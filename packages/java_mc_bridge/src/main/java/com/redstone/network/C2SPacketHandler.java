package com.redstone.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redstone.DartBridge;
import com.redstone.blockentity.DartBlockEntityMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Handles receiving packets from client to server (C2S).
 *
 * <p>This class processes packets from the client Flutter runtime
 * and dispatches them to the server Dart VM.
 */
public class C2SPacketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("C2SPacketHandler");

    /**
     * Handle a packet received from a client.
     *
     * <p>This method is called on the server thread when a packet is received.
     *
     * @param packetType The packet type ID
     * @param data The packet payload data
     * @param player The player who sent the packet
     */
    public static void handlePacket(int packetType, byte[] data, ServerPlayer player) {
        if (player == null) {
            LOGGER.warn("[C2S] Received packet from null player");
            return;
        }

        LOGGER.info("[C2S] Received packet type 0x{} from player {} with {} bytes",
                Integer.toHexString(packetType), player.getName().getString(), data.length);

        // Handle ContainerDataUpdate packet specially - it needs to update Java-side container
        if (packetType == PacketTypes.CONTAINER_DATA_UPDATE) {
            handleContainerDataUpdate(data, player);
            return;
        }

        // Dispatch to native bridge -> Dart VM
        DartBridge.dispatchClientPacket(player.getId(), packetType, data);
    }

    /**
     * Handle ContainerDataUpdate packet from client.
     *
     * <p>This updates the container data on the server side, which will then
     * sync to the block entity via the ContainerData interface.
     *
     * @param data The packet payload (JSON: {menuId, slotIndex, value})
     * @param player The player who sent the packet
     */
    private static void handleContainerDataUpdate(byte[] data, ServerPlayer player) {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            LOGGER.info("[C2S] Received ContainerDataUpdate: json={}", json);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            int menuId = obj.get("menuId").getAsInt();
            int slotIndex = obj.get("slotIndex").getAsInt();
            int value = obj.get("value").getAsInt();

            LOGGER.info("[C2S] ContainerDataUpdate: menuId={}, slotIndex={}, value={} from {}",
                    menuId, slotIndex, value, player.getName().getString());

            // Verify the player has this menu open
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || menu.containerId != menuId) {
                LOGGER.warn("[C2S] ContainerDataUpdate for wrong menu: expected {}, got {}",
                        menu != null ? menu.containerId : "null", menuId);
                return;
            }

            // Update the container data - this will sync to the block entity
            if (menu instanceof DartBlockEntityMenu dartMenu) {
                // Use setDataValue which updates the underlying ContainerData,
                // which in turn calls into Dart via DartBridge.setBlockEntityDataSlot()
                dartMenu.setDataValue(slotIndex, value);
                LOGGER.info("[C2S] Updated container data slot {} to {} for menu {}",
                        slotIndex, value, menuId);
            } else {
                LOGGER.warn("[C2S] ContainerDataUpdate for non-Dart menu type: {}",
                        menu.getClass().getSimpleName());
            }
        } catch (Exception e) {
            LOGGER.error("[C2S] Failed to handle ContainerDataUpdate: {}", e.getMessage(), e);
        }
    }

    /**
     * Packet type ID constants (must match Dart PacketTypes).
     */
    public static class PacketTypes {
        // Server-to-Client (S2C): 0x00 - 0x7F
        public static final int BLOCK_UPDATE = 0x01;
        public static final int ENTITY_UPDATE = 0x02;
        public static final int SCREEN_DATA = 0x03;
        public static final int SYNC_STATE = 0x04;
        public static final int SERVER_EVENT = 0x05;

        // Client-to-Server (C2S): 0x80 - 0xFF
        public static final int UI_ACTION = 0x80;
        public static final int REQUEST_DATA = 0x81;
        public static final int CLIENT_EVENT = 0x82;
        public static final int CONTAINER_DATA_UPDATE = 0x83;

        public static boolean isS2C(int typeId) {
            return typeId >= 0x00 && typeId < 0x80;
        }

        public static boolean isC2S(int typeId) {
            return typeId >= 0x80;
        }
    }
}
