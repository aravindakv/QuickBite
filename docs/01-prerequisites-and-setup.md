# 01 — Prerequisites and Machine Setup

**Goal:** a laptop that can run about 12 containers, 6 JVMs, and an Android emulator at the same time, with every tool at a known version.

---

## Step 1: Size your machine

| Resource | Minimum | Comfortable | Why |
|---|---|---|---|
| RAM | 16 GB | 32 GB | Kafka ≈ 1 GB, Keycloak ≈ 700 MB, 6 JVMs × ~350 MB, emulator ≈ 2–3 GB |
| CPU | 4 cores | 8+ cores | Gradle builds and the emulator are the heaviest |
| Disk | 30 GB free | 60 GB | Docker images plus the Android SDK |

**Docker Desktop / Rancher Desktop / OrbStack:** give the Docker VM at least **8 GB RAM and 4 CPUs** (Settings → Resources). On Linux, native Docker has no VM limit.

**Why:** most "it randomly fails" problems in local microservice setups are OOM kills inside the Docker VM. Running `docker events` while it happens shows `oom` events.

---

## Step 2: Install the tools

| Tool | Version | Install (macOS: Homebrew / Linux: package manager or SDKMAN) | Check |
|---|---|---|---|
| JDK | 25 (Temurin) | `sdk install java 25-tem` | `java -version` |
| Gradle | 9.1+ (only to generate the wrapper) | `sdk install gradle` | `gradle -v` |
| Docker + Compose v2 | recent | Docker Desktop / OrbStack / `docker-ce` | `docker compose version` |
| kubectl | 1.33+ | `brew install kubectl` | `kubectl version --client` |
| kind | 0.29+ | `brew install kind` | `kind version` |
| Helm | 3.17+ | `brew install helm` | `helm version` |
| Android Studio | latest stable | developer.android.com | includes `adb` |
| jq, curl | any | `brew install jq` | `jq --version` |
| k6 | latest | `brew install k6` | `k6 version` |
| IntelliJ IDEA | any recent | – | Lombok **not** needed; we use records |

**Windows:** use WSL2 (Ubuntu) for everything except Android Studio. Run `adb` from Windows. `adb reverse` works the same way.

Add `adb` to your PATH:

```bash
# macOS example (zsh)
echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.zshrc && source ~/.zshrc
adb version
```

---

## Step 3: Understand how the phone reaches your laptop

This is the most important networking idea in the whole guide, so it comes first.

The phone (or emulator) needs to call **two** things:

1. The API, through NGINX on port **8000**.
2. The Keycloak login page, on port **8180**.

We use **`adb reverse`**:

```bash
adb reverse tcp:8000 tcp:8000
adb reverse tcp:8180 tcp:8180
adb reverse --list
```

This makes `localhost:8000` *on the phone* tunnel over USB (or the emulator bridge) to `localhost:8000` *on your laptop*.

**Why not use `10.0.2.2` (emulator) or your Wi-Fi IP (physical phone)?** Because of the JWT **issuer** (`iss`) claim:

- Keycloak writes its own public URL into every token as `iss`, and the gateway rejects tokens whose `iss` doesn't match exactly.
- If the phone logged in via `10.0.2.2:8180` and your curl tests via `localhost:8180`, you would get two different issuers. One of them would always fail.
- With `adb reverse`, **everyone** (phone, emulator, curl, browser) uses `http://localhost:8180`, so there is exactly one issuer.

Inside Docker, services fetch Keycloak's signing keys from `http://keycloak:8180` (the internal hostname) but still expect `iss = http://localhost:8180/realms/quickbite`. File 03 configures exactly this split.

> **Rule you'll reuse in production:** the *frontend URL* (what goes into `iss`) and the *backchannel URL* (how services reach the identity provider) are two different settings. Mixing them up is the #1 OAuth bug in containerized setups.

`adb reverse` must be re-run **every time the device reconnects** or the emulator restarts. File 10 gives you a one-line script for this.

---

## Step 4: Create the repository skeleton

```bash
mkdir quickbite && cd quickbite
git init
gradle wrapper --gradle-version 9.1.0   # creates gradlew, gradle/wrapper/*
mkdir -p build-logic/src/main/kotlin libs/common services deploy/compose deploy/keycloak deploy/nginx scripts clients
cat > .gitignore <<'EOF'
.gradle/
build/
.idea/
*.iml
local.properties
.DS_Store
.env
EOF
```

**Final layout** (built up over the next files):

```
quickbite/
├── build-logic/                  # Gradle convention plugins (file 02)
├── gradle/libs.versions.toml     # version catalog
├── libs/common/                  # shared Java library (file 02)
├── services/
│   ├── gateway/                  # file 04
│   ├── catalog-svc/              # file 05
│   ├── order-svc/                # file 06
│   ├── payment-svc/              # file 07
│   ├── location-svc/             # file 08
│   └── realtime-svc/             # file 08
├── analytics/surge-job/          # file 13 (Spark)
├── clients/android/              # file 10 (separate Gradle build, opened in Android Studio)
├── deploy/
│   ├── compose/                  # file 03, 09
│   ├── keycloak/                 # realm JSON
│   ├── nginx/                    # nginx.conf
│   ├── docker/                   # Dockerfile (file 09)
│   ├── k8s/ and helm/            # file 12
└── scripts/                      # e2e, rider simulator, load tests
```

**Why a monorepo for learning:**

- One `./gradlew build` compiles and tests everything.
- Shared code lives in one library.
- Refactors that touch the gateway and a service happen in one commit.

In industry, repo-per-service is also common. The architecture is the same either way.

---

## Verify

```bash
java -version            # 25
./gradlew --version      # Gradle 9.1.0, JVM 25
docker run --rm hello-world
docker info | grep -i "total memory"   # >= 8 GiB
adb devices              # your phone/emulator listed as "device"
```

All five commands should succeed before you continue to **02**.
