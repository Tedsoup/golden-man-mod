package goldenman.mixin;

import goldenman.GoldenMan;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces Bazaar buttons you aren't allowed to use (buy orders, sell offers, non-cookie instant buys). */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Shadow
    protected Slot hoveredSlot;

    @Unique
    private static ItemStack goldenman$filler;

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void goldenman$hideSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (GoldenMan.guard() != null && GoldenMan.guard().isHiddenButton((Screen) (Object) this, slot.getItem())) {
            // Draw a plain glass pane (like Hypixel's empty menu slots) instead of the button
            if (goldenman$filler == null) goldenman$filler = new ItemStack(Items.STAINED_GLASS_PANE.black());
            g.fakeItem(goldenman$filler, slot.x, slot.y);
            ci.cancel();
        }
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void goldenman$hideTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        if (hoveredSlot != null && GoldenMan.guard() != null
                && GoldenMan.guard().isHiddenButton((Screen) (Object) this, hoveredSlot.getItem())) {
            ci.cancel();
        }
    }
}
