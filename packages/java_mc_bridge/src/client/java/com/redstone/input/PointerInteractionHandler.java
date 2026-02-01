package com.redstone.input;

import com.redstone.DartBridgeClient;
import com.redstone.entity.FlutterDisplayEntity;
import com.redstone.flutter.FlutterTextureManager;
import com.redstone.network.ClientPacketHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Handles pointer interaction with FlutterDisplay entities in the 3D world.
 *
 * <p>When a player uses the Pointer item on a FlutterDisplay entity, this handler:
 * <ul>
 *   <li>Captures the mouse cursor
 *   <li>Maps mouse movements to 2D coordinates on the Flutter surface
 *   <li>Routes pointer events (hover, click, drag) to the Flutter engine
 *   <li>Releases the mouse on shift+click or when the player walks away
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class PointerInteractionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("PointerInteractionHandler");

    // Pointer event phases (must match Flutter's PointerChange enum)
    private static final int PHASE_CANCEL = 0;
    private static final int PHASE_UP = 1;
    private static final int PHASE_DOWN = 2;
    private static final int PHASE_MOVE = 3;
    private static final int PHASE_ADD = 4;
    private static final int PHASE_REMOVE = 5;
    private static final int PHASE_HOVER = 6;

    // Button masks
    private static final long BUTTON_PRIMARY = 1;
    private static final long BUTTON_SECONDARY = 2;

    // Maximum distance from entity before auto-release
    private static final double MAX_INTERACTION_DISTANCE = 10.0;

    // Lock state
    private static int lockedEntityId = -1;
    private static long lockedSurfaceId = -1;
    private static String lockedRoute = "";
    private static float lockedDisplayWidth = 1.0f;
    private static float lockedDisplayHeight = 1.0f;

    // Pointer state tracking
    private static boolean pointerAdded = false;
    private static long currentButtons = 0;
    private static long buttonsDownInFlutter = 0;

    // Virtual cursor position (accumulated from relative mouse movement)
    private static double virtualCursorX = 0;
    private static double virtualCursorY = 0;

    // Surface pixel dimensions (calculated from display size)
    private static int surfaceWidth = 256;
    private static int surfaceHeight = 256;

    // Last sent surface coordinates
    private static double lastSurfaceX = 0;
    private static double lastSurfaceY = 0;

    // Mouse sensitivity for virtual cursor movement
    private static final double MOUSE_SENSITIVITY = 0.5;

    // Throttling for pointer events to reduce lag
    private static long lastPointerEventTime = 0;
    private static final long POINTER_EVENT_MIN_INTERVAL_MS = 16; // ~60 FPS max

    /**
     * Check if we're currently locked to an entity.
     */
    public static boolean isLocked() {
        return lockedEntityId >= 0;
    }

    /**
     * Get the locked entity ID.
     */
    public static int getLockedEntityId() {
        return lockedEntityId;
    }

    /**
     * Called when the server sends a pointer_lock event.
     *
     * @param entityId The entity ID to lock to
     * @param route The Flutter route for the surface
     * @param width The display width in world units
     * @param height The display height in world units
     */
    public static void onLockAcquired(int entityId, String route, float width, float height) {
        LOGGER.info("Lock acquired: entityId={}, route='{}', size={}x{}", entityId, route, width, height);

        // If already locked to a different entity, release first
        if (lockedEntityId >= 0 && lockedEntityId != entityId) {
            releaseLock(false); // Server already knows about the new lock
        }

        lockedEntityId = entityId;
        lockedRoute = route != null ? route : "";
        lockedDisplayWidth = width;
        lockedDisplayHeight = height;

        // Get or find the surface ID
        lockedSurfaceId = findSurfaceIdForEntity(entityId, route);

        // Calculate surface pixel dimensions (PIXELS_PER_BLOCK = 128)
        surfaceWidth = Math.round(width * 128);
        surfaceHeight = Math.round(height * 128);

        // Initialize virtual cursor at center of surface
        virtualCursorX = surfaceWidth / 2.0;
        virtualCursorY = surfaceHeight / 2.0;

        // Capture the mouse (hides cursor, enables raw input)
        grabMouse();

        // Initialize pointer state
        pointerAdded = false;
        currentButtons = 0;
        buttonsDownInFlutter = 0;

        // Send initial pointer position
        sendPointerAdd(virtualCursorX, virtualCursorY);
        pointerAdded = true;
        sendPointerEvent(PHASE_HOVER, virtualCursorX, virtualCursorY, 0);

        LOGGER.info("Virtual cursor initialized at ({}, {}), surface size {}x{}",
            virtualCursorX, virtualCursorY, surfaceWidth, surfaceHeight);
    }

    /**
     * Called when the server sends a pointer_unlock event.
     */
    public static void onLockReleased() {
        LOGGER.info("Lock released by server");
        releaseLock(false); // Don't notify server, it already knows
    }

    /**
     * Release the current lock and restore normal mouse behavior.
     *
     * @param notifyServer If true, send a packet to the server to release the lock there too
     */
    private static void releaseLock(boolean notifyServer) {
        if (lockedEntityId < 0) return;

        // Send REMOVE event if pointer was added
        if (pointerAdded && lockedSurfaceId > 0) {
            sendPointerRemove();
        }

        // Notify server if this is a client-initiated release
        if (notifyServer) {
            sendUnlockRequest();
        }

        // Release the mouse
        releaseMouse();

        // Clear state
        lockedEntityId = -1;
        lockedSurfaceId = -1;
        lockedRoute = "";
        lockedDisplayWidth = 1.0f;
        lockedDisplayHeight = 1.0f;
        pointerAdded = false;
        currentButtons = 0;
        buttonsDownInFlutter = 0;
    }

    /**
     * Send a request to the server to release the pointer lock.
     */
    private static void sendUnlockRequest() {
        // Send a clientEvent packet with the unlock request
        // Packet type 0x82 = clientEvent
        String json = "{\"eventName\":\"pointer_unlock_request\",\"payload\":{}}";
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ClientPacketHandler.sendToServer(0x82, data);
        LOGGER.info("Sent pointer_unlock_request to server");
    }

    /**
     * Called every client tick to check state and handle auto-release.
     */
    public static void tick() {
        if (!isLocked()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            releaseLock(true); // Notify server
            return;
        }

        // Check distance to entity
        Entity entity = mc.level.getEntity(lockedEntityId);
        if (entity == null) {
            LOGGER.info("Locked entity no longer exists, releasing");
            releaseLock(true); // Notify server
            return;
        }

        double distance = mc.player.distanceTo(entity);
        if (distance > MAX_INTERACTION_DISTANCE) {
            LOGGER.info("Player too far from entity ({}), releasing", distance);
            releaseLock(true); // Notify server
            return;
        }

        // Check if a screen is open (should release)
        if (mc.screen != null) {
            LOGGER.info("Screen opened, releasing lock");
            releaseLock(true); // Notify server
            return;
        }
    }

    /**
     * Handle mouse movement when locked.
     *
     * When the mouse is grabbed, x and y are delta (relative) values, not absolute positions.
     * We accumulate these to track a virtual cursor position on the surface.
     *
     * @param deltaX Relative X movement
     * @param deltaY Relative Y movement
     */
    public static void handleMouseMove(double deltaX, double deltaY) {
        if (!isLocked()) return;

        // Update virtual cursor position with mouse movement
        // Apply sensitivity and clamp to surface bounds
        virtualCursorX += deltaX * MOUSE_SENSITIVITY;
        virtualCursorY += deltaY * MOUSE_SENSITIVITY;

        // Clamp to surface bounds
        virtualCursorX = Math.max(0, Math.min(surfaceWidth, virtualCursorX));
        virtualCursorY = Math.max(0, Math.min(surfaceHeight, virtualCursorY));

        lastSurfaceX = virtualCursorX;
        lastSurfaceY = virtualCursorY;

        // Throttle pointer events to reduce lag
        long now = System.currentTimeMillis();
        if (now - lastPointerEventTime < POINTER_EVENT_MIN_INTERVAL_MS) {
            return; // Skip this event, position is already updated for next time
        }
        lastPointerEventTime = now;

        // Send pointer event
        if (!pointerAdded) {
            sendPointerAdd(lastSurfaceX, lastSurfaceY);
            pointerAdded = true;
        }

        int phase = (currentButtons != 0) ? PHASE_MOVE : PHASE_HOVER;
        sendPointerEvent(phase, lastSurfaceX, lastSurfaceY, currentButtons);
    }

    /**
     * Handle mouse button press when locked.
     *
     * @param button The mouse button (0=left, 1=right, 2=middle)
     */
    public static void handleMouseDown(int button) {
        if (!isLocked()) return;

        long buttonMask = buttonToMask(button);
        currentButtons |= buttonMask;

        // Add pointer if needed
        if (!pointerAdded) {
            sendPointerAdd(virtualCursorX, virtualCursorY);
            pointerAdded = true;
        }

        // Track that we sent DOWN for this button
        buttonsDownInFlutter |= buttonMask;

        sendPointerEvent(PHASE_DOWN, virtualCursorX, virtualCursorY, currentButtons);
        LOGGER.info("Mouse down: button={}, cursor=({}, {})", button, virtualCursorX, virtualCursorY);
    }

    /**
     * Handle mouse button release when locked.
     *
     * @param button The mouse button (0=left, 1=right, 2=middle)
     */
    public static void handleMouseUp(int button) {
        if (!isLocked()) return;

        long buttonMask = buttonToMask(button);
        currentButtons &= ~buttonMask;

        // Only send UP if we sent DOWN for this button
        if ((buttonsDownInFlutter & buttonMask) == 0) {
            return;
        }
        buttonsDownInFlutter &= ~buttonMask;

        sendPointerEvent(PHASE_UP, virtualCursorX, virtualCursorY, currentButtons);
        LOGGER.info("Mouse up: button={}, cursor=({}, {})", button, virtualCursorX, virtualCursorY);
    }

    /**
     * Capture the mouse cursor.
     */
    private static void grabMouse() {
        Minecraft mc = Minecraft.getInstance();
        // Hide cursor and enable raw mouse mode for relative movement
        mc.mouseHandler.grabMouse();
        LOGGER.info("Mouse captured");
    }

    /**
     * Release the mouse cursor.
     */
    private static void releaseMouse() {
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.releaseMouse();
        LOGGER.info("Mouse released");
    }

    /**
     * Map screen coordinates to Flutter surface coordinates.
     *
     * @param screenX Screen X coordinate
     * @param screenY Screen Y coordinate
     * @return [surfaceX, surfaceY] or null if not hitting the display
     */
    private static double[] mapScreenToSurface(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;

        // Get the entity
        Entity entity = mc.level.getEntity(lockedEntityId);
        if (!(entity instanceof FlutterDisplayEntity displayEntity)) return null;

        // Get camera info from player (eye position and rotation)
        Vec3 cameraPos = new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());

        // Get window dimensions
        double windowWidth = mc.getWindow().getWidth();
        double windowHeight = mc.getWindow().getHeight();

        // Convert screen coords to normalized device coordinates (-1 to 1)
        double ndcX = (2.0 * screenX / windowWidth) - 1.0;
        double ndcY = 1.0 - (2.0 * screenY / windowHeight);

        // Get the projection matrix and invert it
        Matrix4f projectionMatrix = mc.gameRenderer.getProjectionMatrix(mc.options.fov().get());
        Matrix4f invertedProjection = new Matrix4f(projectionMatrix).invert();

        // Unproject to get ray direction in view space
        Vector4f clipCoords = new Vector4f((float) ndcX, (float) ndcY, -1.0f, 1.0f);
        Vector4f eyeCoords = invertedProjection.transform(clipCoords);
        eyeCoords.z = -1.0f;
        eyeCoords.w = 0.0f;

        // Transform to world space using player rotation
        float pitch = mc.player.getXRot();
        float yaw = mc.player.getYRot();

        // Create rotation matrix for camera
        Matrix4f cameraRotation = new Matrix4f()
            .rotateY((float) Math.toRadians(-yaw))
            .rotateX((float) Math.toRadians(-pitch));

        Vector4f worldRay = cameraRotation.transform(new Vector4f(eyeCoords.x, eyeCoords.y, eyeCoords.z, 0));
        Vector3f rayDir = new Vector3f(worldRay.x, worldRay.y, worldRay.z).normalize();

        // Get entity position and rotation
        Vec3 entityPos = displayEntity.position();
        float entityYaw = displayEntity.getYRot();
        float displayWidth = displayEntity.getDisplayWidth();
        float displayHeight = displayEntity.getDisplayHeight();

        // Calculate the plane normal based on entity rotation
        // The display faces in the entity's forward direction
        double yawRad = Math.toRadians(entityYaw);
        Vector3f planeNormal = new Vector3f(
            (float) -Math.sin(yawRad),
            0,
            (float) Math.cos(yawRad)
        );

        // Ray-plane intersection
        Vector3f planePoint = new Vector3f((float) entityPos.x, (float) entityPos.y, (float) entityPos.z);
        Vector3f rayOrigin = new Vector3f((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);

        float denom = planeNormal.dot(rayDir);
        if (Math.abs(denom) < 0.0001f) {
            // Ray is parallel to plane
            return null;
        }

        Vector3f diff = new Vector3f(planePoint).sub(rayOrigin);
        float t = diff.dot(planeNormal) / denom;

        if (t < 0) {
            // Intersection is behind camera
            return null;
        }

        // Calculate intersection point
        Vector3f intersection = new Vector3f(rayOrigin).add(new Vector3f(rayDir).mul(t));

        // Transform intersection to local quad coordinates
        // The quad is centered at entityPos, aligned with entity rotation
        Vector3f localPoint = new Vector3f(intersection).sub(planePoint);

        // Rotate to local space (inverse of entity rotation)
        Matrix4f inverseRotation = new Matrix4f().rotateY((float) -yawRad);
        Vector4f localPoint4 = inverseRotation.transform(new Vector4f(localPoint.x, localPoint.y, localPoint.z, 1));

        // localPoint4.x is horizontal offset, localPoint4.y is vertical offset
        // Check bounds
        float halfWidth = displayWidth / 2.0f;
        float halfHeight = displayHeight / 2.0f;

        if (Math.abs(localPoint4.x) > halfWidth || Math.abs(localPoint4.y) > halfHeight) {
            // Outside the display quad
            return null;
        }

        // Convert to UV coordinates (0-1)
        double u = (localPoint4.x / displayWidth) + 0.5;
        double v = 1.0 - ((localPoint4.y / displayHeight) + 0.5); // Flip V for screen coords

        // Get surface pixel dimensions
        int surfaceWidth = 256; // Default
        int surfaceHeight = 256;

        FlutterTextureManager manager = FlutterTextureManager.getInstance();
        if (manager != null && lockedSurfaceId > 0) {
            // Try to get actual dimensions from texture manager
            // For now, calculate from display size and PIXELS_PER_BLOCK
            surfaceWidth = Math.round(displayWidth * 128); // PIXELS_PER_BLOCK = 128
            surfaceHeight = Math.round(displayHeight * 128);
        }

        // Convert UV to pixel coordinates
        double surfaceX = u * surfaceWidth;
        double surfaceY = v * surfaceHeight;

        return new double[] { surfaceX, surfaceY };
    }

    /**
     * Find the surface ID for an entity.
     */
    private static long findSurfaceIdForEntity(int entityId, String route) {
        // If route is empty, it uses the main surface (0)
        // Note: Main surface pointer events use sendPointerEvent, not sendSurfacePointerEvent
        if (route == null || route.isEmpty()) {
            LOGGER.info("Entity {} uses main surface (0)", entityId);
            return 0;
        }

        // Look up the surface ID from the renderer's cache
        long surfaceId = com.redstone.render.FlutterDisplayRenderer.getSurfaceIdForEntity(entityId);
        if (surfaceId > 0) {
            LOGGER.info("Found surface {} for entity {} with route '{}'", surfaceId, entityId, route);
            return surfaceId;
        }

        // Surface not yet created - this can happen if pointer lock is acquired
        // before the entity has been rendered. Log a warning.
        LOGGER.warn("No surface found for entity {} with route '{}' - entity may not have been rendered yet", entityId, route);
        return -1;
    }

    /**
     * Convert button index to button mask.
     */
    private static long buttonToMask(int button) {
        return switch (button) {
            case 0 -> BUTTON_PRIMARY;
            case 1 -> BUTTON_SECONDARY;
            default -> 0;
        };
    }

    /**
     * Send a pointer ADD event.
     */
    private static void sendPointerAdd(double x, double y) {
        if (lockedSurfaceId == 0) {
            // Main surface
            DartBridgeClient.sendPointerEvent(PHASE_ADD, x, y, 0);
        } else if (lockedSurfaceId > 0) {
            // Routed surface
            DartBridgeClient.sendSurfacePointerEvent(lockedSurfaceId, PHASE_ADD, x, y, 0);
        }
    }

    /**
     * Send a pointer REMOVE event.
     */
    private static void sendPointerRemove() {
        if (lockedSurfaceId == 0) {
            // Main surface
            DartBridgeClient.sendPointerEvent(PHASE_REMOVE, lastSurfaceX, lastSurfaceY, 0);
        } else if (lockedSurfaceId > 0) {
            // Routed surface
            DartBridgeClient.sendSurfacePointerEvent(lockedSurfaceId, PHASE_REMOVE, lastSurfaceX, lastSurfaceY, 0);
        }
    }

    /**
     * Send a pointer event to the locked surface.
     */
    private static void sendPointerEvent(int phase, double x, double y, long buttons) {
        if (lockedSurfaceId == 0) {
            // Main surface
            DartBridgeClient.sendPointerEvent(phase, x, y, buttons);
        } else if (lockedSurfaceId > 0) {
            // Routed surface
            DartBridgeClient.sendSurfacePointerEvent(lockedSurfaceId, phase, x, y, buttons);
        }
    }

    // ==========================================================================
    // Keyboard Input Handling
    // ==========================================================================

    /**
     * Handle keyboard key press/release when locked.
     *
     * @param key GLFW key code
     * @param scancode Platform-specific scancode
     * @param action GLFW_PRESS (1), GLFW_RELEASE (0), or GLFW_REPEAT (2)
     * @param modifiers Modifier key flags (shift, ctrl, alt, etc.)
     */
    public static void handleKeyEvent(int key, int scancode, int action, int modifiers) {
        if (!isLocked()) return;

        LOGGER.debug("Key event: key={}, action={}, modifiers={}", key, action, modifiers);

        // Map GLFW action to Flutter event type
        // Flutter types: 0=down, 1=up, 2=repeat
        int type;
        if (action == 1) {
            type = 0; // GLFW_PRESS -> Flutter down
        } else if (action == 0) {
            type = 1; // GLFW_RELEASE -> Flutter up
        } else {
            type = 2; // GLFW_REPEAT -> Flutter repeat
        }

        // Use GLFW key code as both physical and logical key for simplicity
        // A proper implementation would map GLFW codes to Flutter's logical key system
        long physicalKey = key;
        long logicalKey = key;

        if (lockedSurfaceId == 0) {
            // Main surface
            DartBridgeClient.sendKeyEvent(type, physicalKey, logicalKey, null, modifiers);
        } else if (lockedSurfaceId > 0) {
            // Routed surface
            DartBridgeClient.sendSurfaceKeyEvent(lockedSurfaceId, type, physicalKey, logicalKey, null, modifiers);
        }
    }

    /**
     * Handle character input when locked.
     * This is for text input (Unicode characters typed).
     *
     * @param codePoint Unicode code point of the character
     */
    public static void handleCharEvent(int codePoint) {
        if (!isLocked()) return;

        String character = new String(Character.toChars(codePoint));
        LOGGER.debug("Char event: codePoint={}, char='{}'", codePoint, character);

        // Character input is sent as a key down event with the character
        // Using codePoint as both physical and logical key
        if (lockedSurfaceId == 0) {
            // Main surface
            DartBridgeClient.sendKeyEvent(0, codePoint, codePoint, character, 0);
        } else if (lockedSurfaceId > 0) {
            // Routed surface
            DartBridgeClient.sendSurfaceKeyEvent(lockedSurfaceId, 0, codePoint, codePoint, character, 0);
        }
    }
}
