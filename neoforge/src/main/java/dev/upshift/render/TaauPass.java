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
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.Upshift;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

public final class TaauPass {
    private static RenderPipeline pipeline;
    private GpuTexture[] textures = new GpuTexture[2];
    private GpuTextureView[] views = new GpuTextureView[2];
    private int width = -1;
    private int height = -1;
    private int writeIndex;
    private GpuTextureView lastOutput;
    private boolean hasHistory;
    private GpuBuffer params;
    private GpuBuffer projectionInverseBuffer;
    private final ByteBuffer data = MemoryUtil.memAlloc((int)80);
    private ByteBuffer projectionInverseData;
    private final boolean[] broken = new boolean[1];
    private final Matrix4f warp = new Matrix4f();
    private final Matrix4f rotationInverse = new Matrix4f();
    private final Matrix4f projectionInverse = new Matrix4f();

    public static synchronized List<RenderPipeline> pipelines() {
        if (pipeline == null) {
            BindGroupLayout layout = BindGroupLayout.builder()
                .withSampler("ColorSampler")
                .withSampler("HistorySampler")
                .withSampler("DepthSampler")
                .withUniform("TaauParams", UniformType.UNIFORM_BUFFER)
                .withUniform("ProjectionInverse", UniformType.UNIFORM_BUFFER)
                .build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            pipeline = RenderPipeline.builder((RenderPipeline.Snippet[])new RenderPipeline.Snippet[0])
                .withLocation(Identifier.parse((String)"upshift:pipeline/taau_resolve"))
                .withVertexShader(Identifier.parse((String)"upshift:core/screenquad"))
                .withFragmentShader(Identifier.parse((String)"upshift:core/taau_resolve"))
                .withBindGroupLayout(layout)
                .withColorTargetState(target)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        }
        return List.of(pipeline);
    }

    public GpuTextureView lastOutput() {
        return this.lastOutput;
    }

    public boolean isBroken() {
        return this.broken[0];
    }

    public void invalidate() {
        this.hasHistory = false;
    }

    public GpuTextureView resolve(CommandEncoder encoder, GpuTextureView current, int w, int h, Matrix4f projection, Matrix4f rotation, Matrix4f previous, float currentWeight) {
        return resolveInternal(encoder, current, w, h, projection, rotation, previous, currentWeight, null, null);
    }

    public GpuTextureView resolveDepthAware(CommandEncoder encoder, GpuTextureView current, int w, int h, Matrix4f projection, Matrix4f rotation, Matrix4f previous, float currentWeight, GpuTextureView depthTexture, Matrix4f projectionInverse) {
        return resolveInternal(encoder, current, w, h, projection, rotation, previous, currentWeight, depthTexture, projectionInverse);
    }

    private GpuTextureView resolveInternal(CommandEncoder encoder, GpuTextureView current, int w, int h, Matrix4f projection, Matrix4f rotation, Matrix4f previous, float currentWeight, GpuTextureView depthTexture, Matrix4f projectionInverse) {
        if (this.broken[0] || current == null) {
            return current;
        }
        try {
            this.ensure(w, h);
            this.writeIndex ^= 1;
            GpuTextureView destination = this.views[this.writeIndex];
            GpuTextureView source = this.views[this.writeIndex ^ 1];

            // Compute warp matrix: current clip space -> previous clip space
            Matrix4f warp = this.warp.set((Matrix4fc)projection).mul((Matrix4fc)previous).mul((Matrix4fc)this.rotationInverse.set((Matrix4fc)rotation).invert()).mul((Matrix4fc)this.projectionInverse.set((Matrix4fc)projection).invert());

            this.data.clear();
            warp.get(0, this.data);
            this.data.position(64);
            this.data.putFloat(currentWeight);
            this.data.putFloat(depthTexture != null ? 1.0f : 0.0f); // y: depthAware flag
            this.data.putFloat(0.0f);
            this.data.putFloat(this.hasHistory ? 1.0f : 0.0f);
            this.data.position(0).limit(80);
            this.ensureBuffer();
            encoder.writeToBuffer(this.params.slice(), this.data);

            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            try (RenderPass pass = encoder.createRenderPass(() -> "upshift/taau", destination, Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("ColorSampler", current, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.bindTexture("HistorySampler", source, sampler);
                if (depthTexture != null) {
                    pass.bindTexture("DepthSampler", depthTexture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                }
                if (projectionInverse != null) {
                    // upload projection inverse for depth reconstruction
                    this.projectionInverseData.clear();
                    projectionInverse.get(0, this.projectionInverseData);
                    this.ensureProjectionInverseBuffer();
                    encoder.writeToBuffer(this.projectionInverseBuffer.slice(), this.projectionInverseData);
                    pass.setUniform("ProjectionInverse", this.projectionInverseBuffer);
                }
                pass.setUniform("TaauParams", this.params);
                pass.draw(3, 1, 0, 0);
            }
            this.hasHistory = true;
            this.lastOutput = destination;
            return destination;
        }
        catch (Throwable t) {
            this.broken[0] = true;
            Upshift.LOG.error("temporal resolve failed; falling back to a plain resize", t);
            return current;
        }
    }

    private void ensureProjectionInverseBuffer() {
        if (this.projectionInverseBuffer != null) {
            return;
        }
        this.projectionInverseData = MemoryUtil.memAlloc((int)64);
        this.projectionInverseBuffer = RenderSystem.getDevice().createBuffer(() -> "upshift/taau-proj-inv", 136, this.projectionInverseData);
    }

    private void ensure(int w, int h) {
        if (this.width == w && this.height == h && this.views[0] != null) {
            return;
        }
        this.closeAll();
        GpuDevice device = RenderSystem.getDevice();
        int usage = 12;
        for (int i = 0; i < 2; ++i) {
            this.textures[i] = device.createTexture("upshift/taau" + i, usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
            this.views[i] = device.createTextureView(this.textures[i]);
        }
        this.width = w;
        this.height = h;
        this.hasHistory = false;
    }

    private void ensureBuffer() {
        if (this.params != null) {
            return;
        }
        ByteBuffer empty = MemoryUtil.memAlloc((int)80);
        this.params = RenderSystem.getDevice().createBuffer(() -> "upshift/taau-params", 136, empty);
        MemoryUtil.memFree((ByteBuffer)empty);
    }

    private void closeAll() {
        for (int i = 0; i < 2; ++i) {
            if (this.views[i] != null) {
                this.views[i].close();
                this.views[i] = null;
            }
            if (this.textures[i] == null) continue;
            this.textures[i].close();
            this.textures[i] = null;
        }
        this.width = -1;
        this.height = -1;
        this.lastOutput = null;
    }

    public void close() {
        this.closeAll();
        if (this.params != null) {
            this.params.close();
            this.params = null;
        }
        if (this.projectionInverseBuffer != null) {
            this.projectionInverseBuffer.close();
            this.projectionInverseBuffer = null;
        }
        if (this.projectionInverseData != null) {
            MemoryUtil.memFree(this.projectionInverseData);
            this.projectionInverseData = null;
        }
    }
}
