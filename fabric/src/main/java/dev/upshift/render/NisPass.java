package dev.upshift.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.Upshift;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

public final class NisPass {
    private static final int TABLE_FLOATS = 512;
    private static final int TABLE_BYTES = 2048;
    private static final int HEADER_BYTES = 48;
    private static final int BLOCK_BYTES = 4144;
    private static RenderPipeline pipeline;
    private static byte[] scalerTable;
    private static byte[] usmTable;
    private GpuBuffer params;
    private int lastW = -1;
    private int lastH = -1;
    private float lastSharpness = Float.NaN;
    private boolean broken;

    public static synchronized List<RenderPipeline> pipelines() {
        if (pipeline == null) {
            BindGroupLayout layout = BindGroupLayout.builder().withSampler("InSampler").withUniform("NisParams", UniformType.UNIFORM_BUFFER).build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            pipeline = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.parse((String)"upshift:pipeline/nis_scale")).withVertexShader(Identifier.parse((String)"upshift:core/screenquad")).withFragmentShader(Identifier.parse((String)"upshift:core/nis_scale")).withBindGroupLayout(layout).withColorTargetState(target).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        }
        return List.of(pipeline);
    }

    public boolean isBroken() {
        return this.broken;
    }

    public void reset() {
        this.broken = false;
    }

    public void upscale(CommandEncoder encoder, GpuTextureView input, int inW, int inH, GpuTextureView output, int outW, int outH, float sharpness) {
        if (this.broken) {
            return;
        }
        try {
            NisPass.pipelines();
            this.ensureParams(inW, inH, outW, outH, sharpness);
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (RenderPass pass = encoder.createRenderPass(() -> "upshift/nis", output, Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("InSampler", input, sampler);
                pass.setUniform("NisParams", this.params);
                pass.draw(3, 1, 0, 0);
            }
        }
        catch (Throwable t) {
            this.broken = true;
            Upshift.LOG.error("NIS upscale failed; falling back to the fast path", t);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void ensureParams(int inW, int inH, int outW, int outH, float sharpness) {
        if (this.params != null && this.lastW == inW && this.lastH == inH && this.lastSharpness == sharpness) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        ByteBuffer block = MemoryUtil.memAlloc((int)4144);
        try {
            block.putFloat((float)inW / (float)outW).putFloat((float)inH / (float)outH).putFloat(inW).putFloat(inH);
            float slider = Math.clamp(sharpness, 0.0f, 1.0f) - 0.5f;
            float maxScale = slider >= 0.0f ? 1.25f : 1.75f;
            float minScale = slider >= 0.0f ? 1.25f : 1.0f;
            float limitScale = slider >= 0.0f ? 1.25f : 1.0f;
            float strengthMin = Math.max(0.0f, 0.4f + slider * minScale * 1.2f);
            float strengthMax = 1.6f + slider * maxScale * 1.8f;
            float limitMin = Math.max(0.1f, 0.14f + slider * limitScale * 0.32f);
            float limitMax = 0.5f + slider * limitScale * 0.6f;
            block.putFloat(strengthMin).putFloat(0.0f).putFloat(strengthMax - strengthMin).putFloat(limitMin);
            block.putFloat(limitMax - limitMin).putFloat(0.0f).putFloat(slider).putFloat(0.0f);
            block.put(NisPass.table("coef_scaler.bin"));
            block.put(NisPass.table("coef_usm.bin"));
            block.flip();
            if (this.params == null) {
                this.params = device.createBuffer(() -> "upshift/nis-params", 136, block);
            } else {
                device.createCommandEncoder().writeToBuffer(this.params.slice(), block);
            }
        }
        finally {
            MemoryUtil.memFree((ByteBuffer)block);
        }
        this.lastW = inW;
        this.lastH = inH;
        this.lastSharpness = sharpness;
    }

    private static synchronized byte[] table(String name) {
        byte[] bytes;
        if ("coef_scaler.bin".equals(name) && scalerTable != null) {
            return scalerTable;
        }
        if ("coef_usm.bin".equals(name) && usmTable != null) {
            return usmTable;
        }
        try (InputStream stream = NisPass.class.getResourceAsStream("/assets/upshift/nis/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing NIS table " + name);
            }
            bytes = stream.readAllBytes();
        }
        catch (Exception e) {
            throw new IllegalStateException("could not read NIS table " + name, e);
        }
        if (bytes.length != 2048) {
            throw new IllegalStateException("NIS table " + name + " has " + bytes.length + " bytes, expected 2048");
        }
        if ("coef_scaler.bin".equals(name)) {
            scalerTable = bytes;
        } else {
            usmTable = bytes;
        }
        return bytes;
    }

    public void close() {
        if (this.params != null) {
            this.params.close();
            this.params = null;
        }
        this.lastW = -1;
        this.lastH = -1;
        this.lastSharpness = Float.NaN;
    }
}
