package com.quickbite.common.id;

public final class SnowflakeIdGenerator {
    private static final long EPOCH = 1767225600000L; // 2026-01-01T00:00:00Z
    private final long nodeId;
    private long lastTs = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("nodeId must be 0..1023");
        this.nodeId = nodeId;
    }

    public synchronized long nextId() {
        long ts = System.currentTimeMillis();
        if (ts < lastTs) ts = lastTs;              // clock went backwards (NTP): reuse last ms, never go back
        if (ts == lastTs) {
            sequence = (sequence + 1) & 0xFFF;     // 4096 ids per ms per node
            if (sequence == 0) {                   // exhausted this ms: spin to the next one
                while ((ts = System.currentTimeMillis()) <= lastTs) Thread.onSpinWait();
            }
        } else {
            sequence = 0;
        }
        lastTs = ts;
        return ((ts - EPOCH) << 22) | (nodeId << 12) | sequence;
    }
}