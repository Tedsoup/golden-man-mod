package goldenman.mixin;

import goldenman.GoldenMan;
import goldenman.NameGlow;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes your name shimmer gold in the tab list (recomputed every frame, so it animates). */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void goldenman$glow(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (GoldenMan.guard() != null) {
            cir.setReturnValue(NameGlow.decorate(cir.getReturnValue()));
        }
    }
}
