package com.example.bds.ml;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Utility for sampling the JVM heap while executing a task, reporting the peak
 * sampled heap usage in megabytes together with the task's return value.
 * <p>
 * This is intended for lightweight, coarse-grained instrumentation during
 * experimentation or demos where you want a quick sense of how much heap a
 * piece of work might consume. It uses a single daemon {@link ScheduledExecutorService}
 * to periodically poll {@link Runtime#totalMemory()} - {@link Runtime#freeMemory()}
 * and keeps the maximum observed value as the "peak".
 *
 * <h2>What is measured?</h2>
 * <ul>
 *   <li><b>Only JVM heap</b> is sampled (i.e., the managed heap reported by
 *       {@link Runtime}).</li>
 *   <li><b>Not sampled:</b> off-heap/native allocations (direct byte buffers,
 *       mmap, JNI, PDFBox native libs, etc.), thread stacks, metaspace, code
 *       cache, or the process's RSS. For a complete picture, use OS tooling or
 *       JVM Native Memory Tracking.</li>
 * </ul>
 *
 * <h2>Accuracy &amp; caveats</h2>
 * <ul>
 *   <li>Sampling is periodic; very short-lived spikes between ticks may be
 *       missed, yielding an <i>underestimate</i> of the true peak.</li>
 *   <li>A best-effort {@code System.gc()} is invoked immediately before the
 *       task runs to reduce noise from prior allocations. This is only a hint
 *       to the JVM and is <em>not</em> guaranteed.</li>
 *   <li>If the task allocates mostly off-heap memory, the reported peak will
 *       not reflect that.</li>
 *   <li>Sampling overhead is small but non-zero (one extra thread + polling).
 *       For microbenchmarks, consider JMH or flight recordings instead.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * A single daemon thread named {@code mem-sampler} performs the sampling.
 * It is started for the duration of {@link #measure(Supplier, long)} and is
 * shut down in a {@code finally} block to avoid leaks, even if the task throws.
 *
 * <h2>Exceptions</h2>
 * If the supplied task throws, the exception is propagated to the caller after
 * the sampler is cancelled and the scheduler is shut down. No {@code Result}
 * is returned in that case.
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * MemorySampler.Result<Integer> r = MemorySampler.measure(() -> {
 *     // your workload here
 *     return doPdfWork();
 * }, 25); // sample every 25 ms
 *
 * System.out.printf("Value=%d, PeakHeap=%.1f MB%n", r.value(), r.peakMb());
 * }</pre>
 *
 * @since 1.0
 */
public final class MemorySampler {

    /** Non-instantiable utility class. */
    private MemorySampler() {}

    /**
     * Execute a task while periodically sampling the JVM heap usage, returning
     * the task's value and the <em>maximum sampled</em> heap in megabytes.
     * <p>
     * Implementation notes:
     * <ul>
     *   <li>Creates a single-threaded, daemon {@link ScheduledExecutorService}
     *       that calls {@link #usedHeapBytes()} every {@code periodMillis}.</li>
     *   <li>Invokes {@code System.gc()} just before running the task to reduce
     *       cross-run noise (best-effort only).</li>
     *   <li>Cancels the sampling task and shuts down the scheduler in a
     *       {@code finally} block.</li>
     * </ul>
     *
     * @param <T>           the task's return type
     * @param task          the work to execute; must be non-null. If this supplier
     *                      throws, its exception is propagated after cleanup.
     * @param periodMillis  sampling period in milliseconds; smaller values may
     *                      capture spikes more accurately at the cost of overhead.
     * @return a {@link Result} containing the task's return value and the peak
     *         sampled heap usage in megabytes
     * @throws NullPointerException if {@code task} is {@code null}
     * @implNote This method measures <strong>heap usage only</strong>. It does not
     *           capture native/off-heap allocations or process RSS.
     */
    public static <T> Result<T> measure(Supplier<T> task, long periodMillis) {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mem-sampler");
            t.setDaemon(true);
            return t;
        });

        final long[] peak = { usedHeapBytes() };

        // Start periodic sampling (fixed rate helps avoid drift over long tasks).
        var tick = ses.scheduleAtFixedRate(() -> {
            long u = usedHeapBytes();
            if (u > peak[0]) peak[0] = u;
        }, 0, periodMillis, TimeUnit.MILLISECONDS);

        T value;
        try {
            // Best-effort to reduce interference from previous allocations.
            System.gc();
            value = task.get();
        } finally {
            // Always stop the sampler and scheduler to avoid thread leaks.
            tick.cancel(true);
            ses.shutdownNow();
        }

        // Convert bytes to megabytes using 1024^2 (MiB).
        return new Result<>(value, peak[0] / (1024.0 * 1024.0));
    }

    /**
     * Compute current used heap in bytes as {@code totalMemory - freeMemory}.
     *
     * @return used heap in bytes at the instant of the call
     */
    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * Immutable container for the measured result.
     *
     * @param value  the task's return value
     * @param peakMb the maximum sampled JVM heap usage in megabytes
     * @param <T>    type of the task's return value
     */
    public record Result<T>(T value, double peakMb) {}
}
