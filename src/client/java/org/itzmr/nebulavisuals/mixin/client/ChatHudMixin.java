package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Добавляет плавную инерционную прокрутку истории чата ({@code scrolledLines -> smoothScrolledLines})
 * и плавный выезд + fade-in для новых входящих сообщений в {@link ChatHud}.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Unique private static final float CHAT_SCROLL_LERP = 18.0f;
    @Unique private static final float CHAT_MSG_SLIDE_LERP = 18.0f;
    @Unique private static final float CHAT_MSG_FADE_DURATION_SEC = 0.22f;

    @Shadow @Final MinecraftClient client;
    @Shadow private int scrolledLines;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;

    @Shadow public abstract int getVisibleLineCount();
    @Shadow private double getChatScale() { throw new AssertionError(); }
    @Shadow private int getWidth() { throw new AssertionError(); }

    @Unique private DrawContext nebulavisuals$activeContext = null;
    @Unique private ChatHud.Backend nebulavisuals$activeBackend = null;
    @Unique private boolean nebulavisuals$scissored = false;
    @Unique private float nebulavisuals$smoothScrolledLines = 0.0f;
    @Unique private int nebulavisuals$savedScrolledLines = 0;
    @Unique private boolean nebulavisuals$overrodeScrolledLines = false;
    @Unique private float nebulavisuals$slideOffsetPx = 0.0f;
    @Unique private int nebulavisuals$prevVisibleSize = 0;
    @Unique private long nebulavisuals$lastRenderNs = 0L;
    @Unique private final Map<OrderedText, Long> nebulavisuals$newLineTimes = new IdentityHashMap<>();

    @Inject(method = "clear", at = @At("TAIL"))
    private void nebulavisuals$onClearChat(boolean clearHistory, CallbackInfo ci) {
        this.nebulavisuals$smoothScrolledLines = 0.0f;
        this.nebulavisuals$slideOffsetPx = 0.0f;
        this.nebulavisuals$prevVisibleSize = 0;
        this.nebulavisuals$newLineTimes.clear();
    }

    @Inject(method = "addVisibleMessage", at = @At("HEAD"))
    private void nebulavisuals$captureVisibleCountBeforeAdd(ChatHudLine message, CallbackInfo ci) {
        this.nebulavisuals$prevVisibleSize = this.visibleMessages.size();
    }

    @Inject(method = "addVisibleMessage", at = @At("TAIL"))
    private void nebulavisuals$onAddVisibleMessage(ChatHudLine message, CallbackInfo ci) {
        int added = Math.max(0, this.visibleMessages.size() - this.nebulavisuals$prevVisibleSize);
        if (added <= 0 || !AnimationsModule.isGuiAnimationsActive()) {
            return;
        }
        long now = System.nanoTime();
        int markCount = Math.min(added, this.visibleMessages.size());
        for (int i = 0; i < markCount; i++) {
            OrderedText content = this.visibleMessages.get(i).content();
            if (content != null) {
                this.nebulavisuals$newLineTimes.put(content, now);
            }
        }
        if (this.scrolledLines == 0) {
            double lineSpacing = this.client != null && this.client.options != null
                    ? this.client.options.getChatLineSpacing().getValue()
                    : 0.0;
            float lineHeight = (float) (9.0 * (lineSpacing + 1.0));
            this.nebulavisuals$slideOffsetPx = Math.min(lineHeight * 4.0f, this.nebulavisuals$slideOffsetPx + markCount * lineHeight);
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V",
            at = @At("HEAD")
    )
    private void nebulavisuals$captureDrawContext(
            DrawContext context,
            TextRenderer textRenderer,
            int currentTick,
            int mouseX,
            int mouseY,
            boolean interactable,
            boolean showDraft,
            CallbackInfo ci) {
        this.nebulavisuals$activeContext = context;
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V",
            at = @At("RETURN")
    )
    private void nebulavisuals$clearDrawContext(
            DrawContext context,
            TextRenderer textRenderer,
            int currentTick,
            int mouseX,
            int mouseY,
            boolean interactable,
            boolean showDraft,
            CallbackInfo ci) {
        this.nebulavisuals$activeContext = null;
    }

    @ModifyVariable(
            method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private ChatHud.Backend nebulavisuals$wrapBackendForFade(ChatHud.Backend original) {
        if (!AnimationsModule.isGuiAnimationsActive() || this.nebulavisuals$newLineTimes.isEmpty()) {
            return original;
        }
        AnimationsModule animations = ModuleManager.getAnimationsModule();
        float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;
        long now = System.nanoTime();
        float fadeDurationSec = CHAT_MSG_FADE_DURATION_SEC / speed;

        return new ChatHud.Backend() {
            @Override
            public void updatePose(Consumer<Matrix3x2f> transformer) {
                original.updatePose(transformer);
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int color) {
                original.fill(x1, y1, x2, y2, color);
            }

            @Override
            public boolean text(int y, float opacity, OrderedText text) {
                float effectiveOpacity = opacity;
                if (text != null) {
                    Long addedNs = nebulavisuals$newLineTimes.get(text);
                    if (addedNs != null) {
                        float ageSec = (now - addedNs) / 1_000_000_000.0f;
                        float t = MathHelper.clamp(ageSec / fadeDurationSec, 0.0f, 1.0f);
                        effectiveOpacity *= AnimationsModule.easeOutCubic(t);
                    }
                }
                return original.text(y, effectiveOpacity, text);
            }

            @Override
            public void indicator(int x1, int y1, int x2, int y2, float opacity, MessageIndicator indicator) {
                original.indicator(x1, y1, x2, y2, opacity, indicator);
            }

            @Override
            public void indicatorIcon(int x, int y, boolean interactable, MessageIndicator indicator, MessageIndicator.Icon icon) {
                original.indicatorIcon(x, y, interactable, indicator, icon);
            }
        };
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V", at = @At("HEAD"))
    private void nebulavisuals$updateSmoothChatState(ChatHud.Backend drawer, int windowHeight, int currentTick, boolean expanded, CallbackInfo ci) {
        this.nebulavisuals$activeBackend = drawer;
        this.nebulavisuals$overrodeScrolledLines = false;
        this.nebulavisuals$scissored = false;

        if (!AnimationsModule.isGuiAnimationsActive()) {
            this.nebulavisuals$smoothScrolledLines = this.scrolledLines;
            this.nebulavisuals$slideOffsetPx = 0.0f;
            this.nebulavisuals$lastRenderNs = 0L;
            this.nebulavisuals$newLineTimes.clear();
            return;
        }

        long now = System.nanoTime();
        float deltaTime = this.nebulavisuals$lastRenderNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$lastRenderNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$lastRenderNs = now;

        AnimationsModule animations = ModuleManager.getAnimationsModule();
        float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;

        // Очищаем завершившиеся fade-таймеры новых сообщений
        float fadeDurationNs = (CHAT_MSG_FADE_DURATION_SEC / speed) * 1_000_000_000.0f;
        Iterator<Map.Entry<OrderedText, Long>> it = this.nebulavisuals$newLineTimes.entrySet().iterator();
        while (it.hasNext()) {
            if ((now - it.next().getValue()) >= fadeDurationNs) {
                it.remove();
            }
        }

        // Интерполируем smoothScrolledLines -> scrolledLines
        float targetScroll = this.scrolledLines;
        float scrollLerp = deltaTime <= 0.0f ? 0.0f : 1.0f - (float) Math.exp(-deltaTime * CHAT_SCROLL_LERP * speed);
        this.nebulavisuals$smoothScrolledLines += (targetScroll - this.nebulavisuals$smoothScrolledLines) * scrollLerp;
        if (Math.abs(targetScroll - this.nebulavisuals$smoothScrolledLines) < 0.005f) {
            this.nebulavisuals$smoothScrolledLines = targetScroll;
        }

        // Интерполируем выезд новых сообщений снизу вверх (slideOffsetPx -> 0)
        float slideLerp = deltaTime <= 0.0f ? 0.0f : 1.0f - (float) Math.exp(-deltaTime * CHAT_MSG_SLIDE_LERP * speed);
        this.nebulavisuals$slideOffsetPx += (0.0f - this.nebulavisuals$slideOffsetPx) * slideLerp;
        if (Math.abs(this.nebulavisuals$slideOffsetPx) < 0.05f) {
            this.nebulavisuals$slideOffsetPx = 0.0f;
        }

        int flooredScroll = Math.max(0, MathHelper.floor(this.nebulavisuals$smoothScrolledLines));
        if (flooredScroll != this.scrolledLines) {
            this.nebulavisuals$savedScrolledLines = this.scrolledLines;
            this.scrolledLines = flooredScroll;
            this.nebulavisuals$overrodeScrolledLines = true;
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/hud/ChatHud$Backend;updatePose(Ljava/util/function/Consumer;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$applyChatScrollAndSlideOffset(
            ChatHud.Backend drawer, int windowHeight, int currentTick, boolean expanded, CallbackInfo ci) {
        if (!AnimationsModule.isGuiAnimationsActive()) {
            return;
        }

        float chatScale = Math.max(0.1f, (float) this.getChatScale());
        double lineSpacing = this.client != null && this.client.options != null
                ? this.client.options.getChatLineSpacing().getValue()
                : 0.0;
        float lineHeight = (float) (9.0 * (lineSpacing + 1.0));
        float scrollFrac = this.nebulavisuals$smoothScrolledLines - MathHelper.floor(this.nebulavisuals$smoothScrolledLines);
        float totalOffsetY = scrollFrac * lineHeight + (this.scrolledLines == 0 ? this.nebulavisuals$slideOffsetPx : 0.0f);

        if (totalOffsetY > 0.02f) {
            if (this.nebulavisuals$activeContext != null) {
                int yBottom = MathHelper.floor((windowHeight - 40) / chatScale);
                int chatWidth = MathHelper.ceil(this.getWidth() / chatScale);
                int visibleCount = Math.max(1, this.getVisibleLineCount());
                int topY = yBottom - MathHelper.ceil(visibleCount * lineHeight) - 6;
                this.nebulavisuals$activeContext.enableScissor(-6, topY, chatWidth + 14, yBottom + 12);
                this.nebulavisuals$scissored = true;
            }
            drawer.updatePose(matrices -> matrices.translate(0.0f, totalOffsetY));
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V", at = @At("RETURN"))
    private void nebulavisuals$restoreChatState(ChatHud.Backend drawer, int windowHeight, int currentTick, boolean expanded, CallbackInfo ci) {
        if (this.nebulavisuals$overrodeScrolledLines) {
            this.scrolledLines = this.nebulavisuals$savedScrolledLines;
            this.nebulavisuals$overrodeScrolledLines = false;
        }
        if (this.nebulavisuals$scissored && this.nebulavisuals$activeContext != null) {
            this.nebulavisuals$activeContext.disableScissor();
            this.nebulavisuals$scissored = false;
        }
        this.nebulavisuals$activeBackend = null;
    }
}
