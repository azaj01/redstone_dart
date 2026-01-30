package com.redstone;

import com.redstone.blockentity.DartBlockEntityMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Menu types for the Redstone/Dart bridge.
 * These are registered under the "redstone" namespace.
 */
public class RedstoneMenuTypes {
    private static final Logger LOGGER = LoggerFactory.getLogger("RedstoneMenuTypes");
    public static final String NAMESPACE = "redstone";

    /**
     * Menu type for custom Dart containers (chest-like, grid-based).
     */
    public static final MenuType<DartContainerMenu> DART_CONTAINER_MENU = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(NAMESPACE, "dart_container"),
            new MenuType<>(DartContainerMenu::new, FeatureFlags.VANILLA_SET)
    );

    /**
     * Menu type for Dart block entity menus.
     * This is the unified menu type that handles all inventory sizes with a grid layout.
     * Uses ExtendedScreenHandlerType to pass MenuConfig (inventory size, data slot count)
     * from server to client, avoiding hardcoded sizes.
     */
    public static final ExtendedScreenHandlerType<DartBlockEntityMenu, DartBlockEntityMenu.MenuConfig> DART_BLOCK_ENTITY_MENU = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(NAMESPACE, "dart_block_entity"),
            new ExtendedScreenHandlerType<>(DartBlockEntityMenu::new, DartBlockEntityMenu.MenuConfig.STREAM_CODEC)
    );

    /**
     * Initialize menu types. Call this during mod initialization.
     */
    public static void initialize() {
        // Menu types are registered when the class is loaded
        LOGGER.info("Redstone menu types registered");
    }
}
