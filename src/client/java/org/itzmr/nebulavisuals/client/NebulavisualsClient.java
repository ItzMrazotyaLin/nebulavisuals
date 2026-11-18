package org.itzmr.nebulavisuals.client;

import me.itzmr.nebulavisuals.alt.AltManager;
import me.itzmr.nebulavisuals.config.ConfigManager;
import me.itzmr.nebulavisuals.config.LocalizationManager;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.BlockCullingModule;
import me.itzmr.nebulavisuals.module.optimization.CullingHelper;
import me.itzmr.nebulavisuals.module.optimization.EntityCullingModule;
import me.itzmr.nebulavisuals.module.optimization.MemoryCleanerModule;
import me.itzmr.nebulavisuals.module.utilities.AutoSprintModule;
import me.itzmr.nebulavisuals.module.utilities.FreelookModule;
import me.itzmr.nebulavisuals.module.utilities.ZoomModule;
import me.itzmr.nebulavisuals.render.engine2d.screen.AltManagerScreen;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.engine3d.NebulaRender3DEngine;
import me.itzmr.nebulavisuals.render.hud.NotificationsRenderer;
import me.itzmr.nebulavisuals.render.hud.TargetHudRenderer;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import me.itzmr.nebulavisuals.render.visuals.BlockOverlayRenderer;
import me.itzmr.nebulavisuals.render.visuals.ChinaHatRenderer;
import me.itzmr.nebulavisuals.render.visuals.HitParticlesRenderer;
import me.itzmr.nebulavisuals.render.visuals.HitboxesRenderer;
import me.itzmr.nebulavisuals.render.visuals.JumpCirclesRenderer;
import me.itzmr.nebulavisuals.render.visuals.KillEffectsRenderer;
import me.itzmr.nebulavisuals.render.visuals.PredictionsRenderer;
import me.itzmr.nebulavisuals.render.visuals.TargetEspRenderer;
import me.itzmr.nebulavisuals.render.visuals.TrailsRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class NebulavisualsClient implements ClientModInitializer {
    public static KeyBinding clickGuiKey;

    @Override
    public void onInitializeClient() {
        // Регистрация клавиши открытия ClickGUI на Right Shift
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("nebulavisuals", "general"));
        clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nebulavisuals.clickgui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category
        ));

        // Инициализация локализации, реестра модулей, конфигов и менеджера аккаунтов (без вызова OpenGL)
        LocalizationManager.init();
        ModuleManager.init();
        ConfigManager.init();
        AltManager.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ConfigManager.shutdown());

        // Трекинг атакуемых игроков для легитной видимости, TargetHUD и спавн 3D-частиц HitParticles
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world != null && world.isClient() && entity != null) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc == null || player == mc.player) {
                    EntityVisibilityUtil.recordPlayerAttack(entity);
                }
                TargetHudRenderer.onPlayerAttack(player, entity);
                HitParticlesRenderer.onPlayerAttack(player, entity);
            }
            return ActionResult.PASS;
        });

        // Кнопка Alt Manager в левом верхнем углу TitleScreen и GameMenuScreen
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                AltManager.tryRestoreLastSessionOnBoot();
            }
            if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) {
                Text label = Text.literal("Alts: " + AltManager.getCurrentUsername());
                int textW = client.textRenderer != null ? client.textRenderer.getWidth(label) : 84;
                int btnW = Math.clamp(textW + 18, 96, 175);
                ButtonWidget altButton = ButtonWidget.builder(label, btn -> client.setScreen(new AltManagerScreen(screen)))
                        .dimensions(8, 8, btnW, 20)
                        .build();
                Screens.getButtons(screen).add(altButton);
            }
        });

        // Инициализация 3D-рендера в конце основного прохода мира (Fabric 1.21.11 END_MAIN)
        NebulaRender3DEngine.registerListener(TargetEspRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(JumpCirclesRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(HitParticlesRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(TrailsRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(ChinaHatRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(BlockOverlayRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(PredictionsRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(KillEffectsRenderer::onRender3D);
        NebulaRender3DEngine.registerListener(HitboxesRenderer::onRender3D);
        WorldRenderEvents.START_MAIN.register(context -> CullingHelper.updateCameraPosition(net.minecraft.client.MinecraftClient.getInstance()));
        WorldRenderEvents.END_MAIN.register(NebulaRender3DEngine::onEndMainRender);

        // Обработка нажатия кейбинда и логики модулей в тиках клиента
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                EntityVisibilityUtil.clearAll();
            }
            JumpCirclesRenderer.onClientTick(client);
            KillEffectsRenderer.onClientTick(client);
            AutoSprintModule.onClientTick(client);
            FreelookModule.onClientTick(client);
            ZoomModule.onClientTick(client);
            EntityCullingModule.onClientTick(client);
            BlockCullingModule.onClientTick(client);
            CullingHelper.onClientTick(client);
            MemoryCleanerModule.onClientTick(client);
            NotificationsRenderer.onClientTick(client);
            ConfigManager.onClientTick();
            while (clickGuiKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(ClickGuiScreen.getInstance());
                }
            }
        });
    }
}
