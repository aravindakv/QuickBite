package com.quickbite.common.web;

import com.quickbite.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(ApiExceptionHandler.class)
public class WebAutoConfiguration {
    /** NODE_ID must be unique per running instance. Compose sets it; the hash fallback is OK for local dev.
     *  Services can replace this bean (file 12 does, using a DB sequence) -> @ConditionalOnMissingBean. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    SnowflakeIdGenerator snowflakeIdGenerator(@Value("${NODE_ID:#{null}}") Integer nodeId) {
        int id = nodeId != null ? nodeId : Math.floorMod(System.getenv().getOrDefault("HOSTNAME", "local").hashCode(), 1024);
        return new SnowflakeIdGenerator(id);
    }
}