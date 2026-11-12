package goldenman.mixin;

import goldenman.GoldenMan;
import goldenman.NameGlow;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hypixel's tab list entries are text lines stored as display names. Other tab list mods
 * (like Skyblocker's fancy tab HUD) read them from here, so gold-ing your name here makes it show up there too.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void goldenman$glow(CallbackInfoReturnable<Component> cir) {
        Component name = cir.getReturnValue();
        if (name != null && GoldenMan.guard() != null) {
            cir.setReturnValue(NameGlow.decorate(name));
        }
    }
}
