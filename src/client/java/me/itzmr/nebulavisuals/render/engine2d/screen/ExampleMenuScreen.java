package me.itzmr.nebulavisuals.render.engine2d.screen;

import me.itzmr.nebulavisuals.render.engine2d.animation.Easing;
import me.itzmr.nebulavisuals.render.engine2d.animation.SpringAnimation;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.text.Text;

/**
 * Пример экрана (Главное меню / ClickGUI), демонстрирующий работу
 * всех компонентов движка: SDF rounded box, тени, градиенты, MSDF текст,
 * пружинную физику (SpringAnimation) и KawaseBlur.
 */
public class ExampleMenuScreen extends NebulaScreen {
    // Пружинные анимации для интерактивных кнопок
    private final SpringAnimation buttonScale1 = new SpringAnimation(1.0f, 220.0f, 18.0f);
    private final SpringAnimation buttonScale2 = new SpringAnimation(1.0f, 220.0f, 18.0f);
    private final SpringAnimation cardSlide = new SpringAnimation(0.0f, 180.0f, 20.0f);

    public ExampleMenuScreen() {
        super(Text.literal("Nebula Visuals Dashboard"));
        this.enableBackgroundBlur = true;
        this.blurIterations = 4;
        this.blurOffset = 2.5f;
    }

    @Override
    protected void onInit() {
        cardSlide.setValue(0.0f);
        cardSlide.setTarget(1.0f);
    }

    @Override
    protected void onTickAnimations(float dt) {
        buttonScale1.update(dt);
        buttonScale2.update(dt);
        cardSlide.update(dt);
    }

    @Override
    protected void onRender(RenderBatch2D batch, float mouseX, float mouseY, float dt) {
        float cx = virtualWidth * 0.5f;
        float cy = virtualHeight * 0.5f;

        float slideProgress = cardSlide.getValue();
        float cardW = 600.0f;
        float cardH = 400.0f;
        float cardX = cx - cardW * 0.5f;
        float cardY = cy - cardH * 0.5f + (1.0f - slideProgress) * 60.0f;

        // 1. Мягкая объемная тень под карточкой
        batch.drawShadow(cardX, cardY, cardW, cardH, 24.0f, 32.0f, ColorUtil.argb((int)(120 * slideProgress), 0, 0, 0));

        // 2. Основная карточка с фоновым темным стеклом и градиентной обводкой
        int bgFill = ColorUtil.argb((int)(200 * slideProgress), 18, 18, 26);
        int strokeColor = ColorUtil.argb((int)(180 * slideProgress), 90, 80, 220);
        batch.drawRoundedRectWithOutline(cardX, cardY, cardW, cardH, 24.0f, bgFill, 1.5f, strokeColor);

        // 3. Верхняя шапка карточки с акцентным градиентом
        int cTL = ColorUtil.argb((int)(255 * slideProgress), 110, 80, 240);
        int cTR = ColorUtil.argb((int)(255 * slideProgress), 210, 60, 160);
        int cBR = ColorUtil.argb((int)(180 * slideProgress), 140, 50, 190);
        int cBL = ColorUtil.argb((int)(180 * slideProgress), 70, 60, 200);
        batch.drawGradientRoundedRect(cardX + 20, cardY + 20, cardW - 40, 70, 16.0f, cTL, cTR, cBR, cBL);

        // 4. Заголовок
        FontRenderer.drawText(batch, "NEBULA VISUALS", cardX + 45, cardY + 42, 28.0f, 0xFFFFFFFF);

        // 5. Интерактивная кнопка 1: "Launch ClickGUI"
        float btn1W = 250.0f;
        float btn1H = 50.0f;
        float btn1X = cardX + 35.0f;
        float btn1Y = cardY + 280.0f;
        boolean hovered1 = mouseX >= btn1X && mouseX <= btn1X + btn1W && mouseY >= btn1Y && mouseY <= btn1Y + btn1H;
        buttonScale1.setTarget(hovered1 ? 1.05f : 1.0f);

        float s1 = buttonScale1.getValue();
        float rw1 = btn1W * s1;
        float rh1 = btn1H * s1;
        float rx1 = btn1X + (btn1W - rw1) * 0.5f;
        float ry1 = btn1Y + (btn1H - rh1) * 0.5f;

        // Тень кнопки + подсветка при наведении
        batch.drawShadow(rx1, ry1, rw1, rh1, 14.0f, hovered1 ? 18.0f : 8.0f, hovered1 ? 0x99A855F7 : 0x44000000);
        int btn1Color = hovered1 ? 0xFF9333EA : 0xFF7E22CE;
        batch.drawRoundedRect(rx1, ry1, rw1, rh1, 14.0f, btn1Color);
        FontRenderer.drawCenteredText(batch, "Open Modules", rx1 + rw1 * 0.5f, ry1 + 16.0f, 18.0f, 0xFFFFFFFF);

        // 6. Интерактивная кнопка 2: "Close Screen"
        float btn2W = 250.0f;
        float btn2H = 50.0f;
        float btn2X = cardX + cardW - btn2W - 35.0f;
        float btn2Y = cardY + 280.0f;
        boolean hovered2 = mouseX >= btn2X && mouseX <= btn2X + btn2W && mouseY >= btn2Y && mouseY <= btn2Y + btn2H;
        buttonScale2.setTarget(hovered2 ? 1.05f : 1.0f);

        float s2 = buttonScale2.getValue();
        float rw2 = btn2W * s2;
        float rh2 = btn2H * s2;
        float rx2 = btn2X + (btn2W - rw2) * 0.5f;
        float ry2 = btn2Y + (btn2H - rh2) * 0.5f;

        batch.drawShadow(rx2, ry2, rw2, rh2, 14.0f, 8.0f, 0x33000000);
        batch.drawRoundedRect(rx2, ry2, rw2, rh2, 14.0f, hovered2 ? 0xFF374151 : 0xFF1F2937);
        FontRenderer.drawCenteredText(batch, "Close Menu", rx2 + rw2 * 0.5f, ry2 + 16.0f, 18.0f, 0xFFE5E7EB);
    }

    @Override
    protected boolean onMouseClicked(float mouseX, float mouseY, int button) {
        if (button == 0) { // ЛКМ
            float cx = virtualWidth * 0.5f;
            float cy = virtualHeight * 0.5f;
            float cardW = 600.0f;
            float cardH = 400.0f;
            float cardX = cx - cardW * 0.5f;
            float cardY = cy - cardH * 0.5f;

            float btn2W = 250.0f;
            float btn2H = 50.0f;
            float btn2X = cardX + cardW - btn2W - 35.0f;
            float btn2Y = cardY + 280.0f;

            if (mouseX >= btn2X && mouseX <= btn2X + btn2W && mouseY >= btn2Y && mouseY <= btn2Y + btn2H) {
                close();
                return true;
            }
        }
        return false;
    }
}
