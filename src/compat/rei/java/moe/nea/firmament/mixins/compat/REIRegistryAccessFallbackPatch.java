package moe.nea.firmament.mixins.compat;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * REI 26.2.820 can return null from InstanceHelper#registryAccess after its first
 * failed lookup. The first failure returns a built-in-registry fallback, but once
 * REI has set warnedRegistryAccess=true a later lookup can fall through and return
 * null. Code such as EntryIngredients.ofItemTag then dereferences that null provider
 * while Hypixel is still finishing the join/tag synchronization.
 *
 * Keep REI's normal registry lookup untouched and only replace a null return value
 * with the same safe fallback REI itself uses on the first failure.
 */
@Pseudo
@Mixin(targets = "me.shedaniel.rei.impl.common.util.InstanceHelper", remap = false)
public class REIRegistryAccessFallbackPatch {
	@Inject(method = "registryAccess", at = @At("RETURN"), cancellable = true, remap = false)
	private void firmament$neverReturnNullRegistryAccess(CallbackInfoReturnable<RegistryAccess> cir) {
		if (cir.getReturnValue() == null) {
			cir.setReturnValue(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		}
	}
}
