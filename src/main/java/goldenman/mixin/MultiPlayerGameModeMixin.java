package goldenman.mixin;

import goldenman.GoldenMan;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every click in a menu (from you or from another mod) goes through handleContainerInput
 * before it is sent to Hypixel, so this is the one place that needs guarding.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void goldenman$guardClick(int containerId, int slotNum, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (GoldenMan.guard() != null && GoldenMan.guard().onContainerClick(containerId, slotNum, input, player)) {
            ci.cancel();
        }
    }
}
