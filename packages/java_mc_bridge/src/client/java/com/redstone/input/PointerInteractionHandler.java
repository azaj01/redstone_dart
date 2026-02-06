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
import java.util.HashMap;
import java.util.Map;

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

    // Pending key event - buffered so we can attach the character from charTyped
    // GLFW fires keyPress first, then charTyped for printable keys.
    // We defer sending key down events until charTyped arrives (or we get another key event).
    private static boolean hasPendingKeyEvent = false;
    private static int pendingType;
    private static long pendingPhysicalKey;
    private static long pendingLogicalKey;
    private static int pendingModifiers;

    // ==========================================================================
    // GLFW to Flutter Key Mapping
    // ==========================================================================

    // Flutter physical keys use USB HID usage codes with format 0x000700XX
    private static final long USB_HID_PREFIX = 0x00070000L;

    // Flutter logical key planes
    private static final long UNICODE_PLANE    = 0x00000000000L;
    private static final long UNPRINTABLE_PLANE = 0x00100000000L;
    private static final long FLUTTER_PLANE     = 0x00200000000L;

    // GLFW key code -> Flutter physical key (USB HID)
    private static final Map<Integer, Long> GLFW_TO_PHYSICAL = new HashMap<>();
    // GLFW key code -> Flutter logical key
    private static final Map<Integer, Long> GLFW_TO_LOGICAL = new HashMap<>();

    static {
        // Letters A-Z: GLFW 65-90 -> USB HID 0x04-0x1D
        for (int i = 0; i < 26; i++) {
            GLFW_TO_PHYSICAL.put(65 + i, USB_HID_PREFIX | (0x04 + i));
            // Logical: lowercase Unicode code point (a=0x61, b=0x62, ...)
            GLFW_TO_LOGICAL.put(65 + i, UNICODE_PLANE | (0x61 + i));
        }

        // Digits 1-9: GLFW 49-57 -> USB HID 0x1E-0x26
        for (int i = 0; i < 9; i++) {
            GLFW_TO_PHYSICAL.put(49 + i, USB_HID_PREFIX | (0x1E + i));
            // Logical: Unicode code point for '1'-'9'
            GLFW_TO_LOGICAL.put(49 + i, UNICODE_PLANE | (0x31 + i));
        }
        // Digit 0: GLFW 48 -> USB HID 0x27
        GLFW_TO_PHYSICAL.put(48, USB_HID_PREFIX | 0x27);
        GLFW_TO_LOGICAL.put(48, UNICODE_PLANE | 0x30);

        // Special keys
        mapKey(32,  0x2C, UNICODE_PLANE | 0x20);          // Space
        mapKey(257, 0x28, UNPRINTABLE_PLANE | 0x0D);      // Enter
        mapKey(256, 0x29, UNPRINTABLE_PLANE | 0x1B);      // Escape
        mapKey(259, 0x2A, UNPRINTABLE_PLANE | 0x08);      // Backspace
        mapKey(258, 0x2B, UNPRINTABLE_PLANE | 0x09);      // Tab
        mapKey(261, 0x4C, UNPRINTABLE_PLANE | 0x7F);      // Delete

        // Punctuation / symbols
        mapKey(45,  0x2D, UNICODE_PLANE | 0x2D); // Minus
        mapKey(61,  0x2E, UNICODE_PLANE | 0x3D); // Equal
        mapKey(91,  0x2F, UNICODE_PLANE | 0x5B); // BracketLeft
        mapKey(93,  0x30, UNICODE_PLANE | 0x5D); // BracketRight
        mapKey(92,  0x31, UNICODE_PLANE | 0x5C); // Backslash
        mapKey(59,  0x33, UNICODE_PLANE | 0x3B); // Semicolon
        mapKey(39,  0x34, UNICODE_PLANE | 0x27); // Quote/Apostrophe
        mapKey(96,  0x35, UNICODE_PLANE | 0x60); // Backquote/GraveAccent
        mapKey(44,  0x36, UNICODE_PLANE | 0x2C); // Comma
        mapKey(46,  0x37, UNICODE_PLANE | 0x2E); // Period
        mapKey(47,  0x38, UNICODE_PLANE | 0x2F); // Slash

        // Lock keys
        mapKey(280, 0x39, UNPRINTABLE_PLANE | 0x104); // CapsLock
        mapKey(281, 0x47, UNPRINTABLE_PLANE | 0x10C); // ScrollLock
        mapKey(282, 0x53, UNPRINTABLE_PLANE | 0x10A); // NumLock

        // Function keys F1-F12: GLFW 290-301 -> USB HID 0x3A-0x45
        for (int i = 0; i < 12; i++) {
            GLFW_TO_PHYSICAL.put(290 + i, USB_HID_PREFIX | (0x3A + i));
            GLFW_TO_LOGICAL.put(290 + i, UNPRINTABLE_PLANE | (0x801 + i));
        }
        // F13-F24: GLFW 302-313 -> USB HID 0x68-0x73
        for (int i = 0; i < 12; i++) {
            GLFW_TO_PHYSICAL.put(302 + i, USB_HID_PREFIX | (0x68 + i));
            GLFW_TO_LOGICAL.put(302 + i, UNPRINTABLE_PLANE | (0x80D + i));
        }
        // F25: GLFW 314 -> USB HID 0x74
        GLFW_TO_PHYSICAL.put(314, USB_HID_PREFIX | 0x74);
        GLFW_TO_LOGICAL.put(314, UNPRINTABLE_PLANE | 0x819);

        // Navigation keys
        mapKey(283, 0x46, UNPRINTABLE_PLANE | 0x608); // PrintScreen
        mapKey(284, 0x48, UNPRINTABLE_PLANE | 0x509); // Pause
        mapKey(260, 0x49, UNPRINTABLE_PLANE | 0x407); // Insert
        mapKey(268, 0x4A, UNPRINTABLE_PLANE | 0x306); // Home
        mapKey(266, 0x4B, UNPRINTABLE_PLANE | 0x308); // PageUp
        mapKey(269, 0x4D, UNPRINTABLE_PLANE | 0x305); // End
        mapKey(267, 0x4E, UNPRINTABLE_PLANE | 0x307); // PageDown

        // Arrow keys
        mapKey(262, 0x4F, UNPRINTABLE_PLANE | 0x303); // Right
        mapKey(263, 0x50, UNPRINTABLE_PLANE | 0x302); // Left
        mapKey(264, 0x51, UNPRINTABLE_PLANE | 0x301); // Down
        mapKey(265, 0x52, UNPRINTABLE_PLANE | 0x304); // Up

        // Numpad keys
        mapKey(331, 0x54, FLUTTER_PLANE | 0x22F); // KP Divide
        mapKey(332, 0x55, FLUTTER_PLANE | 0x22A); // KP Multiply
        mapKey(333, 0x56, FLUTTER_PLANE | 0x22D); // KP Subtract
        mapKey(334, 0x57, FLUTTER_PLANE | 0x22B); // KP Add
        mapKey(335, 0x58, FLUTTER_PLANE | 0x20D); // KP Enter
        for (int i = 0; i < 10; i++) {
            // KP 0-9: GLFW 320-329 -> USB HID 0x62,0x59-0x61
            int hidCode = (i == 0) ? 0x62 : (0x59 + i - 1);
            GLFW_TO_PHYSICAL.put(320 + i, USB_HID_PREFIX | hidCode);
            GLFW_TO_LOGICAL.put(320 + i, FLUTTER_PLANE | (0x230 + i));
        }
        mapKey(330, 0x63, FLUTTER_PLANE | 0x22E); // KP Decimal
        mapKey(336, 0x67, FLUTTER_PLANE | 0x23D); // KP Equal

        // Modifier keys
        mapKey(340, 0xE1, FLUTTER_PLANE | 0x102); // Left Shift
        mapKey(341, 0xE0, FLUTTER_PLANE | 0x100); // Left Control
        mapKey(342, 0xE2, FLUTTER_PLANE | 0x104); // Left Alt
        mapKey(343, 0xE3, FLUTTER_PLANE | 0x106); // Left Super/Meta
        mapKey(344, 0xE5, FLUTTER_PLANE | 0x103); // Right Shift
        mapKey(345, 0xE4, FLUTTER_PLANE | 0x101); // Right Control
        mapKey(346, 0xE6, FLUTTER_PLANE | 0x105); // Right Alt
        mapKey(347, 0xE7, FLUTTER_PLANE | 0x107); // Right Super/Meta
        mapKey(348, 0x76, UNPRINTABLE_PLANE | 0x505); // Menu
    }

    private static void mapKey(int glfwKey, int usbHid, long logicalKey) {
        GLFW_TO_PHYSICAL.put(glfwKey, USB_HID_PREFIX | usbHid);
        GLFW_TO_LOGICAL.put(glfwKey, logicalKey);
    }

    /**
     * Convert a GLFW key code to Flutter physical key (USB HID).
     */
    private static long glfwToPhysicalKey(int glfwKey) {
        Long physical = GLFW_TO_PHYSICAL.get(glfwKey);
        if (physical != null) return physical;
        // Fallback: use GLFW plane for unknown keys
        return 0x01800000000L | glfwKey;
    }

    /**
     * Convert a GLFW key code to Flutter logical key.
     */
    private static long glfwToLogicalKey(int glfwKey) {
        Long logical = GLFW_TO_LOGICAL.get(glfwKey);
        if (logical != null) return logical;
        // Fallback: use GLFW plane for unknown keys
        return 0x01800000000L | glfwKey;
    }

    /**
     * Check if we're currently locked to an entity.
     */
    public static boolean isLocked() {
        return lockedEntityId >= 0;
    }

    /**
     * Immediately unlock from the current entity.
     * Called when the user presses ESC to deselect the display.
     */
    public static void unlock() {
        if (!isLocked()) return;
        LOGGER.info("Immediate unlock requested");
        releaseLock(true);
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

        // Clear state first (before mouse operations, so isLocked() returns false
        // and our mouse/keyboard mixins won't intercept events during the transition)
        lockedEntityId = -1;
        lockedSurfaceId = -1;
        lockedRoute = "";
        lockedDisplayWidth = 1.0f;
        lockedDisplayHeight = 1.0f;
        pointerAdded = false;
        currentButtons = 0;
        buttonsDownInFlutter = 0;

        // Flush any pending key event before releasing
        hasPendingKeyEvent = false;

        // Mouse state: The mouse was already grabbed before we locked (for normal Minecraft
        // camera control). During lock, we use the same grabbed state. On unlock, we just
        // leave it grabbed - Minecraft will handle it from here.
        // We only need to release if a screen is open (e.g., player opened inventory while locked).
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            // Screen is open: release the mouse so cursor is visible on the screen
            mc.mouseHandler.releaseMouse();
            LOGGER.info("Mouse released (screen is open)");
        } else {
            LOGGER.info("Unlock complete, mouse stays grabbed for normal gameplay");
        }
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

        // Flush any pending key event that didn't get a charTyped callback
        // (non-printable keys like arrows, function keys, etc.)
        flushPendingKeyEvent(null);

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

        LOGGER.info("Key event: key={}, action={}, modifiers={}", key, action, modifiers);

        // Flush any pending key event first (it didn't get a character)
        flushPendingKeyEvent(null);

        // Map GLFW action to Flutter event type
        // Flutter embedder types from flutter_embedder.h:
        //   kFlutterKeyEventTypeUp = 1
        //   kFlutterKeyEventTypeDown = 2
        //   kFlutterKeyEventTypeRepeat = 3
        int type;
        if (action == 1) {
            type = 2; // GLFW_PRESS -> kFlutterKeyEventTypeDown
        } else if (action == 0) {
            type = 1; // GLFW_RELEASE -> kFlutterKeyEventTypeUp
        } else {
            type = 3; // GLFW_REPEAT -> kFlutterKeyEventTypeRepeat
        }

        // Map GLFW key codes to Flutter's expected encoding:
        // Physical = USB HID usage code (0x000700XX)
        // Logical = Unicode code point (printable) or plane-prefixed ID (non-printable)
        long physicalKey = glfwToPhysicalKey(key);
        long logicalKey = glfwToLogicalKey(key);

        // For key down and repeat events, buffer the event so we can attach
        // the character from the subsequent charTyped callback.
        // GLFW fires keyPress first, then charTyped for printable keys.
        if (type == 2 || type == 3) { // Down or Repeat
            hasPendingKeyEvent = true;
            pendingType = type;
            pendingPhysicalKey = physicalKey;
            pendingLogicalKey = logicalKey;
            pendingModifiers = modifiers;
        } else {
            // Key up events: send immediately with no character
            sendKeyToFlutter(type, physicalKey, logicalKey, null, modifiers);
        }
    }

    /**
     * Flush the pending key event, optionally attaching a character.
     */
    private static void flushPendingKeyEvent(String character) {
        if (!hasPendingKeyEvent) return;
        hasPendingKeyEvent = false;
        sendKeyToFlutter(pendingType, pendingPhysicalKey, pendingLogicalKey, character, pendingModifiers);
    }

    /**
     * Send a key event to the appropriate Flutter surface.
     */
    private static void sendKeyToFlutter(int type, long physicalKey, long logicalKey, String character, int modifiers) {
        LOGGER.info("Sending key to Flutter: type={}, physical=0x{}, logical=0x{}, char='{}', surface={}",
            type, Long.toHexString(physicalKey), Long.toHexString(logicalKey), character, lockedSurfaceId);
        if (lockedSurfaceId == 0) {
            DartBridgeClient.sendKeyEvent(type, physicalKey, logicalKey, character, modifiers);
        } else if (lockedSurfaceId > 0) {
            DartBridgeClient.sendSurfaceKeyEvent(lockedSurfaceId, type, physicalKey, logicalKey, character, modifiers);
        }
    }

    /**
     * Handle character input when locked.
     * This is for text input (Unicode characters typed).
     *
     * Character events come from GLFW's charTyped callback, which only fires for
     * character-producing key presses. We ignore these and let the character be
     * derived from the key down event instead, as Flutter's key event system
     * handles character generation internally based on the logical key.
     *
     * @param codePoint Unicode code point of the character
     */
    public static void handleCharEvent(int codePoint) {
        if (!isLocked()) return;

        // GLFW fires charTyped right after keyPress for printable keys.
        // We have a pending key event buffered - flush it now with the character attached.
        String character = new String(Character.toChars(codePoint));
        LOGGER.info("Char event: codePoint={}, char='{}', hasPending={}", codePoint, character, hasPendingKeyEvent);
        flushPendingKeyEvent(character);
    }
}
