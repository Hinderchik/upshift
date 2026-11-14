package dev.upshift.gpu;

import dev.upshift.config.FrameGen;
import dev.upshift.config.Upscaler;
import dev.upshift.gpu.Backend;
import dev.upshift.gpu.GpuProfile;
import java.util.ArrayList;
import java.util.List;

public record CapabilityReport(GpuProfile gpu, Backend backend, String deviceName, String vendorName, String driverInfo, List<Row<Upscaler>> upscalers, List<Row<FrameGen>> frameGenerators) {
    public Row<Upscaler> row(Upscaler upscaler) {
        for (Row<Upscaler> row : this.upscalers) {
            if (row.target() != upscaler) continue;
            return row;
        }
        return new Row<Upscaler>(upscaler, State.UNSUPPORTED, "not probed");
    }

    public Row<FrameGen> row(FrameGen frameGen) {
        for (Row<FrameGen> row : this.frameGenerators) {
            if (row.target() != frameGen) continue;
            return row;
        }
        return new Row<FrameGen>(frameGen, State.UNSUPPORTED, "not probed");
    }

    public List<Upscaler> runnableUpscalers() {
        ArrayList<Upscaler> out = new ArrayList<Upscaler>();
        for (Row<Upscaler> row : this.upscalers) {
            if (!row.ready()) continue;
            out.add(row.target());
        }
        return List.copyOf(out);
    }

    public List<Upscaler> offeredUpscalers() {
        ArrayList<Upscaler> out = new ArrayList<Upscaler>();
        for (Row<Upscaler> row : this.upscalers) {
            if (row.state() == State.UNSUPPORTED) continue;
            out.add(row.target());
        }
        return List.copyOf(out);
    }

    public List<FrameGen> offeredFrameGenerators() {
        ArrayList<FrameGen> out = new ArrayList<FrameGen>();
        for (Row<FrameGen> row : this.frameGenerators) {
            if (row.state() == State.UNSUPPORTED) continue;
            out.add(row.target());
        }
        return List.copyOf(out);
    }

    public List<FrameGen> runnableFrameGenerators() {
        ArrayList<FrameGen> out = new ArrayList<FrameGen>();
        for (Row<FrameGen> row : this.frameGenerators) {
            if (!row.ready()) continue;
            out.add(row.target());
        }
        return List.copyOf(out);
    }

    public List<String> summaryLines() {
        ArrayList<String> lines = new ArrayList<String>();
        lines.add("GPU: " + this.gpu.describe());
        lines.add("backend: " + this.backend.label() + ", driver: " + this.driverInfo);
        for (Row<Upscaler> row : this.upscalers) {
            lines.add("upscale/" + row.target().id() + ": " + String.valueOf((Object)row.state()) + " - " + row.detail());
        }
        for (Row<FrameGen> row : this.frameGenerators) {
            lines.add("framegen/" + row.target().id() + ": " + String.valueOf(row.state()) + " - " + row.detail());
        }
        return lines;
    }

    public record Row<T>(T target, State state, String detail) {
        public boolean ready() {
            return this.state == State.READY;
        }
    }

    public static enum State {
        READY,
        BLOCKED,
        UNSUPPORTED;

    }
}
