package dev.upshift.gpu;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.upshift.Upshift;
import java.util.Locale;

public enum Backend {
    OPENGL("OpenGL"),
    VULKAN("Vulkan"),
    NONE("no device");

    private final String label;

    private Backend(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static Backend current() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return NONE;
        }
        try {
            DeviceInfo info = device.getDeviceInfo();
            if (info == null) {
                return NONE;
            }
            String name = info.backendName();
            if (name == null) {
                return NONE;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("vulkan")) {
                return VULKAN;
            }
            if (lower.contains("opengl") || lower.contains("gl")) {
                return OPENGL;
            }
            Upshift.LOG.info("unrecognised Blaze3D backend '{}', treating as OpenGL", (Object)name);
            return OPENGL;
        }
        catch (Throwable t) {
            return NONE;
        }
    }
}
