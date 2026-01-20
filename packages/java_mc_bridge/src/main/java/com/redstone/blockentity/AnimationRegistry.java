package com.redstone.blockentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Registry that maps block IDs to their animation configurations.
 *
 * When a block is registered from Dart with an animation, this registry
 * stores the animation configuration so that the AnimatedBlockEntity can
 * compute animation state based on tick count.
 *
 * Animation types supported:
 * - spin: Continuous rotation around an axis
 * - bob: Bobbing up and down (floating effect)
 * - pulse: Scale pulsing (breathing effect)
 * - combined: Multiple animations combined
 * - stateful: State-driven animations with per-element support
 */
public class AnimationRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("AnimationRegistry");

    /**
     * Configuration for a block animation.
     * Stores the JSON representation of the animation for flexible parsing.
     */
    public record AnimationConfig(
        int handlerId,
        String animationType,
        String animationJson
    ) {}

    /**
     * Info about which elements in a block model should be animated.
     * Element indices that are NOT in the animatedElementIndices set
     * will be rendered statically (no transform applied).
     */
    public record ElementAnimationInfo(
        /** Set of element indices (0-based) that should receive animation transforms */
        Set<Integer> animatedElementIndices,
        /** Total number of elements in the model */
        int totalElements
    ) {
        /** Check if a specific element index should be animated */
        public boolean isAnimated(int index) {
            return animatedElementIndices.contains(index);
        }

        /** Create info where all elements are animated (default behavior) */
        public static ElementAnimationInfo allAnimated(int totalElements) {
            Set<Integer> all = new HashSet<>();
            for (int i = 0; i < totalElements; i++) {
                all.add(i);
            }
            return new ElementAnimationInfo(all, totalElements);
        }
    }

    /**
     * Map of block ID (e.g., "mymod:spinning_block") to animation configuration.
     * Uses ConcurrentHashMap for thread safety during registration and render-time lookups.
     */
    private static final Map<String, AnimationConfig> animationConfigs = new ConcurrentHashMap<>();

    /**
     * Map of block ID to element animation info (which elements should be animated).
     * Uses ConcurrentHashMap for thread safety during registration and render-time lookups.
     */
    private static final Map<String, ElementAnimationInfo> elementAnimationInfos = new ConcurrentHashMap<>();

    /**
     * Map of block ID to animated elements model JSON.
     * This stores the raw JSON string for the animated elements model
     * (textures + elements array) which can be parsed and baked at runtime.
     * Uses ConcurrentHashMap for thread safety during registration and render-time lookups.
     */
    private static final Map<String, String> animatedElementsJson = new ConcurrentHashMap<>();

    /**
     * Register an animation configuration for a block.
     *
     * @param blockId The full block ID (namespace:path)
     * @param handlerId The Dart handler ID for this block
     * @param animationType The type of animation (spin, bob, pulse, combined, custom)
     * @param animationJson JSON string containing the animation parameters.
     *                      May include "elementAnimation" object with "animatedIndices" and "totalElements"
     *                      to specify per-element animation.
     */
    public static void registerAnimation(
            String blockId,
            int handlerId,
            String animationType,
            String animationJson) {

        AnimationConfig config = new AnimationConfig(handlerId, animationType, animationJson);
        animationConfigs.put(blockId, config);

        // Parse elementAnimation from the JSON if present
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(animationJson).getAsJsonObject();
            if (json.has("elementAnimation")) {
                com.google.gson.JsonObject elemAnim = json.getAsJsonObject("elementAnimation");
                com.google.gson.JsonArray indicesArray = elemAnim.getAsJsonArray("animatedIndices");
                int totalElements = elemAnim.get("totalElements").getAsInt();

                Set<Integer> animatedIndices = new HashSet<>();
                for (int i = 0; i < indicesArray.size(); i++) {
                    animatedIndices.add(indicesArray.get(i).getAsInt());
                }

                registerElementAnimationInfo(blockId, animatedIndices, totalElements);
            }
        } catch (Exception e) {
            // No element animation info or parse error - all elements will animate (default)
            LOGGER.debug("No element animation info for {}: {}", blockId, e.getMessage());
        }

        LOGGER.info("Registered animation config for {}: type={}", blockId, animationType);
    }

    /**
     * Get the animation configuration for a block.
     *
     * @param blockId The full block ID (namespace:path)
     * @return The configuration, or null if no animation is registered for this block
     */
    public static AnimationConfig getConfig(String blockId) {
        return animationConfigs.get(blockId);
    }

    /**
     * Check if a block has a registered animation.
     *
     * @param blockId The full block ID (namespace:path)
     * @return true if this block has an animation
     */
    public static boolean hasAnimation(String blockId) {
        return animationConfigs.containsKey(blockId);
    }

    /**
     * Get all registered animation configurations.
     *
     * @return Map of block IDs to their configurations
     */
    public static Map<String, AnimationConfig> getAllConfigs() {
        return new HashMap<>(animationConfigs);
    }

    /**
     * Get the number of registered animation types.
     */
    public static int getCount() {
        return animationConfigs.size();
    }

    /**
     * Clear all registered animation configurations.
     * Used for testing or world unload.
     */
    public static void clear() {
        animationConfigs.clear();
        elementAnimationInfos.clear();
        animatedElementsJson.clear();
    }

    /**
     * Register element animation info for a block.
     * This tells the renderer which elements should receive animation transforms.
     *
     * @param blockId The full block ID (namespace:path)
     * @param animatedIndices Set of element indices (0-based) that should be animated
     * @param totalElements Total number of elements in the model
     */
    public static void registerElementAnimationInfo(
            String blockId,
            Set<Integer> animatedIndices,
            int totalElements) {
        ElementAnimationInfo info = new ElementAnimationInfo(animatedIndices, totalElements);
        elementAnimationInfos.put(blockId, info);
        LOGGER.info("Registered element animation info for {}: {}/{} elements animated",
            blockId, animatedIndices.size(), totalElements);
    }

    /**
     * Get element animation info for a block.
     *
     * @param blockId The full block ID (namespace:path)
     * @return The info, or null if not registered (all elements animate by default)
     */
    public static ElementAnimationInfo getElementAnimationInfo(String blockId) {
        return elementAnimationInfos.get(blockId);
    }

    /**
     * Check if a specific element index should be animated for a block.
     * If no element info is registered, all elements are considered animated (default).
     *
     * @param blockId The full block ID (namespace:path)
     * @param elementIndex The 0-based element index
     * @return true if the element should receive animation transforms
     */
    public static boolean isElementAnimated(String blockId, int elementIndex) {
        ElementAnimationInfo info = elementAnimationInfos.get(blockId);
        if (info == null) {
            // No info registered = all elements animate (backward compatible)
            return true;
        }
        return info.isAnimated(elementIndex);
    }

    /**
     * Register animated elements model JSON for a block.
     * This stores the raw model JSON (textures + elements) for the animated parts,
     * which can be parsed and baked at runtime on the client.
     *
     * @param blockId The full block ID (namespace:path)
     * @param modelJson JSON string containing "textures" and "elements" like a standard block model
     */
    public static void registerAnimatedElementsJson(String blockId, String modelJson) {
        animatedElementsJson.put(blockId, modelJson);
        LOGGER.info("Registered animated elements JSON for {}: {} chars", blockId, modelJson.length());
    }

    /**
     * Get the animated elements model JSON for a block.
     *
     * @param blockId The full block ID (namespace:path)
     * @return The model JSON string, or null if not registered
     */
    @Nullable
    public static String getAnimatedElementsJson(String blockId) {
        return animatedElementsJson.get(blockId);
    }

    /**
     * Check if a block has animated elements JSON registered.
     *
     * @param blockId The full block ID (namespace:path)
     * @return true if animated elements JSON is registered for this block
     */
    public static boolean hasAnimatedElementsJson(String blockId) {
        return animatedElementsJson.containsKey(blockId);
    }
}
