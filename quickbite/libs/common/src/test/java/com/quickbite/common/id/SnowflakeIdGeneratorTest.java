package com.quickbite.common.id;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeIdGeneratorTest {
    @Test
    void uniqueAndIncreasingUnderConcurrency() throws Exception {
        var gen = new SnowflakeIdGenerator(7);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < 16; t++) pool.submit(() -> { for (int i = 0; i < 10_000; i++) ids.add(gen.nextId()); });
        }
        assertThat(ids).hasSize(160_000);                      // no duplicates

        long a = gen.nextId(), b = gen.nextId();
        assertThat(b).isGreaterThan(a);                         // time-sortable
        assertThat((a >> 12) & 0x3FF).isEqualTo(7);             // node id encoded in bits 12..21
    }
}