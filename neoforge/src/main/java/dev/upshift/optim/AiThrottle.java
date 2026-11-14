package dev.upshift.optim;

import dev.upshift.config.FsrConfig;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * Distance-based AI throttle for the integrated server.
 *
 * <p>A mob that is far from every player still pays for its sensing tick on
 * every game tick and for the full goal scan on every other one, even though
 * nobody can observe the result. Such mobs keep moving through
 * {@code PathNavigation}, which is cheap, but their goals and senses only run
 * once every few ticks.</p>
 *
 * <p>Everything here uses primitive math and the level's player list; no
 * {@code Vec3} is allocated per mob per tick.</p>
 */
public final class AiThrottle {
    private AiThrottle() {
    }

    /**
     * @return true when this mob's sensing and full goal scan should be skipped
     *         on this tick. Running goals still tick on the cheap path.
     */
    public static boolean shouldThrottle(Mob mob) {
        if (ModCompat.skipAiThrottle()) {
            return false;
        }
        FsrConfig config = FsrConfig.get();
        int interval = config.aiThrottleInterval;
        int distance = config.aiThrottleDistance;
        if (!config.throttleAi || interval <= 1 || distance <= 0) {
            return false;
        }
        if (mob.isRemoved()
                || mob.isPassenger()
                || mob.isVehicle()
                || mob.isPersistenceRequired()
                || mob.hasCustomName()
                || mob instanceof EnderDragon
                || mob instanceof WitherBoss) {
            return false;
        }
        /*
         * Run the full AI tick on the phase vanilla already reserves for goal
         * updates, so an even interval always lands on a full tick.
         */
        if ((mob.tickCount + mob.getId()) % interval == 0) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        List<ServerPlayer> players = level.players();
        double limitSquared = (double) distance * (double) distance;
        double x = mob.getX();
        double y = mob.getY();
        double z = mob.getZ();
        for (int i = 0; i < players.size(); ++i) {
            ServerPlayer player = players.get(i);
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= limitSquared) {
                return false;
            }
        }
        return true;
    }
}
