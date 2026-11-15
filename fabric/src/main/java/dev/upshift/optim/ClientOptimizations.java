package dev.upshift.optim;

import dev.upshift.config.FsrConfig;
import dev.upshift.optim.ModCompat;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * Cheap, frame-local decisions used by the culling mixins.
 *
 * <p>Everything here is deliberately allocation-free: the entity and particle
 * paths run thousands of times per frame, so a cache miss must not create work
 * of its own.</p>
 */
public final class ClientOptimizations {
    /** Particles spawned since the last particle tick, per type. */
    private static final Object2IntOpenHashMap<ParticleType<?>> PARTICLE_COUNTS = new Object2IntOpenHashMap<>(64);
    private static int particleTotal;

    /**
     * Camera position supplied by {@code BlockEntityRenderDispatcher.prepare}
     * once per frame. Reusing it avoids calling into the camera for every
     * block entity, and the distance test below never allocates a Vec3.
     */
    private static Vec3 cameraPos;

    /** Global per-client-tick counter used for animation frame skipping. */
    private static int upshift$clientTickCounter;

    /** Called once per frame at the start of the client tick. */
    public static void beginClientTick() {
        upshift$clientTickCounter++;
    }

    private ClientOptimizations() {
    }

    /** Called once per frame, before any block entity is extracted. */
    public static void setCameraPosition(Vec3 position) {
        cameraPos = position;
    }

    /**
     * Distance test for block entities, mirroring the vanilla per-renderer
     * range check while avoiding its {@code Vec3.atCenterOf} allocation.
     *
     * @return true when the block entity should be skipped entirely.
     */
    public static boolean cullBlockEntity(BlockEntity blockEntity) {
        if (ModCompat.skipBlockEntityCull()) {
            return false;
        }
        FsrConfig config = FsrConfig.get();
        int limit = config.blockEntityDistance;
        if (limit <= 0) {
            return false;
        }
        Vec3 camera = cameraPos;
        if (camera == null) {
            return false;
        }
        BlockPos pos = blockEntity.getBlockPos();
        double dx = pos.getX() + 0.5 - camera.x;
        double dy = pos.getY() + 0.5 - camera.y;
        double dz = pos.getZ() + 0.5 - camera.z;
        return dx * dx + dy * dy + dz * dz > (double) limit * limit;
    }

    /**
     * Checks if a sprite's animation should advance this tick.
     *
     * @param spriteName the sprite's Identifier (namespace:path)
     * @return true when the animation should NOT advance this tick (i.e. skip)
     */
    public static boolean shouldAdvanceAnimation(Identifier spriteName) {
        if (ModCompat.skipAnimationFreeze()) {
            return false;
        }
        FsrConfig config = FsrConfig.get();

        // Check whitelist first - whitelisted sprites always animate
        if (isWhitelisted(spriteName, config.animationWhitelist)) {
            return false;
        }

        // Frame skip logic using global counter
        int skip = config.animationFrameSkip;
        if (skip > 1 && upshift$clientTickCounter % skip != 0) {
            return true;
        }

        // Unfocused freeze
        return config.freezeAnimationsUnfocused && !Minecraft.getInstance().isWindowActive();
    }

    /**
     * Wildcard whitelist matcher.
     * Supports exact match (namespace:path), prefix (namespace:*), suffix (*:path), and full wildcard (*).
     */
    private static boolean isWhitelisted(Identifier spriteName, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        String full = spriteName.toString(); // namespace:path
        String path = spriteName.getPath();  // just the path part

        for (String pattern : whitelist) {
            if (pattern == null || pattern.isEmpty()) continue;
            if ("*".equals(pattern)) return true;
            if (full.equals(pattern)) return true;
            if (pattern.startsWith("*:")) {
                if (path.equals(pattern.substring(2))) return true;
            } else if (pattern.endsWith(":*")) {
                String ns = pattern.substring(0, pattern.length() - 2);
                if (full.startsWith(ns + ":")) return true;
            }
        }
        return false;
    }

    /** Called once per particle tick; the budget is a per-tick budget. */
    public static void onParticleTick() {
        if (particleTotal != 0 || !PARTICLE_COUNTS.isEmpty()) {
            PARTICLE_COUNTS.clear();
            particleTotal = 0;
        }
    }

    /**
     * @return true when the particle should not be created at all.
     */
    public static boolean cullParticle(ParticleOptions options, double x, double y, double z) {
        if (ModCompat.skipParticleCull()) {
            return false;
        }
        FsrConfig config = FsrConfig.get();
        if (!config.limitParticles) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        if (camera != null && camera.isInitialized() && config.particleDistance > 0) {
            Vec3 pos = camera.position();
            double dx = x - pos.x;
            double dy = y - pos.y;
            double dz = z - pos.z;
            double limit = config.particleDistance;
            if (dx * dx + dy * dy + dz * dz > limit * limit) {
                return true;
            }
        }

        if (config.particleTickBudget <= 0) {
            return false;
        }
        if (particleTotal >= config.particleTickBudget) {
            return true;
        }
        ParticleType<?> type = options.getType();
        int count = PARTICLE_COUNTS.getInt(type);
        if (count >= config.particleTypeBudget) {
            return true;
        }
        PARTICLE_COUNTS.put(type, count + 1);
        ++particleTotal;
        return false;
    }

    /**
     * @param dx entity X minus camera X
     * @param dy entity Y minus camera Y
     * @param dz entity Z minus camera Z
     * @return true when the entity should be skipped entirely.
     */
    public static boolean cullEntity(Entity entity, double dx, double dy, double dz) {
        if (ModCompat.skipEntityCull()) {
            return false;
        }
        FsrConfig config = FsrConfig.get();
        Minecraft minecraft = Minecraft.getInstance();
        if (entity == minecraft.player) {
            return false;
        }
        if (config.cullInvisibleMobs
                && entity.isInvisible()
                && !(entity instanceof Player)
                && !entity.isCurrentlyGlowing()) {
            return true;
        }
        int limit = config.entityCullDistance;
        if (limit > 0) {
            return dx * dx + dy * dy + dz * dz > (double) limit * limit;
        }
        return false;
    }
}