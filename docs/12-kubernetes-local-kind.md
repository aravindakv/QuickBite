# 12 — Kubernetes Locally (kind): Helm, Gateway API, Probes, HPA, and Scaling Experiments

**Goal:** run the same images on a 4-node Kubernetes cluster (1 control plane + 3 workers, one per simulated "availability zone"). Then do the things Compose can't:

- restart on failed liveness
- rolling updates
- node drains with zero downtime
- autoscaling

Finally, run the **scaling ladder** (vertical, then horizontal) with k6 and record real numbers. The phone keeps working exactly as before.

---

## Concepts first

| Kubernetes object | What it gives you | Where you'll see it |
|---|---|---|
| Deployment + ReplicaSet | N identical stateless pods; rolling updates | Every service |
| Service | Stable DNS name + L4 load balancing across pods | `order-svc:8082` |
| Probes (startup / liveness / readiness) | Restart stuck pods; route traffic only to ready ones | Watchdog drill |
| PodDisruptionBudget | "Never take me below N" during voluntary disruptions | Node drain |
| topologySpreadConstraints | Spread replicas across zones | 3 fake AZs |
| HorizontalPodAutoscaler | Add or remove pods based on metrics | k6 load test |
| Gateway API (Gateway + HTTPRoute) | L7 edge routing (successor to Ingress) | NGINX Gateway Fabric |
| Operator (Strimzi) | Runs Kafka as a Kubernetes-native resource | Kafka cluster |

---

## Step 1: Create the cluster

`deploy/k8s/kind-cluster.yaml`

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: quickbite
nodes:
  - role: control-plane
  - role: worker
    labels: { topology.kubernetes.io/zone: zone-a }
  - role: worker
    labels: { topology.kubernetes.io/zone: zone-b }
  - role: worker
    labels: { topology.kubernetes.io/zone: zone-c }
```

```bash
scripts/down.sh                                   # free ports/RAM used by Compose
kind create cluster --config deploy/k8s/kind-cluster.yaml
kubectl get nodes -L topology.kubernetes.io/zone
kubectl create namespace quickbite
kubectl config set-context --current --namespace=quickbite
```

### metrics-server (needed by HPA)

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'   # kind uses self-signed kubelet certs
kubectl top nodes     # works after ~60 s
```

---

## Step 2: Infrastructure in the cluster

### 2.1 Kafka via the Strimzi operator (KRaft)

```bash
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-single-node.yaml -n kafka
kubectl wait kafka/my-cluster --for=condition=Ready --timeout=300s -n kafka
kubectl get pods -n kafka
```

The bootstrap address inside the cluster is `my-cluster-kafka-bootstrap.kafka.svc:9092`.

**Why an operator:** Kafka needs ordered startup, stable identities, rolling restarts one broker at a time, certificate management, and more. An operator encodes that operational knowledge as code that reconciles a `Kafka` custom resource. For production, you would change `replicas`, storage and listeners in that resource, not hand-edit pods.

### 2.2 Postgres, Mongo, Redis, Keycloak (dev-grade manifests)

`deploy/k8s/infra/stores.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: postgres-init }
data:
  init-dbs.sql: |
    create database orders;
    create database payments;
---
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: postgres }
spec:
  serviceName: postgres
  replicas: 1
  selector: { matchLabels: { app: postgres } }
  template:
    metadata: { labels: { app: postgres } }
    spec:
      containers:
        - name: postgres
          image: postgres:17-alpine
          env:
            - { name: POSTGRES_USER, value: quickbite }
            - { name: POSTGRES_PASSWORD, value: quickbite }
            - { name: PGDATA, value: /var/lib/postgresql/data/pgdata }
          ports: [{ containerPort: 5432 }]
          readinessProbe: { exec: { command: ["pg_isready", "-U", "quickbite"] }, periodSeconds: 5 }
          volumeMounts:
            - { name: data, mountPath: /var/lib/postgresql/data }
            - { name: init, mountPath: /docker-entrypoint-initdb.d }
      volumes:
        - name: init
          configMap: { name: postgres-init }
  volumeClaimTemplates:
    - metadata: { name: data }
      spec: { accessModes: ["ReadWriteOnce"], resources: { requests: { storage: 2Gi } } }
---
apiVersion: v1
kind: Service
metadata: { name: postgres }
spec: { selector: { app: postgres }, ports: [{ port: 5432 }] }
---
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: mongo }
spec:
  serviceName: mongo
  replicas: 1
  selector: { matchLabels: { app: mongo } }
  template:
    metadata: { labels: { app: mongo } }
    spec:
      containers:
        - name: mongo
          image: mongo:8.0
          ports: [{ containerPort: 27017 }]
          readinessProbe: { exec: { command: ["mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"] }, periodSeconds: 10 }
          volumeMounts: [{ name: data, mountPath: /data/db }]
  volumeClaimTemplates:
    - metadata: { name: data }
      spec: { accessModes: ["ReadWriteOnce"], resources: { requests: { storage: 2Gi } } }
---
apiVersion: v1
kind: Service
metadata: { name: mongo }
spec: { selector: { app: mongo }, ports: [{ port: 27017 }] }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: redis }
spec:
  replicas: 1
  selector: { matchLabels: { app: redis } }
  template:
    metadata: { labels: { app: redis } }
    spec:
      containers:
        - name: redis
          image: redis:7.4-alpine
          args: ["redis-server", "--maxmemory", "256mb", "--maxmemory-policy", "volatile-lru"]
          ports: [{ containerPort: 6379 }]
          readinessProbe: { exec: { command: ["redis-cli", "ping"] }, periodSeconds: 5 }
---
apiVersion: v1
kind: Service
metadata: { name: redis }
spec: { selector: { app: redis }, ports: [{ port: 6379 }] }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: keycloak }
spec:
  replicas: 1
  selector: { matchLabels: { app: keycloak } }
  template:
    metadata: { labels: { app: keycloak } }
    spec:
      containers:
        - name: keycloak
          image: quay.io/keycloak/keycloak:26.4.0
          args: ["start-dev", "--import-realm", "--http-port=8180"]
          env:
            - { name: KC_BOOTSTRAP_ADMIN_USERNAME, value: admin }
            - { name: KC_BOOTSTRAP_ADMIN_PASSWORD, value: admin }
            - { name: KC_HOSTNAME, value: "http://localhost:8180" }   # same public issuer as before (port-forward + adb reverse)
            - { name: KC_HOSTNAME_BACKCHANNEL_DYNAMIC, value: "true" }
            - { name: KC_HEALTH_ENABLED, value: "true" }
          ports: [{ containerPort: 8180 }, { containerPort: 9000 }]
          readinessProbe: { httpGet: { path: /health/ready, port: 9000 }, periodSeconds: 10, failureThreshold: 30 }
          volumeMounts: [{ name: realm, mountPath: /opt/keycloak/data/import }]
      volumes:
        - name: realm
          configMap: { name: keycloak-realm }
---
apiVersion: v1
kind: Service
metadata: { name: keycloak }
spec: { selector: { app: keycloak }, ports: [{ port: 8180 }] }
```

```bash
kubectl create configmap keycloak-realm --from-file=deploy/keycloak/quickbite-realm.json
kubectl apply -f deploy/k8s/infra/stores.yaml
kubectl get pods -w          # wait for all four to be Ready (Ctrl+C)
```

> These are single-replica, **dev-grade** stores. In production you'd use managed services (RDS, DocumentDB/Atlas, ElastiCache) or operators (CloudNativePG, the MongoDB operator, a Redis operator) with replicas, backups and PodDisruptionBudgets.

---

## Step 3: Fix Snowflake node ids before scaling out

With Deployments, pods get random names. The hash fallback from file 02 then has a real collision chance. By the birthday bound, 10 pods out of 1024 ids collide with probability ≈ 10·9 / (2·1024) ≈ **4%**. A collision means **duplicate primary keys**.

**Fix (order-svc):** lease a node id from a Postgres sequence at startup.

`services/order-svc/src/main/resources/db/migration/V2__node_id_seq.sql`

```sql
create sequence snowflake_node_seq;
```

`services/order-svc/src/main/java/com/quickbite/order/config/IdConfig.java`

```java
package com.quickbite.order.config;

import com.quickbite.common.id.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class IdConfig {
    /** Every process start gets the next sequence value -> unique among the last 1024 starts. */
    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator(JdbcClient jdbc) {
        long n = jdbc.sql("select nextval('snowflake_node_seq')").query(Long.class).single();
        return new SnowflakeIdGenerator(n % 1024);
    }
}
```

Rebuild and re-tag the images:

```bash
./gradlew bootJar -x test
for s in gateway catalog-svc order-svc payment-svc location-svc realtime-svc; do
  docker build -f deploy/docker/Dockerfile --build-arg JAR_FILE=services/$s/build/libs/app.jar -t quickbite/$s:dev .
  kind load docker-image quickbite/$s:dev --name quickbite     # copy the image into the kind nodes (no registry needed)
done
```

---

## Step 4: One Helm chart for all six services

**Why one chart:** every service has the same shape (Deployment, Service, probes, HPA, PDB). Only the values differ. This is the Kubernetes equivalent of the Gradle convention plugin.

`deploy/helm/quickbite-service/Chart.yaml`

```yaml
apiVersion: v2
name: quickbite-service
description: Generic chart for QuickBite Spring Boot services
version: 0.1.0
```

`deploy/helm/quickbite-service/values.yaml`

```yaml
image: { repository: quickbite/unknown, tag: dev, pullPolicy: IfNotPresent }
replicas: 2
port: 8080
env: {}                 # map of NAME: value
envFromSecret: ""       # optional Secret name
resources:
  requests: { cpu: 250m, memory: 384Mi }
  limits:   { cpu: "1",  memory: 512Mi }
hpa: { enabled: false, min: 2, max: 6, cpuPercent: 70 }
pdb: { minAvailable: 1 }
```

`deploy/helm/quickbite-service/templates/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels: { app: {{ .Release.Name }} }
spec:
  {{- if not .Values.hpa.enabled }}
  replicas: {{ .Values.replicas }}
  {{- end }}
  revisionHistoryLimit: 3
  strategy:
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }     # zero-downtime rollouts
  selector: { matchLabels: { app: {{ .Release.Name }} } }
  template:
    metadata: { labels: { app: {{ .Release.Name }} } }
    spec:
      terminationGracePeriodSeconds: 30
      securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: { matchLabels: { app: {{ .Release.Name }} } }
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports: [{ name: http, containerPort: {{ .Values.port }} }]
          env:
            {{- range $k, $v := .Values.env }}
            - { name: {{ $k }}, value: {{ $v | quote }} }
            {{- end }}
          {{- if .Values.envFromSecret }}
          envFrom: [{ secretRef: { name: {{ .Values.envFromSecret }} } }]
          {{- end }}
          resources: {{- toYaml .Values.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
          startupProbe:      # JVM warm-up: up to 2 min before liveness kicks in
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: 3
            failureThreshold: 40
          livenessProbe:     # process health only (incl. loopWatchdog) -> restart
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:    # dependencies -> remove from Service endpoints, no restart
            httpGet: { path: /actuator/health/readiness, port: http }
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            preStop:         # let the Service stop sending traffic BEFORE the JVM starts shutting down
              exec: { command: ["sh", "-c", "sleep 5"] }
          volumeMounts: [{ name: tmp, mountPath: /tmp }]
      volumes: [{ name: tmp, emptyDir: {} }]
```

`deploy/helm/quickbite-service/templates/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata: { name: {{ .Release.Name }} }
spec:
  selector: { app: {{ .Release.Name }} }
  ports: [{ name: http, port: {{ .Values.port }}, targetPort: http }]
```

`deploy/helm/quickbite-service/templates/hpa.yaml`

```yaml
{{- if .Values.hpa.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: {{ .Release.Name }} }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: {{ .Release.Name }} }
  minReplicas: {{ .Values.hpa.min }}
  maxReplicas: {{ .Values.hpa.max }}
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: {{ .Values.hpa.cpuPercent }} } }
  behavior:
    scaleDown: { stabilizationWindowSeconds: 120 }   # avoid flapping
{{- end }}
```

`deploy/helm/quickbite-service/templates/pdb.yaml`

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: {{ .Release.Name }} }
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector: { matchLabels: { app: {{ .Release.Name }} } }
```

### Values files

`deploy/helm/values/common.yaml`

```yaml
env:
  KAFKA_BOOTSTRAP: my-cluster-kafka-bootstrap.kafka.svc:9092
  REDIS_HOST: redis
  DB_HOST: postgres
  MONGO_URI: mongodb://mongo:27017/catalog
  KC_ISSUER: http://localhost:8180/realms/quickbite
  KC_JWKS: http://keycloak:8180/realms/quickbite/protocol/openid-connect/certs
  KC_TOKEN_URI: http://keycloak:8180/realms/quickbite/protocol/openid-connect/token
  CATALOG_URL: http://catalog-svc:8081
  LOCATION_URL: http://location-svc:8084
```

> Helm deep-merges maps across multiple `-f` files: `env` from `common.yaml` and from the service file combine, and later files win on the same key. (Lists are replaced, not merged, which is why `env` is a map here.)

`deploy/helm/values/gateway.yaml`

```yaml
image: { repository: quickbite/gateway }
port: 8080
env:
  ORDER_URL: http://order-svc:8082
  PAYMENT_URL: http://payment-svc:8083
  LOCATION_WS_URL: ws://location-svc:8084
  REALTIME_WS_URL: ws://realtime-svc:8085
hpa: { enabled: true, min: 2, max: 6, cpuPercent: 70 }
```

`deploy/helm/values/catalog-svc.yaml`

```yaml
image: { repository: quickbite/catalog-svc }
port: 8081
hpa: { enabled: true, min: 2, max: 6, cpuPercent: 70 }
```

`deploy/helm/values/order-svc.yaml`

```yaml
image: { repository: quickbite/order-svc }
port: 8082
envFromSecret: order-svc-secret
hpa: { enabled: true, min: 2, max: 6, cpuPercent: 70 }
```

`deploy/helm/values/payment-svc.yaml`

```yaml
image: { repository: quickbite/payment-svc }
port: 8083
replicas: 2
```

`deploy/helm/values/location-svc.yaml`

```yaml
image: { repository: quickbite/location-svc }
port: 8084
replicas: 2
```

`deploy/helm/values/realtime-svc.yaml`

```yaml
image: { repository: quickbite/realtime-svc }
port: 8085
replicas: 2
resources:                      # connection-heavy: memory matters more than CPU
  requests: { cpu: 250m, memory: 512Mi }
  limits:   { cpu: "1",  memory: 768Mi }
```

`scripts/k8s-deploy.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
kubectl -n quickbite create secret generic order-svc-secret \
  --from-literal=ORDER_SVC_SECRET=order-svc-secret --dry-run=client -o yaml | kubectl apply -f -
for s in catalog-svc location-svc payment-svc order-svc realtime-svc gateway; do
  helm upgrade --install $s deploy/helm/quickbite-service -n quickbite \
    -f deploy/helm/values/common.yaml -f deploy/helm/values/$s.yaml
done
kubectl -n quickbite get pods -o wide
```

```bash
chmod +x scripts/k8s-deploy.sh && scripts/k8s-deploy.sh
kubectl get pods -o wide -w     # note: replicas land in different zones (NODE column)
```

> `readOnlyRootFilesystem: true` is why `/tmp` is mounted as an `emptyDir`: the JVM and Tomcat write temp files there. If a pod crash-loops with "Read-only file system", find the path it writes to and mount it.

---

## Step 5: Edge routing with the Gateway API (NGINX Gateway Fabric)

The community `ingress-nginx` controller was retired in March 2026. The Gateway API is the current standard, and **NGINX Gateway Fabric** implements it with NGINX.

```bash
# Check the NGF docs for the current version and CRD install command, then:
NGF_VERSION=v2.2.0     # <- set to the latest NGF release
kubectl kustomize "https://github.com/nginx/nginx-gateway-fabric/config/crd/gateway-api/standard?ref=${NGF_VERSION}" | kubectl apply -f -
helm install ngf oci://ghcr.io/nginx/charts/nginx-gateway-fabric --create-namespace -n nginx-gateway \
  --set nginx.service.type=NodePort
kubectl get gatewayclass       # "nginx" should be Accepted
```

`deploy/k8s/edge.yaml`

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: edge, namespace: quickbite }
spec:
  gatewayClassName: nginx
  listeners:
    - { name: http, port: 80, protocol: HTTP }
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: quickbite, namespace: quickbite }
spec:
  parentRefs: [{ name: edge }]
  rules:
    - matches: [{ path: { type: PathPrefix, value: / } }]
      backendRefs: [{ name: gateway, port: 8080 }]
```

```bash
kubectl apply -f deploy/k8s/edge.yaml
kubectl get gateway edge           # PROGRAMMED=True
kubectl get svc -n quickbite       # find the NGINX data-plane service created for "edge" (e.g. edge-nginx)
```

### Expose to your laptop and the phone

`scripts/k8s-forward.sh`

```bash
#!/usr/bin/env bash
# Same ports as Compose -> the app, scripts and adb reverse need no changes.
EDGE_SVC=$(kubectl -n quickbite get svc -o name | grep -m1 edge)
kubectl -n quickbite port-forward "$EDGE_SVC" 8000:80 &
kubectl -n quickbite port-forward svc/keycloak 8180:8180 &
wait
```

```bash
chmod +x scripts/k8s-forward.sh && scripts/k8s-forward.sh     # keep running
# new terminal:
scripts/android-reverse.sh
scripts/e2e.sh                  # the same E2E suite, now against Kubernetes
```

Open the app on the phone. It works unchanged. That is the payoff of keeping one public URL (`localhost:8000` / `localhost:8180`) everywhere.

---

## Step 6: Kubernetes-only drills

### K1: The watchdog triggers a real restart

```bash
ADMIN=$(scripts/token.sh admin admin)
# The switch lives in ONE pod; call it repeatedly so every order-svc pod gets hung
for i in 1 2 3 4; do curl -s -X POST -H "Authorization: Bearer $ADMIN" "localhost:8000/api/orders/admin/debug/hang?on=true"; done
kubectl get pods -l app=order-svc -w
# ~60 s later: RESTARTS goes 0 -> 1 on the hung pods
kubectl describe pod -l app=order-svc | grep -A3 "Liveness probe failed"
```

This is the **end-to-end watchdog chain**: loop stops beating → `LoopWatchdog` DOWN → liveness 503 → kubelet kills the container → fresh process with a working loop. The flag lives in memory, so the restart clears it.

### K2: Zero-downtime rolling update

```bash
k6 run --duration 90s --vus 20 - <<'EOF' &
import http from 'k6/http'; export default () => http.get('http://localhost:8000/api/restaurants');
EOF
kubectl rollout restart deployment/catalog-svc && kubectl rollout status deployment/catalog-svc
wait    # k6 summary: http_req_failed should be 0%
```

This works because of `maxUnavailable: 0`, readiness probes, the `preStop` sleep, and graceful shutdown together.

### K3: Node drain (simulated zone maintenance)

```bash
kubectl drain quickbite-worker2 --ignore-daemonsets --delete-emptydir-data
kubectl get pods -o wide        # pods rescheduled; PDBs kept >= 1 of each service up
scripts/e2e.sh                  # still passes (if a stateful store lived on that node, note the downtime: that is WHY stores need replicas)
kubectl uncordon quickbite-worker2
```

---

## Step 7: The scaling ladder, hands-on (topic 11)

Use the k6 script from file 11 (`scripts/load/browse-and-order.js`). Record each rung in the table at the end.

### Rung 0: Baseline, find one pod's knee

```bash
kubectl scale deployment order-svc catalog-svc gateway --replicas=1
kubectl patch hpa order-svc -p '{"spec":{"minReplicas":1,"maxReplicas":1}}'
kubectl patch hpa catalog-svc -p '{"spec":{"minReplicas":1,"maxReplicas":1}}'
kubectl patch hpa gateway -p '{"spec":{"minReplicas":1,"maxReplicas":1}}'
# Raise the order rate step by step (edit rate: 10 -> 20 -> 40 ...) until p99 breaks the threshold
k6 run scripts/load/browse-and-order.js
kubectl top pods
```

The **knee** is the highest rate that still meets the p99 target. That number is **one pod's capacity** in the capacity formula:

`pods = ceil(peak / (capacity × 0.6))`

### Rung 1: Vertical scaling of the pod

```bash
helm upgrade order-svc deploy/helm/quickbite-service -f deploy/helm/values/common.yaml -f deploy/helm/values/order-svc.yaml \
  --set resources.limits.cpu=2 --set resources.requests.cpu=1 --set resources.limits.memory=1Gi
k6 run scripts/load/browse-and-order.js
```

Compare the knee with rung 0. You'll typically see **less than 2×**. The Universal Scalability Law explains why: contention (the DB pool, `synchronized` in Snowflake, lock waits) and coherency costs grow with concurrency. Vertical scaling is simple, but its returns diminish, and you hit the node size ceiling.

### Rung 2: Vertical scaling of the database

Check where the time goes:

```sql
-- inside the postgres pod: kubectl exec -it postgres-0 -- psql -U quickbite -d orders
select wait_event_type, wait_event, count(*) from pg_stat_activity where datname='orders' group by 1,2;
```

If connections are waiting on locks or I/O, the fix is the DB (indexes, bigger instance, `shared_buffers`), not more app pods.

Also try the HikariCP experiment: `--set env.SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=50` vs `10`. Larger pools often make p99 **worse**.

### Rung 3: Horizontal scaling of stateless services

```bash
kubectl patch hpa order-svc -p '{"spec":{"minReplicas":2,"maxReplicas":6}}'
kubectl patch hpa catalog-svc -p '{"spec":{"minReplicas":2,"maxReplicas":6}}'
kubectl patch hpa gateway -p '{"spec":{"minReplicas":2,"maxReplicas":6}}'
k6 run scripts/load/browse-and-order.js &
kubectl get hpa -w              # watch REPLICAS climb as CPU crosses 70%
```

This works only because the services are stateless:

- sessions live in Redis
- idempotency lives in the DB
- WebSocket fan-out is broadcast

Throughput should now scale near-linearly until the **next** bottleneck (usually the DB or Kafka partitions).

### Rung 4: Offload reads

Compare runs with and without the NGINX edge cache and the Redis cache (set `quickbite.cache.ttl=0s` via env to effectively disable it). Record the catalog pod count the HPA settles at in each case.

### Rung 5: Decouple writes and scale consumers with partitions

```bash
kubectl scale deployment payment-svc --replicas=4
kubectl -n kafka exec -it my-cluster-dual-role-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group payment-svc    # one member has NO partition (3 partitions, 4 pods)

kubectl -n kafka exec -it my-cluster-dual-role-0 -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --alter --topic orders.events --partitions 6
# DLT must match: also alter orders.events.dlt to 6
kubectl -n kafka exec -it my-cluster-dual-role-0 -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --alter --topic orders.events.dlt --partitions 6
```

(The broker pod name comes from the Strimzi example; check it with `kubectl get pods -n kafka`.)

> **Warning:** increasing partitions changes the key → partition mapping. Events for an order already in flight may land on a new partition, so ordering *across* the change isn't guaranteed. That's why you size partitions for future peak **up front** (the capacity plan chose 48 for `rider.location`).

**Optional, KEDA (autoscale consumers on lag, the honest signal for async work):**

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: payment-svc, namespace: quickbite }
spec:
  scaleTargetRef: { name: payment-svc }
  minReplicaCount: 1
  maxReplicaCount: 6            # never more than the partition count
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: my-cluster-kafka-bootstrap.kafka.svc:9092
        consumerGroup: payment-svc
        topic: orders.events
        lagThreshold: "50"
```

### Rung 6: Partition data (sharding)

The capacity sheet showed that orders shard for **storage and locality**, not for write throughput. The mechanics to practise:

- Add a `shard_id = hash(customer_id) % 64` column (logical shards).
- Map logical shards to physical Postgres instances in a small routing table.
- Move one logical shard to a second Postgres pod with `pg_dump --table ... --where shard_id=7` → restore → flip the routing entry.

Because the logical shard count (64) never changes, nothing is rehashed. This is consistent hashing's goal, achieved with a lookup table.

### Rung 7+: More nodes, more regions

- **Nodes:** on a cloud cluster, the Cluster Autoscaler or Karpenter adds nodes when pods are Pending. Simulate it locally: `kind` can't add nodes live, so recreate the cluster with 5 workers and re-run rung 3 with `maxReplicas: 12`.
- **Regions:** two kind clusters = two "regions". Route cities to clusters (Bengaluru → cluster A, Mumbai → cluster B) in the gateway by a `city` claim or header. That is the cell-based architecture from the capacity plan.

### Record your results

| Rung | Config | Orders/s at p99 < 1 s | Browse RPS at p99 < 300 ms | Pods | Bottleneck observed |
|---|---|---|---|---|---|
| 0 | 1 pod, 1 CPU | | | | |
| 1 | 1 pod, 2 CPU | | | | |
| 2 | + DB tuning | | | | |
| 3 | HPA 2–6 pods | | | | |
| 4 | + caches on/off | | | | |
| 5 | 6 partitions, 4–6 consumers | | | | |

These measured numbers replace the estimates in the capacity sheet. That is the real lesson of capacity planning.

---

## Step 8: Observability (optional but recommended)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install mon prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
kubectl -n monitoring port-forward svc/mon-grafana 3000:80    # admin / prom-operator (check the chart's notes)
```

Add `io.micrometer:micrometer-registry-prometheus` to the services, expose `prometheus` in `management.endpoints.web.exposure.include`, and add a `ServiceMonitor`. Then build a RED dashboard (rate, errors, duration) per service. Your k6 runs will light it up.

---

## Clean up

```bash
kind delete cluster --name quickbite
```

## Verify

| Check | Pass condition |
|---|---|
| `kubectl get pods` | All Running/Ready, replicas spread across zones |
| `scripts/e2e.sh` via port-forward | `FAILED: 0` |
| Phone | The full order flow works unchanged |
| K1 | RESTARTS increments on the hung pods |
| K2 | 0% failed requests during rollout |
| K3 | E2E passes during the drain (app tier) |
| Scaling table | All rungs filled with your numbers |
