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
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

public final class Fsr1Pass {
    private static final int EASU_FLOATS = 16;
    private static final int RCAS_FLOATS = 4;
    private static RenderPipeline easuPipeline;
    private static RenderPipeline rcasPipeline;
    private GpuBuffer easuConstants;
    private GpuBuffer rcasConstants;
    private int conInW = -1;
    private int conInH = -1;
    private int conOutW = -1;
    private int conOutH = -1;
    private float conSharpness = Float.NaN;
    private float conAdaptiveStrength = Float.NaN;
    private boolean broken;

    public static synchronized List<RenderPipeline> pipelines() {
        if (easuPipeline == null) {
            BindGroupLayout easuLayout = BindGroupLayout.builder().withSampler("InSampler").withUniform("FsrParams", UniformType.UNIFORM_BUFFER).build();
            BindGroupLayout rcasLayout = BindGroupLayout.builder().withSampler("InSampler").withUniform("RcasParams", UniformType.UNIFORM_BUFFER).build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            easuPipeline = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.parse((String)"upshift:pipeline/fsr_easu")).withVertexShader(Identifier.parse((String)"upshift:core/screenquad")).withFragmentShader(Identifier.parse((String)"upshift:core/fsr_easu")).withBindGroupLayout(easuLayout).withColorTargetState(target).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
            rcasPipeline = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.parse((String)"upshift:pipeline/fsr_rcas")).withVertexShader(Identifier.parse((String)"upshift:core/screenquad")).withFragmentShader(Identifier.parse((String)"upshift:core/fsr_rcas")).withBindGroupLayout(rcasLayout).withColorTargetState(target).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        }
        return List.of(easuPipeline, rcasPipeline);
    }

    public void easu(CommandEncoder encoder, GpuTextureView input, int inW, int inH, GpuTextureView output, int outW, int outH) {
        if (this.broken) {
            return;
        }
        try {
            Fsr1Pass.pipelines();
            this.ensureEasuConstants(inW, inH, outW, outH);
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(() -> "upshift/easu", output, Optional.empty())) {
                pass.setPipeline(easuPipeline);
                pass.bindTexture("InSampler", input, sampler);
                pass.setUniform("FsrParams", this.easuConstants);
                pass.draw(3, 1, 0, 0);
            }
        }
        catch (Throwable t) {
            this.broken = true;
            Upshift.LOG.error("FSR 1 EASU failed; upscaling disabled for this session", t);
        }
    }

    public void rcas(CommandEncoder encoder, GpuTextureView input, GpuTextureView output, float sharpness, float adaptiveStrength) {
        if (this.broken) {
            return;
        }
        try {
            Fsr1Pass.pipelines();
            this.ensureRcasConstants(sharpness, adaptiveStrength);
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (RenderPass pass = encoder.createRenderPass(() -> "upshift/rcas", output, Optional.empty())) {
                pass.setPipeline(rcasPipeline);
                pass.bindTexture("InSampler", input, sampler);
                pass.setUniform("RcasParams", this.rcasConstants);
                pass.draw(3, 1, 0, 0);
            }
        }
        catch (Throwable t) {
            this.broken = true;
            Upshift.LOG.error("FSR 1 RCAS failed; upscaling disabled for this session", t);
        }
    }

    public void rcas(CommandEncoder encoder, GpuTextureView input, GpuTextureView output, float sharpness) {
        // Backwards compatibility: default adaptive strength 0.0 (non-adaptive)
        this.rcas(encoder, input, output, sharpness, 0.0f);
    }

    public boolean isBroken() {
        return this.broken;
    }

    public void reset() {
        this.broken = false;
    }

    private void ensureEasuConstants(int inW, int inH, int outW, int outH) {
        if (this.conInW == inW && this.conInH == inH && this.conOutW == outW && this.conOutH == outH) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (this.easuConstants != null) {
            this.easuConstants.close();
        }
        float inWf = inW;
        float inHf = inH;
        float outWf = Math.max(1, outW);
        float outHf = Math.max(1, outH);
        ByteBuffer easu = MemoryUtil.memAlloc((int)64);
        easu.putFloat(inWf / outWf).putFloat(inHf / outHf).putFloat(0.5f * inWf / outWf - 0.5f).putFloat(0.5f * inHf / outHf - 0.5f);
        easu.putFloat(1.0f / inWf).putFloat(1.0f / inHf).putFloat(1.0f / inWf).putFloat(-1.0f / inHf);
        easu.putFloat(-1.0f / inWf).putFloat(2.0f / inHf).putFloat(1.0f / inWf).putFloat(2.0f / inHf);
        easu.putFloat(0.0f).putFloat(4.0f / inHf).putFloat(0.0f).putFloat(0.0f);
        easu.flip();
        this.easuConstants = device.createBuffer(() -> "upshift/easu-constants", 128, easu);
        MemoryUtil.memFree((ByteBuffer)easu);
        this.conInW = inW;
        this.conInH = inH;
        this.conOutW = outW;
        this.conOutH = outH;
    }

    private void ensureRcasConstants(float sharpness, float adaptiveStrength) {
        if (this.conSharpness == sharpness && this.conAdaptiveStrength == adaptiveStrength && this.rcasConstants != null) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (this.rcasConstants != null) {
            this.rcasConstants.close();
        }
        float strength = (float)Math.pow(2.0, -sharpness);
        ByteBuffer rcas = MemoryUtil.memAlloc((int)16);
        rcas.putFloat(strength).putFloat(adaptiveStrength).putFloat(0.0f).putFloat(0.0f);
        rcas.flip();
        this.rcasConstants = device.createBuffer(() -> "upshift/rcas-constants", 128, rcas);
        MemoryUtil.memFree((ByteBuffer)rcas);
        this.conSharpness = sharpness;
        this.conAdaptiveStrength = adaptiveStrength;
    }

    public void close() {
        if (this.easuConstants != null) {
            this.easuConstants.close();
            this.easuConstants = null;
        }
        if (this.rcasConstants != null) {
            this.rcasConstants.close();
            this.rcasConstants = null;
        }
        this.conInW = -1;
        this.conInH = -1;
        this.conOutW = -1;
        this.conOutH = -1;
        this.conSharpness = Float.NaN;
        this.conAdaptiveStrength = Float.NaN;
    }
}
