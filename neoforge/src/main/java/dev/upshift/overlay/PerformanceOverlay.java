package dev.upshift.overlay;

import dev.upshift.config.FrameGen;
import dev.upshift.config.FsrConfig;
import dev.upshift.overlay.HardwareMonitor;
import dev.upshift.overlay.OverlayMode;
import dev.upshift.overlay.PerfStats;
import dev.upshift.perf.CpuTuner;
import dev.upshift.render.UpscaleManager;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class PerformanceOverlay {
    private static final int PANEL = -1341124592;
    private static final int TITLE = -8400385;
    private static final int LABEL = -6645094;
    private static final int VALUE = -1;
    private static final int GOOD = -9707413;
    private static final int WARN = -2047654;
    private static final int BAD = -2074022;
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 4;
    private static final int MARGIN = 4;
    private static final int GRAPH_WIDTH = 120;
    private static final int GRAPH_HEIGHT = 28;
    private static Entry[] cachedEntries = new Entry[0];
    private static int cachedLabelWidth;
    private static int cachedValueWidth;
    private static long builtAtNs;
    private static OverlayMode builtFor;

    public static void render(GuiGraphicsExtractor graphics) {
        OverlayMode mode = FsrConfig.get().overlay;
        if (mode == OverlayMode.OFF) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        HardwareMonitor hardware = HardwareMonitor.get();
        PerfStats stats = PerfStats.get();
        Font font = minecraft.font;
        PerformanceOverlay.rebuild(minecraft, hardware, stats, mode);
        Entry[] entries = cachedEntries;
        int labelWidth = cachedLabelWidth;
        int valueWidth = cachedValueWidth;
        int contentWidth = Math.max(labelWidth + 8 + valueWidth, mode == OverlayMode.FULL ? 120 : 0);
        int graphLines = mode == OverlayMode.FULL ? 1 : 0;
        int height = 8 + entries.length * 10 + graphLines * 30;
        int width = contentWidth + 8;
        int x = 4;
        int y = 4;
        graphics.fill(x, y, x + width, y + height, -1341124592);
        int cursorY = y + 4;
        for (Entry entry : entries) {
            graphics.text(font, entry.label(), x + 4, cursorY, -6645094, false);
            graphics.text(font, entry.value(), x + 4 + labelWidth + 8, cursorY, entry.color(), false);
            cursorY += 10;
        }
        if (mode == OverlayMode.FULL) {
            PerformanceOverlay.drawGraph(graphics, x + 4, cursorY, stats);
        }
    }

    private static void rebuild(Minecraft minecraft, HardwareMonitor hardware, PerfStats stats, OverlayMode mode) {
        long now = System.nanoTime();
        if (builtFor == mode && now - builtAtNs < 100000000L && cachedEntries.length > 0) {
            return;
        }
        builtFor = mode;
        builtAtNs = now;
        ArrayList<Entry> entries = new ArrayList<Entry>(12);
        int fps = minecraft.getFps();
        int generated = stats.generatedPerSecond();
        double avg = stats.averageMs();
        double worst = stats.worstPercentMs();
        entries.add(new Entry("FPS", fps + (String)(generated > 0 ? " +" + generated : ""), PerformanceOverlay.colorForFps(fps)));
        entries.add(new Entry("frame", PerformanceOverlay.format(avg, " ms"), PerformanceOverlay.colorForMs(avg)));
        entries.add(new Entry("1% low", PerformanceOverlay.format(worst, " ms"), PerformanceOverlay.colorForMs(worst)));
        double latency = stats.latencyMs();
        entries.add(new Entry("latency", PerformanceOverlay.format(latency, " ms"), PerformanceOverlay.colorForLatency(latency)));
        boolean generating = FsrConfig.get().frameGen == FrameGen.INTERNAL && Math.clamp(FsrConfig.get().frameGenMultiplier, 1, 4) > 1;
        entries.add(new Entry("gen", generating ? generated + " f/s" : "off", generating ? (generated > 0 ? -9707413 : -2047654) : -6645094));
        double work = CpuTuner.get().workMs();
        entries.add(new Entry("work", work > 0.0 ? PerformanceOverlay.format(work, " ms") : "-", PerformanceOverlay.colorForMs(work)));
        entries.add(new Entry("CPU", PerformanceOverlay.cpuLine(hardware), -1));
        entries.add(new Entry("GPU", PerformanceOverlay.gpuLine(hardware), -1));
        entries.add(new Entry("VRAM", PerformanceOverlay.vramLine(hardware), -1));
        entries.add(new Entry("RAM", PerformanceOverlay.ramLine(hardware), -1));
        String power = PerformanceOverlay.powerLine(hardware);
        if (!power.isEmpty()) {
            entries.add(new Entry("power", power, -1));
        }
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        int[] internal = UpscaleManager.get().internalSize(windowWidth, windowHeight);
        entries.add(new Entry("render", internal[0] + "x" + internal[1] + " -> " + windowWidth + "x" + windowHeight, -1));
        entries.add(new Entry("upscaler", UpscaleManager.get().activeUpscaler().id() + "  " + Math.round(FsrConfig.get().renderScale() * 100.0f) + "%", -1));
        if (mode == OverlayMode.FULL) {
            String card = PerformanceOverlay.cardLine(hardware);
            if (!card.isEmpty()) {
                entries.add(new Entry("card", card, -1));
            }
            String generator = FsrConfig.get().frameGen.id();
            if (FsrConfig.get().frameGen == FrameGen.INTERNAL) {
                generator = generator + " " + Math.clamp(FsrConfig.get().frameGenMultiplier, 1, 4) + "x";
            }
            entries.add(new Entry("profile", FsrConfig.get().profile.id() + "  fg=" + generator, -6645094));
        }
        int labelWidth = 0;
        int valueWidth = 0;
        for (Entry entry : entries) {
            labelWidth = Math.max(labelWidth, minecraft.font.width(entry.label()));
            valueWidth = Math.max(valueWidth, minecraft.font.width(entry.value()));
        }
        cachedEntries = entries.toArray(new Entry[0]);
        cachedLabelWidth = labelWidth;
        cachedValueWidth = valueWidth;
    }

    private static void drawGraph(GuiGraphicsExtractor graphics, int x, int y, PerfStats stats) {
        double[] samples = stats.recent(120);
        if (samples.length == 0) {
            return;
        }
        double ceiling = Math.max(11.0, Math.min(66.0, stats.averageMs() * 2.0));
        for (double sample : samples) {
            ceiling = Math.max(ceiling, Math.min(sample, 66.0));
        }
        graphics.fill(x, y, x + 120, y + 28, 0x60000000);
        int sixty = y + 28 - (int)Math.round(16.67 / ceiling * 28.0);
        if (sixty > y && sixty < y + 28) {
            graphics.fill(x, sixty, x + 120, sixty + 1, 0x40FFFFFF);
        }
        for (int i = 0; i < samples.length; ++i) {
            int bar = (int)Math.round(Math.min(samples[i], ceiling) / ceiling * 28.0);
            bar = Math.max(1, Math.min(28, bar));
            graphics.fill(x + i, y + 28 - bar, x + i + 1, y + 28, PerformanceOverlay.colorForMs(samples[i]));
        }
    }

    private static String format(double value, String suffix) {
        if (value <= 0.0) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.1f", value) + suffix;
    }

    private static String cpuLine(HardwareMonitor hardware) {
        StringBuilder text = new StringBuilder();
        PerformanceOverlay.append(text, hardware, hardware.cpuLoad(), "%.0f", "%", 0);
        PerformanceOverlay.append(text, hardware, hardware.cpuTemp(), "%.0f", " C", 0);
        PerformanceOverlay.append(text, hardware, hardware.cpuClock(), "%.2f", " GHz", 2);
        if (text.length() > 0) {
            text.append("  ");
        }
        return text.append(hardware.cpuThreads()).append('T').toString();
    }

    private static String gpuLine(HardwareMonitor hardware) {
        StringBuilder text = new StringBuilder();
        PerformanceOverlay.append(text, hardware, hardware.gpuLoad(), "%.0f", "%", 0);
        PerformanceOverlay.append(text, hardware, hardware.gpuTemp(), "%.0f", " C", 0);
        PerformanceOverlay.append(text, hardware, hardware.gpuClock(), "%.0f", " MHz", 0);
        if (!Float.isNaN(hardware.gpuFan())) {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append("fan ").append(Math.round(hardware.gpuFan())).append('%');
        }
        return text.length() == 0 ? "--" : text.toString();
    }

    private static String powerLine(HardwareMonitor hardware) {
        StringBuilder text = new StringBuilder();
        if (!Float.isNaN(hardware.cpuPower())) {
            text.append("CPU ").append(Math.round(hardware.cpuPower())).append(" W");
        }
        if (!Float.isNaN(hardware.gpuPower())) {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append("GPU ").append(Math.round(hardware.gpuPower())).append(" W");
        }
        return text.toString();
    }

    private static String cardLine(HardwareMonitor hardware) {
        String name = hardware.gpuName();
        if (name == null || name.isBlank() || "GPU".equals(name)) {
            return "";
        }
        return name.replace("(R)", "").replace("(TM)", "").replace("NVIDIA GeForce ", "").replace("NVIDIA ", "").replace("AMD Radeon ", "").replace("AMD ", "").replace("Intel(R) ", "Intel ").replaceAll("\\s+", " ").trim();
    }

    private static String vramLine(HardwareMonitor hardware) {
        if (Float.isNaN(hardware.vramUsedMb()) && Float.isNaN(hardware.vramTotalMb())) {
            return "--";
        }
        return hardware.format("%.0f", hardware.vramUsedMb(), "", 0) + "/" + hardware.format("%.0f", hardware.vramTotalMb(), " MB", 0);
    }

    private static String ramLine(HardwareMonitor hardware) {
        if (Float.isNaN(hardware.ramUsedGb()) && Float.isNaN(hardware.ramTotalGb())) {
            return "--";
        }
        return hardware.format("%.1f", hardware.ramUsedGb(), "", 1) + "/" + hardware.format("%.1f", hardware.ramTotalGb(), " GB", 1);
    }

    private static void append(StringBuilder text, HardwareMonitor hardware, float value, String format, String suffix, int decimals) {
        if (Float.isNaN(value)) {
            return;
        }
        if (text.length() > 0) {
            text.append("  ");
        }
        text.append(hardware.format(format, value, suffix, decimals));
    }

    private static int colorForFps(int fps) {
        if (fps >= 100) {
            return -9707413;
        }
        return fps >= 45 ? -2047654 : -2074022;
    }

    private static int colorForMs(double ms) {
        if (ms <= 0.0) {
            return -6645094;
        }
        if (ms <= 8.4) {
            return -9707413;
        }
        return ms <= 22.2 ? -2047654 : -2074022;
    }

    private static int colorForLatency(double ms) {
        if (ms <= 0.0) {
            return -6645094;
        }
        if (ms <= 25.0) {
            return -9707413;
        }
        return ms <= 50.0 ? -2047654 : -2074022;
    }

    private record Entry(String label, String value, int color) {
    }
}
