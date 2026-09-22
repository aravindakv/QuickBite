package com.quickbite.common.context;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
public class ContextAutoConfiguration {
    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        var reg = new FilterRegistrationBean<>(new CorrelationFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE); // before Spring Security, so auth failures are logged with ids too
        return reg;
    }
}