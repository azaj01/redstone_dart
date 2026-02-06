package com.redstone.mixin;

import com.redstone.input.PointerInteractionHandler;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept keyboard events when the pointer is locked to a FlutterDisplay entity.
 *
 * <p>When PointerInteractionHandler is in a locked state, this mixin:
 * <ul>
 *   <li>Intercepts key presses and routes them to the Flutter surface
 *   <li>Intercepts character input and routes it to the Flutter surface
 *   <li>Cancels the original Minecraft input handling (except for Escape to release)
 * </ul>
 */
@Mixin(KeyboardHandler.class)
public class PointerKeyboardMixin {

    /**
     * Intercept key events when locked to a FlutterDisplay.
     *
     * @param window GLFW window handle
     * @param action GLFW_PRESS (1), GLFW_RELEASE (0), or GLFW_REPEAT (2)
     * @param keyEvent The KeyEvent containing key code, scancode, and modifiers
     */
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onPointerKeyPress(long window, int action, KeyEvent keyEvent, CallbackInfo ci) {
        if (PointerInteractionHandler.isLocked()) {
            int key = keyEvent.key();
            int scancode = keyEvent.scancode();
            int modifiers = keyEvent.modifiers();

            // GLFW_KEY_ESCAPE = 256, GLFW_PRESS = 1
            // Unlock immediately on ESC press, before Minecraft opens the pause screen.
            // Previously we let ESC pass through and relied on tick() to detect
            // mc.screen != null, but that caused a race condition where the mouse
            // state was corrupted by the time the lock was released.
            if (key == 256 && action == 1) {
                PointerInteractionHandler.unlock();
                ci.cancel(); // Don't open pause menu, just unlock
                return;
            }

            // Route key event to Flutter surface
            PointerInteractionHandler.handleKeyEvent(key, scancode, action, modifiers);
            ci.cancel();
        }
    }

    /**
     * Intercept character input when locked to a FlutterDisplay.
     * This is for text input (characters typed).
     *
     * @param window GLFW window handle
     * @param characterEvent The CharacterEvent containing the character
     */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onPointerCharTyped(long window, CharacterEvent characterEvent, CallbackInfo ci) {
        if (PointerInteractionHandler.isLocked()) {
            // Route character to Flutter surface
            int codePoint = characterEvent.codepoint();
            PointerInteractionHandler.handleCharEvent(codePoint);
            ci.cancel();
        }
    }
}
