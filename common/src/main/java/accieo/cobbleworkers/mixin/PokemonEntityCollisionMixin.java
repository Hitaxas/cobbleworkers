package accieo.cobbleworkers.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PokemonEntity.class)
public abstract class PokemonEntityCollisionMixin {

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void onIsPushable(CallbackInfoReturnable<Boolean> cir) {
        PokemonEntity self = (PokemonEntity) (Object) this;
        if (self.getPokemon().getStoreCoordinates().get() != null) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        PokemonEntity self = (PokemonEntity) (Object) this;
        if (self.getPokemon().getStoreCoordinates().get() != null) {
            // Clear any collision-induced velocity changes
            // This prevents pokemon from being pushed around
            if (Math.abs(self.getVelocity().x) < 0.1 && Math.abs(self.getVelocity().z) < 0.1) {
                self.setVelocity(self.getVelocity().multiply(0, 1, 0));
            }
        }
    }
}