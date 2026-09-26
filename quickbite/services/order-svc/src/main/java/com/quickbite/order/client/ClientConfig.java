package com.quickbite.order.client;

import com.quickbite.common.context.PropagatingInterceptor;
import com.quickbite.common.security.ServiceAuthInterceptor;
import com.quickbite.common.security.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ClientConfig {

    @Bean
    ServiceTokenProvider serviceTokenProvider(@Value("${quickbite.service-auth.token-uri}") String tokenUri,
                                              @Value("${quickbite.service-auth.client-id}") String clientId,
                                              @Value("${quickbite.service-auth.client-secret}") String secret) {
        return new ServiceTokenProvider(tokenUri, clientId, secret);
    }

    @Bean
    Clients.CatalogClient catalogClient(@Value("${quickbite.catalog-url}") String url, ServiceTokenProvider tokens) {
        return create(url, tokens, Clients.CatalogClient.class);
    }

    @Bean
    Clients.LocationClient locationClient(@Value("${quickbite.location-url}") String url, ServiceTokenProvider tokens) {
        return create(url, tokens, Clients.LocationClient.class);
    }

    private static <T> T create(String baseUrl, ServiceTokenProvider tokens, Class<T> type) {
        // TIMEOUTS ARE MANDATORY. A call without a timeout can hang a thread forever,
        // and the circuit breaker can only count failures that actually finish.
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(2));
        RestClient rc = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new ServiceAuthInterceptor(tokens))
                .requestInterceptor(new PropagatingInterceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(type);
    }
}