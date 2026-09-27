package com.quickbite.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoopWatchdogTest {
    @Test
    void goesDownWhenALoopStopsBeating() throws Exception {
        var wd = new LoopWatchdog(Duration.ofMillis(100));
        wd.beat("dispatcher");
        assertThat(wd.health().getStatus()).isEqualTo(Status.UP);
        Thread.sleep(150);
        assertThat(wd.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(wd.health().getDetails().get("stuckLoops")).asList().containsExactly("dispatcher");
        wd.beat("dispatcher");
        assertThat(wd.health().getStatus()).isEqualTo(Status.UP);   // recovers
    }
}