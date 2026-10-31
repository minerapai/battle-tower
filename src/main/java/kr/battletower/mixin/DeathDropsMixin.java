package kr.battletower.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonServerDelegate;
import kr.battletower.TowerBattles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tower opponents have no owner, so Cobblemon treats them like wild pokemon and drops their held item and species
 * loot when they faint. They are marked by the generator; skip the drops for them.
 */
@Mixin(value = PokemonServerDelegate.class, remap = false)
public abstract class DeathDropsMixin {
    @Inject(method = "doDeathDrops", at = @At("HEAD"), cancellable = true)
    private void battletower$noDrops(CallbackInfo ci) {
        if (((PokemonServerDelegate) (Object) this).getEntity().getPokemon().getPersistentData().getBoolean(TowerBattles.TAG)) {
            ci.cancel();
        }
    }
}
