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