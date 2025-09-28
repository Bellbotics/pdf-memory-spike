package com.example.bds.ml;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class MemorySampler {
    private MemorySampler() {}

    public static <T> Result<T> measure(Supplier<T> task, long periodMillis) {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mem-sampler"); t.setDaemon(true); return t;
        });

        final long[] peak = { usedHeapBytes() };
        var tick = ses.scheduleAtFixedRate(() -> {
            long u = usedHeapBytes();
            if (u > peak[0]) peak[0] = u;
        }, 0, periodMillis, TimeUnit.MILLISECONDS);

        T value;
        try { System.gc(); value = task.get(); }
        finally { tick.cancel(true); ses.shutdownNow(); }

        return new Result<>(value, peak[0] / (1024.0 * 1024.0));
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public record Result<T>(T value, double peakMb) {}
}
