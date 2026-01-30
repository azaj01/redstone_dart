package com.redstone.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redstone.DartBridgeClient;
import com.redstone.input.PointerInteractionHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Client-side packet handler.
 *
 * <p>This class handles receiving packets from the server and
 * dispatching them to the client Flutter runtime.
 */
@Environment(EnvType.CLIENT)
public class ClientPacketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ClientPacketHandler");

    // Packet type constants (must match Dart's PacketTypes)
    private static final int PACKET_TYPE_SERVER_EVENT = 0x05;

    private static boolean registered = false;

    /**
     * Register client-side packet handlers.
     * Should be called during client mod initialization.
     */
    public static void registerHandlers() {
        if (registered) {
            return;
        }
        registered = true;

        LOGGER.info("Registering client packet handlers...");

        // Handle packets from server (S2C)
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.MOD_PACKET_TYPE, (payload, context) -> {
            // Process on the render thread
            context.client().execute(() -> {
                handlePacket(payload.packetType(), payload.data());
            });
        });

        LOGGER.info("Client packet handlers registered");
    }

    /**
     * Handle a packet received from the server.
     *
     * @param packetType The packet type ID
     * @param data The packet payload data
     */
    public static void handlePacket(int packetType, byte[] data) {
        LOGGER.debug("Received S2C packet type 0x{} with {} bytes", Integer.toHexString(packetType), data.length);

        // Handle ServerEventPacket for pointer events directly in Java
        // This avoids going through the Dart FFI callback which can crash
        if (packetType == PACKET_TYPE_SERVER_EVENT) {
            if (handleServerEventDirectly(data)) {
                return; // Event was handled in Java
            }
        }

        // Dispatch to native bridge -> Flutter/Dart client runtime
        DartBridgeClient.dispatchServerPacket(packetType, data);
    }

    /**
     * Try to handle a ServerEventPacket directly in Java.
     *
     * @param data The packet payload (JSON)
     * @return true if the event was handled, false to pass to Dart
     */
    private static boolean handleServerEventDirectly(byte[] data) {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            String eventName = root.get("eventName").getAsString();
            JsonObject payload = root.getAsJsonObject("payload");

            LOGGER.info("ServerEvent: {} with payload {}", eventName, payload);

            switch (eventName) {
                case "pointer_lock" -> {
                    int entityId = payload.has("entityId") ? payload.get("entityId").getAsInt() : -1;
                    String route = payload.has("route") ? payload.get("route").getAsString() : "";
                    float width = payload.has("width") ? payload.get("width").getAsFloat() : 1.0f;
                    float height = payload.has("height") ? payload.get("height").getAsFloat() : 1.0f;
                    PointerInteractionHandler.onLockAcquired(entityId, route, width, height);
                    return true;
                }
                case "pointer_unlock" -> {
                    PointerInteractionHandler.onLockReleased();
                    return true;
                }
                default -> {
                    // Not a pointer event, let Dart handle it
                    return false;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse ServerEventPacket: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send a packet to the server.
     *
     * @param packetType The packet type ID
     * @param data The packet payload data
     */
    public static void sendToServer(int packetType, byte[] data) {
        ModPacketPayload payload = new ModPacketPayload(packetType, data);
        ClientPlayNetworking.send(payload);
    }

    /**
     * Send a packet to the server using raw bytes.
     *
     * @param packetBytes The complete packet bytes (type + length + data)
     */
    public static void sendToServer(byte[] packetBytes) {
        try {
            ModPacketPayload payload = ModPacketPayload.fromBytes(packetBytes);
            ClientPlayNetworking.send(payload);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to parse packet bytes: {}", e.getMessage());
        }
    }
}
