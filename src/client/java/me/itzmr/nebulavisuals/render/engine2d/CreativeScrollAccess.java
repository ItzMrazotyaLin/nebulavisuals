package me.itzmr.nebulavisuals.render.engine2d;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.screen.slot.Slot;

/**
 * Доступ к состоянию настоящего Smooth Scrolling (клетки + предметы) и fade-перехода вкладок {@code CreativeInventoryScreen}.
 */
public interface CreativeScrollAccess {
    boolean nebulavisuals$isCreativeScrollTransformActive();

    boolean nebulavisuals$isCreativeGridSlot(Slot slot);

    float nebulavisuals$getSlotYOffset();

    boolean nebulavisuals$shouldFadeSlot(Slot slot);

    float nebulavisuals$getTabFadeAlpha();

    void nebulavisuals$renderExtraBottomRow(DrawContext context);
}
