package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.PredictionsModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.EggItem;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ExperienceBottleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.LingeringPotionItem;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.SplashPotionItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Locale;

/**
 * 3D- и 2D-рендерер модуля {@link PredictionsModule} (100% легитный расчёт только по блокам мира).
 * Симулирует баллистику строго в ванильном порядке Minecraft (pos -> raycast -> pos += vel -> drag -> gravity)
 * и рисует тонкие экранные {@code GL_LINES}-линии с неизменной пиксельной шириной при зуме,
 * гладкое 48-сегментное кольцо приземления и бейдж времени полёта.
 */
public final class PredictionsRenderer {
    private static final int MAX_STEPS = 160;
    private static final int RING_SEGMENTS = 48;
    private static final double[] PATH_X = new double[MAX_STEPS + 1];
    private static final double[] PATH_Y = new double[MAX_STEPS + 1];
    private static final double[] PATH_Z = new double[MAX_STEPS + 1];
    private static final int[] GLOW_COLORS = new int[MAX_STEPS + 1];
    private static final int[] CORE_COLORS = new int[MAX_STEPS + 1];
    private static final BlockPos.Mutable WATER_CHECK_POS = new BlockPos.Mutable();
    private static final float[] SCREEN_OUT = new float[2];

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private static boolean hasLandingBadge = false;
    private static double badgeWorldX = 0.0;
    private static double badgeWorldY = 0.0;
    private static double badgeWorldZ = 0.0;
    private static float badgeFlightSeconds = 0.0f;

    private PredictionsRenderer() {}

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        hasLandingBadge = false;

        PredictionsModule module = ModuleManager.getPredictionsModule();
        if (module == null || !module.isEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;

        ActiveProjectileSpec spec = resolveActiveProjectile(player);
        if (spec == null) {
            return;
        }

        float tickProgress = Camera3D.getTickProgress();
        float yaw = player.getYaw(tickProgress);
        float pitch = player.getPitch(tickProgress) + spec.pitchOffset;

        double radYaw = yaw * DEG_TO_RAD;
        double radPitch = pitch * DEG_TO_RAD;
        double cosPitch = Math.cos(radPitch);

        double dirX = -Math.sin(radYaw) * cosPitch;
        double dirY = -Math.sin(radPitch);
        double dirZ = Math.cos(radYaw) * cosPitch;

        double vx = dirX * spec.speed;
        double vy = dirY * spec.speed;
        double vz = dirZ * spec.speed;

        // Ванильный расчёт инерции броска: X/Z всегда + Y только если игрок в воздухе
        Vec3d playerVel = player.getVelocity();
        vx += playerVel.x;
        vz += playerVel.z;
        if (!player.isOnGround()) {
            vy += playerVel.y;
        }

        // Точная ванильная точка спавна снаряда (eyePos - 0.1 по Y)
        Vec3d basePos = player.getLerpedPos(tickProgress);
        double simX = basePos.x;
        double simY = basePos.y + player.getEyeHeight(player.getPose()) - 0.1;
        double simZ = basePos.z;

        // Визуальное смещение только первых точек от руки, симуляция идёт строго из simX/Y/Z
        boolean rightHand = (spec.hand == Hand.MAIN_HAND) == (player.getMainArm() == Arm.RIGHT);
        double sideSign = rightHand ? 1.0 : -1.0;
        double handOffX = -Math.cos(radYaw) * 0.14 * sideSign;
        double handOffZ = -Math.sin(radYaw) * 0.14 * sideSign;

        PATH_X[0] = simX + handOffX;
        PATH_Y[0] = simY - 0.04;
        PATH_Z[0] = simZ + handOffZ;
        int pointCount = 1;

        boolean landed = false;
        Direction hitSide = Direction.UP;
        double hitX = simX;
        double hitY = simY;
        double hitZ = simZ;
        float flightTicks = 0.0f;

        for (int step = 0; step < MAX_STEPS; step++) {
            Vec3d start = new Vec3d(simX, simY, simZ);
            Vec3d next = new Vec3d(simX + vx, simY + vy, simZ + vz);

            // 1. Сначала проверяем коллизии исключительно с блоками мира от pos до pos + vel
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    start, next,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));

            if (blockHit.getType() == HitResult.Type.BLOCK) {
                Vec3d bPos = blockHit.getPos();
                double stepLen = Math.max(1e-6, start.distanceTo(next));
                double frac = Math.clamp(start.distanceTo(bPos) / stepLen, 0.0, 1.0);
                flightTicks = step + (float) frac;

                PATH_X[pointCount] = bPos.x;
                PATH_Y[pointCount] = bPos.y;
                PATH_Z[pointCount] = bPos.z;
                pointCount++;

                hitSide = blockHit.getSide();
                hitX = bPos.x;
                hitY = bPos.y;
                hitZ = bPos.z;
                landed = true;
                break;
            }

            // 2. Сдвигаем позицию pos += vel
            simX = next.x;
            simY = next.y;
            simZ = next.z;

            double blend = pointCount <= 3 ? (1.0 - pointCount / 4.0) : 0.0;
            PATH_X[pointCount] = simX + handOffX * blend;
            PATH_Y[pointCount] = simY - 0.04 * blend;
            PATH_Z[pointCount] = simZ + handOffZ * blend;
            pointCount++;
            flightTicks = step + 1.0f;

            // 3. ТОЛЬКО ПОСЛЕ сдвига применяем сопротивление среды и гравитацию
            WATER_CHECK_POS.set(MathHelper.floor(simX), MathHelper.floor(simY), MathHelper.floor(simZ));
            FluidState fluid = world.getFluidState(WATER_CHECK_POS);
            double drag = fluid.isIn(FluidTags.WATER) ? spec.waterDrag : spec.airDrag;

            vx *= drag;
            vy *= drag;
            vz *= drag;
            vy -= spec.gravity;

            if (simY < world.getBottomY() - 16) {
                break;
            }
        }

        if (pointCount < 2) {
            return;
        }

        int accent = ClientSettings.getAccentColor();
        int brightTip = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.45f);
        float lineWidth = module.lineWidth.getValue().floatValue();

        int screenW = client.getWindow() != null ? Math.max(1, client.getWindow().getFramebufferWidth()) : 1920;
        int screenH = client.getWindow() != null ? Math.max(1, client.getWindow().getFramebufferHeight()) : 1080;
        float dpiScale = Math.max(1.0f, screenH / 1080.0f);
        float pixelWidth = lineWidth * dpiScale;

        // Предвычисляем градиенты без аллокаций: 0.35 у руки -> 0.95 у цели + мягкий glow-слой 0.22
        int maxIdx = pointCount - 1;
        for (int i = 0; i < pointCount; i++) {
            float t = (float) i / maxIdx;
            float coreAlpha = 0.35f + 0.60f * t;
            float glowAlpha = 0.14f + 0.08f * t;
            int col = ColorUtil.lerp(accent, brightTip, t * 0.70f);
            GLOW_COLORS[i] = ColorUtil.withAlpha(accent, glowAlpha);
            CORE_COLORS[i] = ColorUtil.withAlpha(col, coreAlpha);
        }

        // 1. Внешнее мягкое glow-свечение (Line Width * 2.2f, alpha ~0.22f)
        batch3D.drawScreenPolyline(PATH_X, PATH_Y, PATH_Z, GLOW_COLORS, pointCount, pixelWidth * 2.2f, screenW, screenH, BlendMode.ALPHA);
        // 2. Чёткая экранно-ориентированная лента траектории (постоянная пиксельная ширина при любом зуме)
        batch3D.drawScreenPolyline(PATH_X, PATH_Y, PATH_Z, CORE_COLORS, pointCount, pixelWidth, screenW, screenH, BlendMode.ALPHA);

        if (landed && module.showLandingRing.getValue()) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() * 1e-9 * 6.0);
            renderLandingMarker(spheres, batch3D, hitX, hitY, hitZ, hitSide, accent, brightTip, lineWidth, pulse);

            hasLandingBadge = true;
            badgeWorldX = hitX + hitSide.getOffsetX() * 0.18;
            badgeWorldY = hitY + hitSide.getOffsetY() * 0.22 + 0.24;
            badgeWorldZ = hitZ + hitSide.getOffsetZ() * 0.18;
            badgeFlightSeconds = flightTicks / 20.0f;
        }
    }

    /**
     * Рисует компактный плавающий бейдж времени полёта ({@code 1.2s}) поверх 2D-HUD в точке падения.
     */
    public static void renderFlightBadgeOverlay(MinecraftClient client) {
        if (!hasLandingBadge || client == null || client.getWindow() == null || client.options.hudHidden || client.currentScreen != null) {
            return;
        }
        PredictionsModule module = ModuleManager.getPredictionsModule();
        if (module == null || !module.isEnabled() || !module.showLandingRing.getValue()) {
            return;
        }
        if (!NebulaRenderEngine.isInitialized() || !FontRenderer.hasDefaultFont()) {
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        if (!Camera3D.worldToScreen(badgeWorldX, badgeWorldY, badgeWorldZ, canvasW, canvasH, SCREEN_OUT)) {
            return;
        }
        float sx = SCREEN_OUT[0];
        float sy = SCREEN_OUT[1];
        if (sx < -40.0f || sx > canvasW + 40.0f || sy < -40.0f || sy > canvasH + 40.0f) {
            return;
        }

        String label = String.format(Locale.ROOT, "%.1fs", Math.max(0.05f, badgeFlightSeconds));
        float fontSize = 10.5f;
        float textW = FontRenderer.getWidth(label, fontSize);
        float textH = FontRenderer.getHeight(fontSize);
        float pillW = textW + 14.0f;
        float pillH = textH + 8.0f;
        float px = sx - pillW * 0.5f;
        float py = sy - pillH * 0.5f;

        int accent = ClientSettings.getAccentColor();
        int bgColor = ColorUtil.argb(205, 13, 15, 20);
        int borderColor = ColorUtil.withAlpha(accent, 0.85f);

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        batch.begin(canvasW, canvasH, uiScale);
        batch.drawShadow(px, py, pillW, pillH, 6.0f, 6.0f, 0x60000000);
        batch.drawRoundedRectWithOutline(px, py, pillW, pillH, 6.0f, bgColor, 1.0f, borderColor);
        FontRenderer.drawCenteredText(batch, label, sx, py + (pillH - textH) * 0.5f, fontSize, 0xFFF8FAFC);
        batch.end();
    }

    private static void renderLandingMarker(SphereBatch3D spheres, RenderBatch3D batch3D,
                                            double hitX, double hitY, double hitZ,
                                            Direction side, int accent, int brightTip, float lineWidth, float pulse) {
        double nx = side.getOffsetX();
        double ny = side.getOffsetY();
        double nz = side.getOffsetZ();

        double cx = hitX + nx * 0.02;
        double cy = hitY + ny * 0.02;
        double cz = hitZ + nz * 0.02;

        float outerR = 0.40f + 0.025f * pulse;
        float innerR = 0.22f;
        int outerRingCol = ColorUtil.withAlpha(brightTip, 0.96f);
        int innerRingCol = ColorUtil.withAlpha(accent, 0.72f);

        if (side == Direction.UP || side == Direction.DOWN) {
            batch3D.drawHaloRing(
                    cx, cy, cz,
                    outerR, 0.08f,
                    ColorUtil.withAlpha(accent, 0.36f),
                    ColorUtil.withAlpha(accent, 0.0f),
                    RING_SEGMENTS, 0.0f, BlendMode.ALPHA
            );
            batch3D.drawNativeCircleOutline(cx, cy + ny * 0.002, cz, outerR, outerRingCol, RING_SEGMENTS, lineWidth, BlendMode.ALPHA);
            batch3D.drawNativeCircleOutline(cx, cy + ny * 0.003, cz, innerR, innerRingCol, RING_SEGMENTS, Math.max(1.0f, lineWidth * 0.75f), BlendMode.ALPHA);
        } else {
            double ux = (side == Direction.NORTH || side == Direction.SOUTH) ? 1.0 : 0.0;
            double uz = (side == Direction.EAST || side == Direction.WEST) ? 1.0 : 0.0;
            batch3D.drawNativeOrientedCircleOutline(cx, cy, cz, ux, 0.0, uz, 0.0, 1.0, 0.0, outerR, outerRingCol, RING_SEGMENTS, lineWidth, BlendMode.ALPHA);
            batch3D.drawNativeOrientedCircleOutline(cx, cy, cz, ux, 0.0, uz, 0.0, 1.0, 0.0, innerR, innerRingCol, RING_SEGMENTS, Math.max(1.0f, lineWidth * 0.75f), BlendMode.ALPHA);
        }

        // Компактная пульсирующая центральная точка
        spheres.drawSphere(cx, cy, cz, 0.045f + 0.015f * pulse, outerRingCol, 1.6f, BlendMode.ADDITIVE);
    }

    private static ActiveProjectileSpec resolveActiveProjectile(ClientPlayerEntity player) {
        ActiveProjectileSpec main = checkStack(player, player.getMainHandStack(), Hand.MAIN_HAND);
        if (main != null) {
            return main;
        }
        return checkStack(player, player.getOffHandStack(), Hand.OFF_HAND);
    }

    private static ActiveProjectileSpec checkStack(ClientPlayerEntity player, ItemStack stack, Hand hand) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();

        if (item instanceof BowItem) {
            if (!player.isUsingItem() || player.getActiveItem() != stack) {
                return null;
            }
            int usedTicks = stack.getMaxUseTime(player) - player.getItemUseTimeLeft();
            float pull = BowItem.getPullProgress(usedTicks);
            if (pull < 0.1f) {
                return null;
            }
            return new ActiveProjectileSpec(hand, pull * 3.0f, 0.05f, 0.99f, 0.60f, 0.0f);
        }

        if (item instanceof CrossbowItem) {
            if (!CrossbowItem.isCharged(stack)) {
                return null;
            }
            ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged == null || charged.isEmpty()) {
                return null;
            }
            if (charged.contains(Items.FIREWORK_ROCKET)) {
                return new ActiveProjectileSpec(hand, 1.6f, 0.0f, 1.0f, 1.0f, 0.0f);
            }
            return new ActiveProjectileSpec(hand, 3.15f, 0.05f, 0.99f, 0.60f, 0.0f);
        }

        if (item instanceof TridentItem) {
            if (!player.isUsingItem() || player.getActiveItem() != stack) {
                return null;
            }
            int usedTicks = stack.getMaxUseTime(player) - player.getItemUseTimeLeft();
            if (usedTicks < TridentItem.MIN_DRAW_DURATION) {
                return null;
            }
            return new ActiveProjectileSpec(hand, TridentItem.THROW_SPEED, 0.05f, 0.99f, 0.99f, 0.0f);
        }

        if (item instanceof EnderPearlItem || item instanceof SnowballItem || item instanceof EggItem) {
            return new ActiveProjectileSpec(hand, 1.5f, 0.03f, 0.99f, 0.80f, 0.0f);
        }

        if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem) {
            return new ActiveProjectileSpec(hand, 0.5f, 0.05f, 0.99f, 0.80f, -20.0f);
        }

        if (item instanceof ExperienceBottleItem) {
            return new ActiveProjectileSpec(hand, 0.7f, 0.07f, 0.99f, 0.80f, -20.0f);
        }

        return null;
    }

    private record ActiveProjectileSpec(
            Hand hand,
            float speed,
            float gravity,
            float airDrag,
            float waterDrag,
            float pitchOffset
    ) {}
}
