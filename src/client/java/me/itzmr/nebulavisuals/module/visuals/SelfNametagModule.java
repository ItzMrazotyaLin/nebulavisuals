package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль SelfNametag (категория Visuals): отображает стандартный майнкрафтовский неймтег
 * над головой локального игрока в режиме третьего лица (F5), включая префиксы и цвета команд.
 */
public class SelfNametagModule extends VisualModule {
    public SelfNametagModule() {
        super("SelfNametag", "Display your own vanilla nametag in third-person perspective", Category.VISUALS, false);
    }
}
