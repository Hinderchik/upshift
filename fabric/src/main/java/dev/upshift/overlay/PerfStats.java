package dev.upshift.overlay;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class PerfStats {
    private static final PerfStats INSTANCE = new PerfStats();
    private static final int WINDOW = 240;
    private final long[] frames = new long[240];
    private final long[] sorted = new long[240];
    private int cursor;
    private int count;
    private long lastNs;
    private long statsStampNs;
    private double cachedAverage;
    private double cachedWorst;
    private double[] recentCache = new double[0];
    private long recentStampNs;
    private final AtomicInteger generatedInWindow = new AtomicInteger();
    private final AtomicInteger reusedInWindow = new AtomicInteger();
    private volatile int generatedPerSecond;
    private volatile int reusedPerSecond;
    private long generatedWindowStart = System.nanoTime();
    private long renderFrameStartNs;
    private volatile double latencyMs;

    private PerfStats() {
    }

    public static PerfStats get() {
        return INSTANCE;
    }

    public synchronized void onFrame() {
        long now = System.nanoTime();
        long delta = this.lastNs == 0L ? 0L : now - this.lastNs;
        this.lastNs = now;
        if (delta <= 0L || delta > 2000000000L) {
            return;
        }
        this.frames[this.cursor] = delta;
        this.cursor = (this.cursor + 1) % 240;
        if (this.count < 240) {
            ++this.count;
        }
        if (now - this.generatedWindowStart > 1000000000L) {
            this.generatedPerSecond = this.generatedInWindow.getAndSet(0);
            this.reusedPerSecond = this.reusedInWindow.getAndSet(0);
            this.generatedWindowStart = now;
        }
        if (now - this.statsStampNs > 100000000L) {
            this.restamp(now);
        }
    }

    public void onGeneratedFrame() {
        this.generatedInWindow.incrementAndGet();
    }

    /**
     * Marks the start of a frame. Paired with {@link #endRenderFrame()} when the window
     * surface is actually presented, so the result covers every step from extracting the
     * frame to the image being handed to the display.
     */
    public void beginRenderFrame() {
        this.renderFrameStartNs = System.nanoTime();
    }

    public void endRenderFrame() {
        long start = this.renderFrameStartNs;
        this.renderFrameStartNs = 0L;
        if (start == 0L) {
            return;
        }
        double ms = (double)(System.nanoTime() - start) / 1000000.0;
        if (ms < 1.0 || ms > 1000.0) {
            return;
        }
        double previous = this.latencyMs;
        this.latencyMs = previous <= 0.0 ? ms : previous * 0.85 + ms * 0.15;
    }

    public double latencyMs() {
        return this.latencyMs;
    }

    public void onMenuFrame() {
        this.reusedInWindow.incrementAndGet();
    }

    public synchronized double averageMs() {
        return this.cachedAverage;
    }

    private void restamp(long now) {
        this.statsStampNs = now;
        if (this.count == 0) {
            this.cachedAverage = 0.0;
            this.cachedWorst = 0.0;
            return;
        }
        long sum = 0L;
        for (int i = 0; i < this.count; ++i) {
            sum += this.frames[i];
        }
        this.cachedAverage = (double)sum / 1000000.0 / (double)this.count;
        if (this.count < 20) {
            this.cachedWorst = 0.0;
            return;
        }
        System.arraycopy(this.frames, 0, this.sorted, 0, this.count);
        Arrays.sort(this.sorted, 0, this.count);
        int index = Math.max(0, (int)Math.ceil((double)this.count * 0.99) - 1);
        this.cachedWorst = (double)this.sorted[index] / 1000000.0;
    }

    public synchronized double worstPercentMs() {
        return this.cachedWorst;
    }

    public synchronized double maximumMs() {
        if (this.count == 0) {
            return 0.0;
        }
        double max = 0.0;
        for (int i = 0; i < this.count; ++i) {
            max = Math.max(max, (double)this.frames[i] / 1000000.0);
        }
        return max;
    }

    public int generatedPerSecond() {
        return this.generatedPerSecond;
    }

    public int reusedPerSecond() {
        return this.reusedPerSecond;
    }

    public synchronized double[] recent(int size) {
        long now;
        double[] out = this.recentCache;
        if (out.length != size) {
            this.recentCache = out = new double[size];
            this.recentStampNs = 0L;
        }
        if ((now = System.nanoTime()) - this.recentStampNs < 100000000L) {
            return out;
        }
        this.recentStampNs = now;
        int n = Math.min(size, this.count);
        for (int i = 0; i < size; ++i) {
            out[i] = i < n ? (double)this.frames[(this.cursor - n + i + 240) % 240] / 1000000.0 : 0.0;
        }
        return out;
    }
}
