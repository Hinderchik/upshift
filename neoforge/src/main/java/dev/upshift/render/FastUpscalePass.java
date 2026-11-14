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

public final class FastUpscalePass {
    private static RenderPipeline pipeline;
    private GpuBuffer params;
    private int lastW = -1;
    private int lastH = -1;
    private float lastSharpness = Float.NaN;
    private boolean broken;

    public static synchronized List<RenderPipeline> pipelines() {
        if (pipeline == null) {
            BindGroupLayout layout = BindGroupLayout.builder().withSampler("InSampler").withUniform("FastParams", UniformType.UNIFORM_BUFFER).build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            pipeline = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0]).withLocation(Identifier.parse((String)"upshift:pipeline/fsr_fast")).withVertexShader(Identifier.parse((String)"upshift:core/screenquad")).withFragmentShader(Identifier.parse((String)"upshift:core/fsr_fast")).withBindGroupLayout(layout).withColorTargetState(target).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        }
        return List.of(pipeline);
    }

    public boolean isBroken() {
        return this.broken;
    }

    public void reset() {
        this.broken = false;
    }

    public void upscale(CommandEncoder encoder, GpuTextureView input, int inW, int inH, GpuTextureView output, float sharpness) {
        if (this.broken) {
            return;
        }
        try {
            FastUpscalePass.pipelines();
            this.ensureParams(inW, inH, sharpness);
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (RenderPass pass = encoder.createRenderPass(() -> "upshift/fast", output, Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("InSampler", input, sampler);
                pass.setUniform("FastParams", this.params);
                pass.draw(3, 1, 0, 0);
            }
        }
        catch (Throwable t) {
            this.broken = true;
            Upshift.LOG.error("fast upscale failed; upscaling disabled for this session", t);
        }
    }

    private void ensureParams(int inW, int inH, float sharpness) {
        if (this.params != null && this.lastW == inW && this.lastH == inH && this.lastSharpness == sharpness) {
            return;
        }
        float strength = (float)Math.pow(2.0, -sharpness);
        ByteBuffer data = MemoryUtil.memAlloc((int)16);
        data.putFloat(strength).putFloat(1.0f / (float)inW).putFloat(1.0f / (float)inH).putFloat(0.0f);
        data.flip();
        GpuDevice device = RenderSystem.getDevice();
        if (this.params == null) {
            this.params = device.createBuffer(() -> "upshift/fast-params", 136, data);
        } else {
            device.createCommandEncoder().writeToBuffer(this.params.slice(), data);
        }
        MemoryUtil.memFree((ByteBuffer)data);
        this.lastW = inW;
        this.lastH = inH;
        this.lastSharpness = sharpness;
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
