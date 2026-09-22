package com.quickbite.common.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import java.time.Duration;

@AutoConfiguration
public class WatchdogAutoConfiguration {
    @Bean("loopWatchdog") // bean name = health component name used in the liveness group
    LoopWatchdog loopWatchdog(@Value("${quickbite.watchdog.timeout:30s}") Duration timeout) {
        return new LoopWatchdog(timeout);
    }
}