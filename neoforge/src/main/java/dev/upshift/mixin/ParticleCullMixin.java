package dev.upshift.mixin;

import dev.upshift.optim.ClientOptimizations;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how many particles the client creates per tick. Vanilla already drops
 * everything past 32 blocks, but a busy farm or a large explosion can still
 * spawn thousands of quads in a single tick.
 */
@Mixin(value={ParticleEngine.class})
public abstract class ParticleCullMixin {
    @Inject(method={"createParticle"}, at={@At(value="HEAD")}, cancellable=true)
    private void upshift$cullParticle(ParticleOptions options, double x, double y, double z,
                                      double velocityX, double velocityY, double velocityZ,
                                      CallbackInfoReturnable<Particle> callback) {
        if (ClientOptimizations.cullParticle(options, x, y, z)) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method={"tick"}, at={@At(value="HEAD")})
    private void upshift$resetParticleBudget(CallbackInfo callback) {
        ClientOptimizations.onParticleTick();
    }
}
