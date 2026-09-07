package app.gamenative.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight bridge from lsfg-vk's atomic stats.txt state to the X Present
 * scheduler. The gate fails closed: GameNative keeps its normal frame limiter
 * until the native layer has published a fresh, ready LSFG swapchain context.
 *
 * Stats polling is intentionally asynchronous. This method is called from the
 * render path, so filesystem latency must never be allowed to stall a frame.
 */
public final class LsfgRuntimeGate {
    private static final long STATS_FRESHNESS_MS = 2_000L;
    private static final long POLL_INTERVAL_NS = 100_000_000L;

    private static final ExecutorService POLL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LsfgRuntimeGatePoll");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private static final AtomicLong configurationGeneration = new AtomicLong(0L);

    private static volatile File statsFile;
    private static volatile long nextPollNs;
    private static volatile boolean cachedReady;

    private LsfgRuntimeGate() {}

    public static synchronized void configure(File containerRoot) {
        File next = containerRoot == null
                ? null
                : new File(containerRoot, ".config/lsfg-vk/stats.txt");
        if (next == null ? statsFile == null : next.equals(statsFile)) return;
        statsFile = next;
        cachedReady = false;
        nextPollNs = 0L;
        configurationGeneration.incrementAndGet();
    }

    /**
     * Return the last published readiness state and opportunistically schedule
     * a refresh when the 100 ms poll interval expires. No filesystem access or
     * waiting occurs on the caller's render/present thread.
     */
    public static boolean isGenerationReady() {
        final boolean ready = cachedReady;
        final long nowNs = System.nanoTime();
        if (nowNs < nextPollNs || !pollInFlight.compareAndSet(false, true)) {
            return ready;
        }

        nextPollNs = nowNs + POLL_INTERVAL_NS;
        final File file = statsFile;
        final long generation = configurationGeneration.get();
        try {
            POLL_EXECUTOR.execute(() -> pollReadyState(file, generation));
        } catch (RejectedExecutionException | SecurityException ignored) {
            pollInFlight.set(false);
            cachedReady = false;
            nextPollNs = 0L;
        }
        return ready;
    }

    private static void pollReadyState(File file, long generation) {
        try {
            final boolean ready = readReadyState(file);
            if (configurationGeneration.get() == generation) {
                cachedReady = ready;
            }
        } finally {
            pollInFlight.set(false);
        }
    }

    private static boolean readReadyState(File file) {
        if (file == null || !file.isFile()) return false;
        final long ageMs = System.currentTimeMillis() - file.lastModified();
        if (ageMs < 0L || ageMs > STATS_FRESHNESS_MS) return false;

        boolean active = false;
        boolean generationReady = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("active=1")) active = true;
                else if (line.equals("generation_ready=1")) generationReady = true;
            }
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        return active && generationReady;
    }
}
