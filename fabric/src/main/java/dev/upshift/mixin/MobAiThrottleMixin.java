package dev.upshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.upshift.optim.AiThrottle;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Throttles mob AI on the integrated server for mobs that are far from every
 * player.
 *
 * <p>Sensing is skipped entirely on throttled ticks. The full goal scan is
 * replaced by {@code tickRunningGoals(false)}, which is exactly the cheap path
 * vanilla itself uses on the off tick, so running goals keep ticking and
 * navigation keeps moving. Mobs within range, persistent mobs, named mobs,
 * passengers and bosses are never touched.</p>
 *
 * <p>The decision is computed once per game tick and cached on the mob, so the
 * five wrapped calls do not repeat the player-distance scan.</p>
 */
@Mixin(value={Mob.class})
public abstract class MobAiThrottleMixin {
    @Unique
    private int upshift$aiTick = Integer.MIN_VALUE;
    @Unique
    private boolean upshift$aiThrottled;

    @Unique
    private boolean upshift$shouldThrottle() {
        Mob mob = (Mob)(Object)this;
        int tick = mob.tickCount;
        if (tick != this.upshift$aiTick) {
            this.upshift$aiTick = tick;
            this.upshift$aiThrottled = AiThrottle.shouldThrottle(mob);
        }
        return this.upshift$aiThrottled;
    }

    @WrapOperation(
            method={"serverAiStep"},
            at={@At(value="INVOKE",
                    target="Lnet/minecraft/world/entity/ai/sensing/Sensing;tick()V")})
    private void upshift$throttleSensing(Sensing sensing, Operation<Void> original) {
        if (!this.upshift$shouldThrottle()) {
            original.call(sensing);
        }
    }

    @WrapOperation(
            method={"serverAiStep"},
            at={@At(value="INVOKE",
                    target="Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V")})
    private void upshift$throttleGoalTick(GoalSelector selector, Operation<Void> original) {
        if (this.upshift$shouldThrottle()) {
            selector.tickRunningGoals(false);
        } else {
            original.call(selector);
        }
    }
}
