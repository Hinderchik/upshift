package dev.upshift.vr;

import dev.upshift.Upshift;
import dev.upshift.render.TaauPass;
import dev.upshift.render.UpscaleManager;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Runtime state for VR rendering.
 * <p>
 * Detects stereo passes (two renderLevel calls per renderFrame) and keeps
 * per-eye history buffers, temporal matrices, and jitter synchronization.
 * All state is inert when VR is not active.
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

        // Initialize optimizers on first VR frame
        if (!dev.upshift.vr.render.VrVulkanOptimizer.get().vulkanInitialized) {
            dev.upshift.vr.render.VrVulkanOptimizer.get().initialize();
        }
        if (!dev.upshift.vr.render.VrMobileOptimizer.get().mobileDetected()) {
            dev.upshift.vr.render.VrMobileOptimizer.get().detect();
        }
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
     * Called once per frame before eye passes.
     */
    public void updateHmdPose() {
        if (!dev.upshift.vr.config.VrConfig.get().vrFrameGenEnabled) {
            return;
        }
        try {
            // Try to access Vivecraft's OpenXR pose predictor
            Class<?> vrSystem = Class.forName("jrbudda.vivecraft.Vivecraft");
            // Common pattern: Vivecraft.getInstance().getHmdPosePredicted()
            // or Vivecraft.getOpenXR().getPredictedDisplayTime() + getPose()
            Object instance = vrSystem.getMethod("getInstance").invoke(null);
            if (instance == null) return;

            // Try various method names for predicted pose
            String[] poseMethods = {"getPredictedHmdPose", "getHmdPose", "getHeadPose", "getPredictedPose"};
            for (String methodName : poseMethods) {
                try {
                    java.lang.reflect.Method m = vrSystem.getMethod(methodName);
                    Object pose = m.invoke(instance);
                    if (pose != null) {
                        // Try to extract Matrix4f or float[16]
                        Matrix4f mat = extractMatrix4f(pose);
                        if (mat != null) {
                            // Shift history
                            this.hmdPoseHistory[1 - this.hmdHistoryIndex].set(this.hmdPoseHistory[this.hmdHistoryIndex]);
                            this.hmdPositionHistory[1 - this.hmdHistoryIndex].set(this.hmdPositionHistory[this.hmdHistoryIndex]);
                            this.hmdPoseHistory[this.hmdHistoryIndex].set(mat);
                            // Extract translation
                            this.hmdPositionHistory[this.hmdHistoryIndex].set(
                                mat.m30(), mat.m31(), mat.m32()
                            );
                            this.hmdHistoryIndex = 1 - this.hmdHistoryIndex;
                            this.hasHmdPose = true;
                            break;
                        }
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable t) {
            // Vivecraft not present or API different - silently ignore
        }
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
            // Check for QuestCraft or mobile indicators
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method isModLoaded = loader.getMethod("isModLoaded", String.class);
            this.isMobileVR = (Boolean) isModLoaded.invoke(instance, "questcraft") ||
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
     * Detects Vivecraft by checking for its mod ID at runtime.
     * No compile-time dependency on Vivecraft classes.
     * Returns false if VR is not active (mod present but not in VR mode).
     */
    private boolean isVivecraftPresent() {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            java.lang.reflect.Method getInstance = loader.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            java.lang.reflect.Method isModLoaded = loader.getMethod("isModLoaded", String.class);
            boolean modPresent = (Boolean) isModLoaded.invoke(instance, "vivecraft");
            if (!modPresent) return false;
            
            // Additional check: is VR actually active?
            // Try to detect if we're in a VR session
            return isVrSessionActive();
        } catch (Throwable t) {
            return false;
        }
    }
    
    /**
     * Checks if VR session is actually active (headset connected, VR mode enabled).
     * This prevents the mod from activating when Vivecraft is installed but not in VR.
     */
    private boolean isVrSessionActive() {
        try {
            // Check for Vivecraft VR session indicators
            Class<?> vrSystem = Class.forName("jrbudda.vivecraft.Vivecraft");
            Object instance = vrSystem.getMethod("getInstance").invoke(null);
            if (instance == null) return false;
            
            // Check if VR is enabled/active - try many possible method names
            String[] activeMethods = {
                "isVRActive", "isInVR", "isVREnabled", "getVRStatus",
                "isVRMode", "isHMDActive", "isHeadsetConnected", "isVRRunning",
                "getVRState", "isInVRMode", "vrActive", "inVR"
            };
            for (String methodName : activeMethods) {
                try {
                    java.lang.reflect.Method m = vrSystem.getMethod(methodName);
                    Object result = m.invoke(instance);
                    if (result instanceof Boolean) {
                        return (Boolean) result;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            
            // Also check for OpenXR session state
            try {
                Class<?> openXR = Class.forName("jrbudda.vivecraft.openxr.OpenXR");
                Object oxrInstance = openXR.getMethod("getInstance").invoke(null);
                if (oxrInstance != null) {
                    String[] oxrMethods = {"isSessionRunning", "isActive", "getSessionState"};
                    for (String mName : oxrMethods) {
                        try {
                            java.lang.reflect.Method m = openXR.getMethod(mName);
                            Object result = m.invoke(oxrInstance);
                            if (result instanceof Boolean) {
                                return (Boolean) result;
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            
            // If we can't determine, check stereo rendering as fallback
            return this.stereoDetected;
        } catch (Throwable t) {
            // Vivecraft classes not accessible or different version
            // Fall back to stereo detection
            return this.stereoDetected;
        }
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