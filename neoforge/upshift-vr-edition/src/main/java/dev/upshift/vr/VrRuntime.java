package dev.upshift.vr;

import dev.upshift.Upshift;
import dev.upshift.render.TaauPass;
import dev.upshift.render.UpscaleManager;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Runtime state for VR rendering.
 * <p>
 * Detects stereo passes (two renderLevel calls per renderFrame) and keeps
 * per-eye history buffers, temporal matrices, and jitter synchronization.
 * All state is inert when VR is not active. On a platform where Vivecraft
 * isn't installed (for instance NeoForge before its VR port lands), the
 * presence check falls back to always returning {@code false}, so the
 * addon never allocates or mutates anything.
 */
public final class VrRuntime {
    public static final VrRuntime INSTANCE = new VrRuntime();

    private boolean stereoDetected;
    private int passIndex;
    private long frameStartNs;

    // Per-eye TaauPass instances and rotation state
    private final dev.upshift.vr.render.VrTaauPass leftTaau = new dev.upshift.vr.render.VrTaauPass();
    private final dev.upshift.vr.render.VrTaauPass rightTaau = new dev.upshift.vr.render.VrTaauPass();
    private final Matrix4f leftPreviousRotation = new Matrix4f();
    private final Matrix4f rightPreviousRotation = new Matrix4f();
    private boolean leftHasPreviousRotation;
    private boolean rightHasPreviousRotation;

    // Shared jitter index (advanced once per frame pair)
    private int jitterIndex;

    // VR frame generation state
    private final Matrix4f[] hmdPoseHistory = new Matrix4f[2]; // [previous, current]
    private final Vector3f[] hmdPositionHistory = new Vector3f[2];
    private int hmdHistoryIndex = 0;
    private boolean hasHmdPose = false;

    // Hand/controller layer snapshots (for native-rate compositing)
    private dev.upshift.vr.render.HandLayerSnapshot leftHandSnapshot;
    private dev.upshift.vr.render.HandLayerSnapshot rightHandSnapshot;

    // Foveated rendering stub state
    private dev.upshift.vr.render.FoveationParams foveationParams;

    // Vulkan swapchain safety
    private boolean vulkanBackend;
    private int vrPresentCount = 0;

    // QuestCraft/mobile detection
    private boolean isMobileVR;

    private VrRuntime() {
        this.hmdPoseHistory[0] = new Matrix4f();
        this.hmdPoseHistory[1] = new Matrix4f();
        this.hmdPositionHistory[0] = new Vector3f();
        this.hmdPositionHistory[1] = new Vector3f();
    }

    public static VrRuntime get() {
        return INSTANCE;
    }

    /**
     * Call at the start of renderFrame to reset per-frame VR state.
     */
    public void beginFrame() {
        this.stereoDetected = false;
        this.passIndex = 0;
        this.frameStartNs = System.nanoTime();
        this.vrPresentCount = 0;

        // Defer optimizer initialization until after we know a VR session is
        // actually running; see isVivecraftPresent / isVrSessionActive.
    }

    /**
     * Call at HEAD of renderLevel. Returns the eye index (0 = left, 1 = right)
     * or -1 if stereo not active.
     */
    public int beginEyePass() {
        if (!this.isVivecraftPresent()) {
            return -1;
        }
        int eye = this.passIndex++;
        if (this.passIndex > 1) {
            this.stereoDetected = true;
        }
        return eye;
    }

    /**
     * Call at RETURN of renderLevel.
     */
    public void endEyePass(int eye) {
        // Nothing needed here for now; per-eye history swap happens inside TaauPass
    }

    /**
     * Call at the end of renderFrame to finalize frame pair.
     */
    public void endFrame() {
        this.jitterIndex++;
        dev.upshift.vr.perf.VrAutoResolution.get().update();
    }

    /**
     * Returns the TaauPass for the given eye (0 = left, 1 = right).
     * Creates per-eye instance lazily.
     */
    public dev.upshift.vr.render.VrTaauPass taauForEye(int eye) {
        return eye == 0 ? this.leftTaau : this.rightTaau;
    }

    /**
     * Returns the previous rotation matrix for the given eye.
     */
    public Matrix4f previousRotationForEye(int eye) {
        return eye == 0 ? this.leftPreviousRotation : this.rightPreviousRotation;
    }

    /**
     * Returns whether the given eye has a previous rotation stored.
     */
    public boolean hasPreviousRotation(int eye) {
        return eye == 0 ? this.leftHasPreviousRotation : this.rightHasPreviousRotation;
    }

    /**
     * Sets the previous rotation for the given eye.
     */
    public void setPreviousRotation(int eye, Matrix4f rotation) {
        if (eye == 0) {
            this.leftPreviousRotation.set(rotation);
            this.leftHasPreviousRotation = true;
        } else {
            this.rightPreviousRotation.set(rotation);
            this.rightHasPreviousRotation = true;
        }
    }

    /**
     * Returns the shared jitter index (incremented once per frame pair).
     */
    public int jitterIndex() {
        return this.jitterIndex;
    }

    /**
     * Returns the VR-specific temporal clamp weight (stricter than flat).
     * Value comes from VrConfig.
     */
    public float vrClampWeight() {
        return dev.upshift.vr.config.VrConfig.get().vrTemporalClampWeight;
    }

    // ========== HMD Pose Tracking (for frame generation) ==========

    /**
     * Attempts to read the predicted HMD pose from Vivecraft/OpenXR via reflection.
     * Called once per frame before eye passes. No-op unless Vivecraft is present.
     */
    public void updateHmdPose() {
        if (!dev.upshift.vr.config.VrConfig.get().vrFrameGenEnabled) {
            return;
        }
        // The underlying Vivecraft reflection lives in VrRuntime.isVivecraftPresent /
        // isVrSessionActive – keep it gated there. For this port we rely on the
        // public API exposed by the existing VRClientAPI/VivecraftClientAPI
        // interfaces if they ever appear on the Forge runtime. Stub kept
        // identical to Fabric for when such a port arrives.
        // TODO: wire to Vivecraft's org.vivecraft.api.client.data.VRPose when a
        //       NeoForge build ships.
    }

    /**
     * Returns the predicted HMD pose for the next frame (interpolated/extrapolated).
     */
    public Matrix4f getPredictedHmdPose(float frameFraction) {
        if (!this.hasHmdPose) return new Matrix4f().identity();
        Matrix4f prev = this.hmdPoseHistory[1 - this.hmdHistoryIndex];
        Matrix4f curr = this.hmdPoseHistory[this.hmdHistoryIndex];
        Matrix4f out = new Matrix4f();
        // Spherical linear interpolation of rotation + linear interpolation of translation
        interpolatePose(prev, curr, frameFraction, out);
        return out;
    }

    /**
     * Returns the HMD translation delta between frames (for micro-translation warp).
     */
    public Vector3f getHmdTranslationDelta() {
        if (!this.hasHmdPose) return new Vector3f();
        Vector3f prev = this.hmdPositionHistory[1 - this.hmdHistoryIndex];
        Vector3f curr = this.hmdPositionHistory[this.hmdHistoryIndex];
        return new Vector3f(curr).sub(prev);
    }

    private Matrix4f extractMatrix4f(Object pose) {
        try {
            // Try direct Matrix4f
            if (pose instanceof Matrix4f) return (Matrix4f) pose;
            // Try float[16]
            if (pose instanceof float[]) {
                float[] arr = (float[]) pose;
                if (arr.length >= 16) {
                    Matrix4f m = new Matrix4f();
                    m.set(arr);
                    return m;
                }
            }
            // Try double[16]
            if (pose instanceof double[]) {
                double[] arr = (double[]) pose;
                if (arr.length >= 16) {
                    Matrix4f m = new Matrix4f();
                    for (int i = 0; i < 16; i++) {
                        int row = i / 4;
                        int col = i % 4;
                        m.set(row, col, (float) arr[i]);
                    }
                    return m;
                }
            }
            // Try getMatrix() method
            java.lang.reflect.Method m = pose.getClass().getMethod("getMatrix");
            Object arr = m.invoke(pose);
            if (arr instanceof float[]) return extractMatrix4f(arr);
            if (arr instanceof double[]) return extractMatrix4f(arr);
        } catch (Throwable ignored) {}
        return null;
    }

    private void interpolatePose(Matrix4f a, Matrix4f b, float t, Matrix4f out) {
        // Extract rotations as quaternions
        Quaternionf qa = new Quaternionf();
        Quaternionf qb = new Quaternionf();
        a.getUnnormalizedRotation(qa);
        b.getUnnormalizedRotation(qb);
        qa.normalize(); qb.normalize();
        Quaternionf q = new Quaternionf(qa).slerp(qb, t);
        out.rotation(q);
        // Interpolate translation
        out.m30(a.m30() + (b.m30() - a.m30()) * t);
        out.m31(a.m31() + (b.m31() - a.m31()) * t);
        out.m32(a.m32() + (b.m32() - a.m32()) * t);
    }

    // ========== Hand/Controller Layer ==========

    /**
     * Captures the hand/controller render layer for native-rate compositing.
     * Called from mixin after hand render pass.
     */
    public void captureHandLayer(int eye, com.mojang.blaze3d.pipeline.RenderTarget handTarget) {
        if (!dev.upshift.vr.config.VrConfig.get().vrFrameGenEnabled) return;
        if (handTarget == null) return;
        try {
            dev.upshift.vr.render.HandLayerSnapshot snap = new dev.upshift.vr.render.HandLayerSnapshot();
            snap.eye = eye;
            snap.texture = handTarget.getColorTexture();
            snap.view = handTarget.getColorTextureView();
            snap.width = handTarget.width;
            snap.height = handTarget.height;
            snap.pose = new Matrix4f(); // Would need controller pose from Vivecraft
            if (eye == 0) this.leftHandSnapshot = snap;
            else this.rightHandSnapshot = snap;
        } catch (Throwable ignored) {}
    }

    public dev.upshift.vr.render.HandLayerSnapshot getHandSnapshot(int eye) {
        return eye == 0 ? this.leftHandSnapshot : this.rightHandSnapshot;
    }

    // ========== Foveated Rendering Stub ==========

    public dev.upshift.vr.render.FoveationParams getFoveationParams() {
        if (this.foveationParams == null) {
            this.foveationParams = new dev.upshift.vr.render.FoveationParams();
        }
        return this.foveationParams;
    }

    public boolean isFoveationEnabled() {
        return dev.upshift.vr.config.VrConfig.get().vrFoveatedEnabled && this.foveationParams != null && this.foveationParams.enabled;
    }

    // ========== Vulkan Safety ==========

    public void setVulkanBackend(boolean vulkan) {
        this.vulkanBackend = vulkan;
    }

    public boolean isVulkanBackend() {
        return this.vulkanBackend;
    }

    public void recordVrPresent() {
        this.vrPresentCount++;
    }

    public int getVrPresentCount() {
        return this.vrPresentCount;
    }

    // ========== QuestCraft/Mobile ==========

    public void detectMobileVR() {
        try {
            this.isMobileVR = ModList.get().isLoaded("questcraft") ||
                              System.getProperty("os.name").toLowerCase().contains("android");
        } catch (Throwable t) {
            this.isMobileVR = false;
        }
    }

    public boolean isMobileVR() {
        return this.isMobileVR;
    }

    /**
     * Invalidates per-eye history when resolution changes or on context loss.
     */
    public void invalidate() {
        this.leftTaau.invalidate();
        this.rightTaau.invalidate();
        this.leftHasPreviousRotation = false;
        this.rightHasPreviousRotation = false;
        this.hasHmdPose = false;
        this.leftHandSnapshot = null;
        this.rightHandSnapshot = null;
    }

    /**
     * Closes per-eye resources.
     */
    public void close() {
        this.leftTaau.close();
        this.rightTaau.close();
    }

    /**
     * Detects whether a VR-capable Vivecraft instance is on the classpath AND
     * currently running a headset session. Used by beginEyePass to gate all
     * per-eye paths. The addon must remain inert if the mod is present but no
     * VR session is active.
     */
    private boolean isVivecraftPresent() {
        if (!ModList.get().isLoaded("vivecraft")) return false;
        return isVrSessionActive();
    }

    /**
     * Checks if VR session is actually active (headset connected, VR mode enabled).
     * This prevents the mod from activating when Vivecraft is installed but not in VR.
     */
    private boolean isVrSessionActive() {
        try {
            // Try the public VRClientAPI from the Vivecraft bundle first – it
            // exposes isVRActive() and isVRInitialized() cleanly across API
            // versions.
            Class<?> clientApi = Class.forName("org.vivecraft.api.client.VRClientAPI");
            Object api = clientApi.getMethod("instance").invoke(null);
            Boolean active = (Boolean) clientApi.getMethod("isVRActive").invoke(api);
            if (active != null && active) return true;
            Boolean initialized = (Boolean) clientApi.getMethod("isVRInitialized").invoke(api);
            if (initialized != null && initialized) return true;
        } catch (ClassNotFoundException ignored) {
            // VRClientAPI may not be exported from the bundle — fall through.
        } catch (Throwable t) {
            // Non-fatal during normal desktop play without a headset.
            Upshift.LOG.debug("VR session probe failed (expected when no headset): {}", t.getMessage());
            return false;
        }

        // Legacy field fallback for older Vivecraft variants.
        try {
            Class<?> vrState = Class.forName("org.vivecraft.client_vr.VRState");
            Boolean running = (Boolean) vrState.getField("VR_RUNNING").get(null);
            if (running != null && running) return true;
        } catch (Throwable ignored) {}

        // As a last resort accept the session once we have observed two
        // renderLevel invocations (stereoDetected), which can only happen when
        // Vivecraft is routing world rendering through two separate eye passes.
        return this.stereoDetected;
    }

    /**
     * Returns whether stereo rendering is active (two eyes per frame).
     */
    public boolean isStereo() {
        return this.stereoDetected;
    }

    /**
     * Resets per-frame state at the start of a new frame pair.
     */
    public void resetPerFrame() {
        this.passIndex = 0;
        this.stereoDetected = false;
    }
}
