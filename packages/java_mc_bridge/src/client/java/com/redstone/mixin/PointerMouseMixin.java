package com.redstone.mixin;

import com.redstone.input.PointerInteractionHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept mouse events when the pointer is locked to a FlutterDisplay entity.
 *
 * <p>When PointerInteractionHandler is in a locked state, this mixin:
 * <ul>
 *   <li>Intercepts mouse movement and routes it to the Flutter surface
 *   <li>Intercepts mouse clicks and routes them to the Flutter surface
 *   <li>Cancels the original Minecraft input handling
 * </ul>
 */
@Mixin(MouseHandler.class)
public class PointerMouseMixin {

    // Track previous mouse position to calculate deltas
    private static double lastMouseX = 0;
    private static double lastMouseY = 0;
    private static boolean hasLastPosition = false;

    /**
     * Intercept mouse movement when locked to a FlutterDisplay.
     *
     * When the mouse is grabbed, x and y are still absolute screen coordinates,
     * but they stay near the center since the cursor is locked. We need to
     * calculate the delta from the previous position.
     */
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void onPointerMove(long window, double x, double y, CallbackInfo ci) {
        if (PointerInteractionHandler.isLocked()) {
            // Calculate delta from last position
            double deltaX = 0;
            double deltaY = 0;

            if (hasLastPosition) {
                deltaX = x - lastMouseX;
                deltaY = y - lastMouseY;
            }

            // Update last position
            lastMouseX = x;
            lastMouseY = y;
            hasLastPosition = true;

            // Only process if there's actual movement
            if (deltaX != 0 || deltaY != 0) {
                PointerInteractionHandler.handleMouseMove(deltaX, deltaY);
            }

            ci.cancel();
        } else {
            // Reset tracking when not locked
            hasLastPosition = false;
        }
    }

    /**
     * Intercept mouse button events when locked to a FlutterDisplay.
     */
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onPointerButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (PointerInteractionHandler.isLocked()) {
            int button = buttonInfo.button();
            if (action == 1) { // Press
                PointerInteractionHandler.handleMouseDown(button);
            } else if (action == 0) { // Release
                PointerInteractionHandler.handleMouseUp(button);
            }
            ci.cancel();
        }
    }
}
