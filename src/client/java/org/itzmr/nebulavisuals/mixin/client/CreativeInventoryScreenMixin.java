package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.render.engine2d.CreativeScrollAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Реализует настоящий {@code Smooth Scrolling} сетки креатива:
 * пустые клетки {@code 18x18} и слоты с предметами плавно прокручиваются единым полотном
 * с обрезкой {@code enableScissor} и мягким {@code Fade} при переключении вкладок.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin implements CreativeScrollAccess {
    @Unique private static final int CREATIVE_GRID_SLOTS = 45;
    @Unique private static final int SLOT_HEIGHT_PX = 18;
    @Unique private static final int GRID_X_OFFSET = 8;
    @Unique private static final int GRID_Y_OFFSET = 17;
    @Unique private static final int GRID_WIDTH = 162;
    @Unique private static final int GRID_HEIGHT = 90;
    @Unique private static final float SCROLL_LERP_RATE = 15.0f;
    @Unique private static final float TAB_FADE_SPEED = 7.0f;

    @Shadow @Final static SimpleInventory INVENTORY;
    @Shadow private static ItemGroup selectedTab;
    @Shadow private float scrollPosition;
    @Shadow private boolean scrolling;

    @Shadow protected abstract boolean hasScrollbar();
    @Shadow public abstract boolean isInventoryTabSelected();

    @Unique private float nebulavisuals$targetRowScroll = 0.0f;
    @Unique private float nebulavisuals$smoothRowScroll = 0.0f;
    @Unique private int nebulavisuals$baseRow = 0;
    @Unique private float nebulavisuals$pixelOffsetY = 0.0f;
    @Unique private float nebulavisuals$tabFadeProgress = 1.0f;
    @Unique private long nebulavisuals$lastRenderNs = 0L;

    @Unique
    private int nebulavisuals$getOverflowRows() {
        CreativeInventoryScreen self = (CreativeInventoryScreen) (Object) this;
        return Math.max(0, MathHelper.ceilDiv(self.getScreenHandler().itemList.size(), 9) - 5);
    }

    @Override
    public boolean nebulavisuals$isCreativeScrollTransformActive() {
        return AnimationsModule.isCreativeScrollActive()
                && !this.isInventoryTabSelected()
                && this.hasScrollbar()
                && Math.abs(this.nebulavisuals$pixelOffsetY) > 0.01f;
    }

    @Override
    public boolean nebulavisuals$isCreativeGridSlot(Slot slot) {
        if (this.isInventoryTabSelected() || slot == null) {
            return false;
        }
        return slot.inventory == INVENTORY && slot.getIndex() < CREATIVE_GRID_SLOTS;
    }

    @Override
    public float nebulavisuals$getSlotYOffset() {
        return this.nebulavisuals$pixelOffsetY;
    }

    @Override
    public boolean nebulavisuals$shouldFadeSlot(Slot slot) {
        if (!AnimationsModule.isCreativeScrollActive() || this.isInventoryTabSelected() || slot == null) {
            return false;
        }
        return slot.inventory == INVENTORY
                && slot.getIndex() < CREATIVE_GRID_SLOTS
                && this.nebulavisuals$tabFadeProgress < 1.0f;
    }

    @Override
    public float nebulavisuals$getTabFadeAlpha() {
        return AnimationsModule.easeOutCubic(this.nebulavisuals$tabFadeProgress);
    }

    @Override
    public void nebulavisuals$renderExtraBottomRow(DrawContext context) {
        if (!this.nebulavisuals$isCreativeScrollTransformActive()) {
            return;
        }
        CreativeInventoryScreen self = (CreativeInventoryScreen) (Object) this;
        CreativeInventoryScreen.CreativeScreenHandler handler = self.getScreenHandler();
        int rowIndex = this.nebulavisuals$baseRow + 5;
        int listSize = handler.itemList.size();
        int firstItem = rowIndex * 9;
        if (firstItem >= listSize) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        context.enableScissor(GRID_X_OFFSET, GRID_Y_OFFSET, GRID_X_OFFSET + GRID_WIDTH, GRID_Y_OFFSET + GRID_HEIGHT);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(0.0f, this.nebulavisuals$pixelOffsetY);

        int slotY = 18 + 5 * SLOT_HEIGHT_PX;
        for (int col = 0; col < 9; col++) {
            int idx = firstItem + col;
            if (idx < 0 || idx >= listSize) {
                break;
            }
            ItemStack stack = handler.itemList.get(idx);
            if (stack.isEmpty()) {
                continue;
            }
            int slotX = 9 + col * SLOT_HEIGHT_PX;
            context.drawItem(stack, slotX, slotY);
            if (client != null && client.textRenderer != null) {
                context.drawStackOverlay(client.textRenderer, stack, slotX, slotY);
            }
        }

        if (this.nebulavisuals$tabFadeProgress < 1.0f) {
            float fade = 1.0f - MathHelper.clamp(this.nebulavisuals$getTabFadeAlpha(), 0.0f, 1.0f);
            int alpha = MathHelper.clamp(Math.round(fade * 220.0f), 0, 220);
            if (alpha > 2) {
                for (int col = 0; col < 9; col++) {
                    int idx = firstItem + col;
                    if (idx >= listSize) {
                        break;
                    }
                    int slotX = 9 + col * SLOT_HEIGHT_PX;
                    context.fill(slotX, slotY, slotX + 16, slotY + 16, (alpha << 24) | 0x8B8B8B);
                }
            }
        }

        context.getMatrices().popMatrix();
        context.disableScissor();
    }

    @Inject(method = "setSelectedTab", at = @At("TAIL"))
    private void nebulavisuals$onTabSwitch(ItemGroup group, CallbackInfo ci) {
        int overflowRows = this.nebulavisuals$getOverflowRows();
        float row = overflowRows > 0 ? MathHelper.clamp(this.scrollPosition * overflowRows, 0.0f, overflowRows) : 0.0f;
        this.nebulavisuals$targetRowScroll = row;
        this.nebulavisuals$smoothRowScroll = row;
        this.nebulavisuals$baseRow = Math.max(0, MathHelper.floor(row));
        this.nebulavisuals$pixelOffsetY = 0.0f;
        this.nebulavisuals$tabFadeProgress = AnimationsModule.isCreativeScrollActive() ? 0.0f : 1.0f;
        this.nebulavisuals$lastRenderNs = System.nanoTime();
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"))
    private void nebulavisuals$prepareTargetScroll(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount,
            CallbackInfoReturnable<Boolean> cir) {
        if (AnimationsModule.isCreativeScrollActive() && !this.isInventoryTabSelected() && this.hasScrollbar()) {
            int overflowRows = this.nebulavisuals$getOverflowRows();
            this.scrollPosition = overflowRows > 0
                    ? MathHelper.clamp(this.nebulavisuals$targetRowScroll / overflowRows, 0.0f, 1.0f)
                    : 0.0f;
        }
    }

    @Inject(method = "mouseScrolled", at = @At("RETURN"))
    private void nebulavisuals$captureSmoothWheelScroll(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !this.hasScrollbar() || this.isInventoryTabSelected()) {
            return;
        }
        int overflowRows = this.nebulavisuals$getOverflowRows();
        if (!AnimationsModule.isCreativeScrollActive() || overflowRows <= 0) {
            float row = overflowRows > 0 ? this.scrollPosition * overflowRows : 0.0f;
            this.nebulavisuals$targetRowScroll = row;
            this.nebulavisuals$smoothRowScroll = row;
            this.nebulavisuals$baseRow = Math.max(0, MathHelper.floor(row));
            this.nebulavisuals$pixelOffsetY = 0.0f;
            return;
        }

        this.nebulavisuals$targetRowScroll = MathHelper.clamp(this.scrollPosition * overflowRows, 0.0f, overflowRows);
        this.nebulavisuals$baseRow = MathHelper.clamp(MathHelper.floor(this.nebulavisuals$smoothRowScroll), 0, overflowRows);
        this.nebulavisuals$pixelOffsetY = -(this.nebulavisuals$smoothRowScroll - this.nebulavisuals$baseRow) * SLOT_HEIGHT_PX;
        this.scrollPosition = MathHelper.clamp(this.nebulavisuals$smoothRowScroll / overflowRows, 0.0f, 1.0f);

        CreativeInventoryScreen self = (CreativeInventoryScreen) (Object) this;
        self.getScreenHandler().scrollItems(MathHelper.clamp((this.nebulavisuals$baseRow + 0.05f) / overflowRows, 0.0f, 1.0f));
    }

    @Inject(method = "mouseDragged", at = @At("RETURN"))
    private void nebulavisuals$syncScrollbarDrag(
            Click click,
            double offsetX,
            double offsetY,
            CallbackInfoReturnable<Boolean> cir) {
        if (this.scrolling) {
            int overflowRows = this.nebulavisuals$getOverflowRows();
            float row = overflowRows > 0 ? MathHelper.clamp(this.scrollPosition * overflowRows, 0.0f, overflowRows) : 0.0f;
            this.nebulavisuals$targetRowScroll = row;
            this.nebulavisuals$smoothRowScroll = row;
            this.nebulavisuals$baseRow = Math.max(0, MathHelper.floor(row));
            this.nebulavisuals$pixelOffsetY = -(row - this.nebulavisuals$baseRow) * SLOT_HEIGHT_PX;
            if (overflowRows > 0 && AnimationsModule.isCreativeScrollActive() && Math.abs(this.nebulavisuals$pixelOffsetY) > 0.01f) {
                CreativeInventoryScreen self = (CreativeInventoryScreen) (Object) this;
                self.getScreenHandler().scrollItems(MathHelper.clamp((this.nebulavisuals$baseRow + 0.05f) / overflowRows, 0.0f, 1.0f));
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void nebulavisuals$updateSmoothCreativeScroll(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        int overflowRows = this.nebulavisuals$getOverflowRows();
        if (!AnimationsModule.isCreativeScrollActive() || this.isInventoryTabSelected() || !this.hasScrollbar() || overflowRows <= 0) {
            float row = overflowRows > 0 ? MathHelper.clamp(this.scrollPosition * overflowRows, 0.0f, overflowRows) : 0.0f;
            this.nebulavisuals$targetRowScroll = row;
            this.nebulavisuals$smoothRowScroll = row;
            this.nebulavisuals$baseRow = Math.max(0, MathHelper.floor(row));
            this.nebulavisuals$pixelOffsetY = 0.0f;
            this.nebulavisuals$tabFadeProgress = 1.0f;
            this.nebulavisuals$lastRenderNs = 0L;
            return;
        }

        long now = System.nanoTime();
        float deltaTime = this.nebulavisuals$lastRenderNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$lastRenderNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$lastRenderNs = now;

        AnimationsModule animations = ModuleManager.getAnimationsModule();
        float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;
        float lerp = deltaTime <= 0.0f ? 0.0f : 1.0f - (float) Math.exp(-deltaTime * SCROLL_LERP_RATE * speed);

        this.nebulavisuals$smoothRowScroll += (this.nebulavisuals$targetRowScroll - this.nebulavisuals$smoothRowScroll) * lerp;
        if (Math.abs(this.nebulavisuals$targetRowScroll - this.nebulavisuals$smoothRowScroll) < 0.002f) {
            this.nebulavisuals$smoothRowScroll = this.nebulavisuals$targetRowScroll;
        }

        if (this.nebulavisuals$tabFadeProgress < 1.0f) {
            this.nebulavisuals$tabFadeProgress = Math.min(1.0f, this.nebulavisuals$tabFadeProgress + deltaTime * TAB_FADE_SPEED * speed);
        }

        this.nebulavisuals$baseRow = MathHelper.clamp(MathHelper.floor(this.nebulavisuals$smoothRowScroll), 0, overflowRows);
        this.nebulavisuals$pixelOffsetY = -(this.nebulavisuals$smoothRowScroll - this.nebulavisuals$baseRow) * SLOT_HEIGHT_PX;
        if (Math.abs(this.nebulavisuals$pixelOffsetY) < 0.05f) {
            this.nebulavisuals$pixelOffsetY = 0.0f;
        }

        if (!this.scrolling) {
            this.scrollPosition = MathHelper.clamp(this.nebulavisuals$smoothRowScroll / overflowRows, 0.0f, 1.0f);
            CreativeInventoryScreen self = (CreativeInventoryScreen) (Object) this;
            float basePos = Math.abs(this.nebulavisuals$pixelOffsetY) > 0.01f
                    ? MathHelper.clamp((this.nebulavisuals$baseRow + 0.05f) / overflowRows, 0.0f, 1.0f)
                    : this.scrollPosition;
            self.getScreenHandler().scrollItems(basePos);
        }
    }

    @Inject(
            method = "drawBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIII)V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$renderScrollingGridCells(DrawContext context, float deltaTicks, int mouseX, int mouseY, CallbackInfo ci) {
        if (!this.nebulavisuals$isCreativeScrollTransformActive() || selectedTab == null) {
            return;
        }
        HandledScreenAccessor accessor = (HandledScreenAccessor) this;
        int gridX = accessor.nebulavisuals$getX() + GRID_X_OFFSET;
        int gridY = accessor.nebulavisuals$getY() + GRID_Y_OFFSET;

        Identifier texture = selectedTab.getTexture();
        context.enableScissor(gridX, gridY, gridX + GRID_WIDTH, gridY + GRID_HEIGHT);
        context.fill(gridX, gridY, gridX + GRID_WIDTH, gridY + GRID_HEIGHT, 0xFFC6C6C6);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(0.0f, this.nebulavisuals$pixelOffsetY);

        for (int row = 0; row < 6; row++) {
            context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    texture,
                    gridX, gridY + row * SLOT_HEIGHT_PX,
                    (float) GRID_X_OFFSET, (float) GRID_Y_OFFSET,
                    GRID_WIDTH, SLOT_HEIGHT_PX,
                    256, 256
            );
        }

        context.getMatrices().popMatrix();
        context.disableScissor();
    }
}
