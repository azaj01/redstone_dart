package com.redstone.blockentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redstone.DartBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block entity that stores animation state with previous values for interpolation.
 *
 * Animation state includes rotation, translation, scale, and pivot point.
 * Previous tick values are stored for smooth interpolation during rendering.
 *
 * For built-in animations (spin, bob, pulse), the animation is computed directly
 * in Java based on the animation config stored in AnimationRegistry. This avoids
 * JNI overhead for simple time-based animations.
 *
 * For custom animations, Dart is called via JNI to compute the animation state.
 */
public class AnimatedBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger("AnimatedBlockEntity");
    private static final Gson GSON = new Gson();

    // Handler ID linking to the Dart-side block entity type handler
    protected final int handlerId;

    // Hash of the block position for unique identification
    protected final long blockPosHash;

    // Current animation state (what gets rendered)
    private float rotationX = 0, rotationY = 0, rotationZ = 0;
    private float translateX = 0, translateY = 0, translateZ = 0;
    private float scaleX = 1, scaleY = 1, scaleZ = 1;
    private float pivotX = 0.5f, pivotY = 0.5f, pivotZ = 0.5f;

    // Target animation state (for smooth interpolation in stateful animations)
    private float targetRotationX = 0, targetRotationY = 0, targetRotationZ = 0;
    private float targetTranslateX = 0, targetTranslateY = 0, targetTranslateZ = 0;
    private float targetScaleX = 1, targetScaleY = 1, targetScaleZ = 1;

    // Interpolation speed for stateful animations (units per tick)
    private static final float INTERPOLATION_SPEED = 15.0f; // degrees or units per tick

    // Previous tick state (for partial tick interpolation during rendering)
    private float oRotationX = 0, oRotationY = 0, oRotationZ = 0;
    private float oTranslateX = 0, oTranslateY = 0, oTranslateZ = 0;
    private float oScaleX = 1, oScaleY = 1, oScaleZ = 1;

    // Animation time tracking
    private int tickCount = 0;

    // Cached animation config (parsed once on first tick)
    private JsonObject animationConfig = null;
    private boolean configLoaded = false;
    private String blockId = null;

    // Stateful animation state - per-key interpolation values
    private final Map<String, AnimationStateValue> stateValues = new HashMap<>();

    // Cached stateful animation config
    private boolean isStatefulAnimation = false;
    private String easingType = "linear";

    /**
     * Holds interpolation state for a single animation input key.
     * Stores current, target, and previous values for smooth interpolation.
     */
    public static class AnimationStateValue {
        public double target = 0.0;
        public double current = 0.0;
        public double previous = 0.0;  // For partial tick interpolation
        public double speed = 0.1;     // Interpolation speed per tick

        /**
         * Tick the interpolation - move current toward target.
         */
        public void tick() {
            previous = current;
            if (current < target) {
                current = Math.min(current + speed, target);
            } else if (current > target) {
                current = Math.max(current - speed, target);
            }
        }

        /**
         * Get the interpolated value for rendering (between previous and current).
         */
        public double getInterpolated(float partialTick) {
            return previous + (current - previous) * partialTick;
        }
    }

    public AnimatedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int handlerId) {
        super(type, pos, state);
        this.handlerId = handlerId;
        this.blockPosHash = DartBlockEntity.posToHash(pos);
    }

    /**
     * Tick method. Called every game tick (20 times per second).
     * Runs on both client and server to keep animation state synchronized.
     * Saves previous state for interpolation and computes animation based on config.
     */
    public static void tick(Level level, BlockPos pos, BlockState state, AnimatedBlockEntity entity) {
        // Save previous state for interpolation
        entity.oRotationX = entity.rotationX;
        entity.oRotationY = entity.rotationY;
        entity.oRotationZ = entity.rotationZ;
        entity.oTranslateX = entity.translateX;
        entity.oTranslateY = entity.translateY;
        entity.oTranslateZ = entity.translateZ;
        entity.oScaleX = entity.scaleX;
        entity.oScaleY = entity.scaleY;
        entity.oScaleZ = entity.scaleZ;

        entity.tickCount++;

        // Compute animation state based on config
        entity.computeAnimationState();

        // Tick stateful animation interpolation
        for (AnimationStateValue value : entity.stateValues.values()) {
            value.tick();
        }
    }

    /**
     * Load animation config from registry if not already loaded.
     */
    private void loadAnimationConfig() {
        if (configLoaded) return;
        configLoaded = true;

        // Get block ID from block state
        if (blockId == null) {
            blockId = BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).toString();
        }

        // Load animation config from registry
        AnimationRegistry.AnimationConfig config = AnimationRegistry.getConfig(blockId);
        if (config != null && config.animationJson() != null && !config.animationJson().isEmpty()) {
            try {
                animationConfig = GSON.fromJson(config.animationJson(), JsonObject.class);

                // Check if this is a stateful animation
                if (animationConfig != null && animationConfig.has("type")) {
                    String type = animationConfig.get("type").getAsString();
                    if ("stateful".equals(type)) {
                        isStatefulAnimation = true;
                        if (animationConfig.has("easing")) {
                            easingType = animationConfig.get("easing").getAsString();
                        }
                        // Initialize state values from inputs config
                        if (animationConfig.has("inputs")) {
                            JsonObject inputs = animationConfig.getAsJsonObject("inputs");
                            for (String key : inputs.keySet()) {
                                JsonObject inputConfig = inputs.getAsJsonObject(key);
                                AnimationStateValue stateValue = new AnimationStateValue();
                                if (inputConfig.has("default")) {
                                    stateValue.current = inputConfig.get("default").getAsDouble();
                                    stateValue.previous = stateValue.current;
                                    stateValue.target = stateValue.current;
                                }
                                if (inputConfig.has("speed")) {
                                    stateValue.speed = inputConfig.get("speed").getAsDouble();
                                }
                                stateValues.put(key, stateValue);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse animation config for {}: {}", blockId, e.getMessage());
            }
        }
    }

    /**
     * Compute animation state based on the animation config.
     * Supports spin, bob, pulse, and combined animations computed directly in Java.
     * For custom animations, falls back to Dart JNI calls.
     */
    private void computeAnimationState() {
        loadAnimationConfig();

        if (animationConfig == null) {
            return;
        }

        // For stateful animations, DON'T reset the values - they're controlled externally
        // via setAnimationTarget() calls from Dart. Resetting here would override those values.
        if (!isStatefulAnimation) {
            // Reset state before applying time-based animations (spin, bob, pulse, etc.)
            rotationX = 0;
            rotationY = 0;
            rotationZ = 0;
            translateX = 0;
            translateY = 0;
            translateZ = 0;
            scaleX = 1;
            scaleY = 1;
            scaleZ = 1;
            pivotX = 0.5f;
            pivotY = 0.5f;
            pivotZ = 0.5f;
        }

        // Compute time in seconds (20 ticks per second)
        double time = tickCount / 20.0;

        // Apply animation based on type
        applyAnimation(animationConfig, time);
    }

    /**
     * Apply a single animation to the current state.
     */
    private void applyAnimation(JsonObject anim, double time) {
        String type = anim.has("type") ? anim.get("type").getAsString() : "";

        switch (type) {
            case "spin" -> applySpinAnimation(anim, time);
            case "bob" -> applyBobAnimation(anim, time);
            case "pulse" -> applyPulseAnimation(anim, time);
            case "combined" -> applyCombinedAnimation(anim, time);
            case "custom" -> applyCustomAnimation(time);
            case "stateful" -> applyStatefulAnimation();
            default -> LOGGER.warn("Unknown animation type: {}", type);
        }
    }

    /**
     * Apply spin animation: continuous rotation around an axis.
     */
    private void applySpinAnimation(JsonObject anim, double time) {
        String axis = anim.has("axis") ? anim.get("axis").getAsString() : "y";
        double speed = anim.has("speed") ? anim.get("speed").getAsDouble() : 1.0;

        // Load pivot point
        if (anim.has("pivot")) {
            JsonArray pivot = anim.getAsJsonArray("pivot");
            if (pivot.size() >= 3) {
                pivotX = pivot.get(0).getAsFloat();
                pivotY = pivot.get(1).getAsFloat();
                pivotZ = pivot.get(2).getAsFloat();
            }
        }

        // Compute rotation angle: speed rotations per second, 360 degrees per rotation
        float angle = (float) ((time * speed * 360.0) % 360.0);

        switch (axis) {
            case "x" -> rotationX += angle;
            case "y" -> rotationY += angle;
            case "z" -> rotationZ += angle;
        }
    }

    /**
     * Apply bob animation: bobbing up and down (floating effect).
     */
    private void applyBobAnimation(JsonObject anim, double time) {
        double amplitude = anim.has("amplitude") ? anim.get("amplitude").getAsDouble() : 0.1;
        double frequency = anim.has("frequency") ? anim.get("frequency").getAsDouble() : 1.0;

        // Sine wave oscillation
        translateY += (float) (Math.sin(time * frequency * 2 * Math.PI) * amplitude);
    }

    /**
     * Apply pulse animation: scale pulsing (breathing effect).
     */
    private void applyPulseAnimation(JsonObject anim, double time) {
        double minScale = anim.has("minScale") ? anim.get("minScale").getAsDouble() : 0.9;
        double maxScale = anim.has("maxScale") ? anim.get("maxScale").getAsDouble() : 1.1;
        double frequency = anim.has("frequency") ? anim.get("frequency").getAsDouble() : 1.0;

        // Load pivot point
        if (anim.has("pivot")) {
            JsonArray pivot = anim.getAsJsonArray("pivot");
            if (pivot.size() >= 3) {
                pivotX = pivot.get(0).getAsFloat();
                pivotY = pivot.get(1).getAsFloat();
                pivotZ = pivot.get(2).getAsFloat();
            }
        }

        // Oscillate between 0 and 1
        double t = (Math.sin(time * frequency * 2 * Math.PI) + 1) / 2;
        float scale = (float) (minScale + (maxScale - minScale) * t);

        scaleX *= scale;
        scaleY *= scale;
        scaleZ *= scale;
    }

    /**
     * Apply combined animation: multiple animations combined.
     */
    private void applyCombinedAnimation(JsonObject anim, double time) {
        if (!anim.has("animations")) return;

        JsonArray animations = anim.getAsJsonArray("animations");
        for (int i = 0; i < animations.size(); i++) {
            JsonObject subAnim = animations.get(i).getAsJsonObject();
            applyAnimation(subAnim, time);
        }
    }

    /**
     * Apply custom animation: call Dart via JNI to get animation state.
     * This is used for complex animations that can't be expressed declaratively.
     */
    private void applyCustomAnimation(double time) {
        // For custom animations, we would call Dart via JNI
        // This is a placeholder for future implementation
        // For now, custom animations are not supported on the Java side
        LOGGER.warn("Custom animations require Dart JNI callbacks (not yet implemented)");
    }

    /**
     * Apply stateful animation: smoothly interpolate toward target values.
     *
     * For stateful animations, the TARGET values are set by Dart via setAnimationTarget().
     * This method smoothly interpolates the CURRENT values toward those targets each tick,
     * creating smooth animation transitions.
     *
     * The Dart side controls the animation by calling setAnimationRotation(),
     * setAnimationTranslation(), etc. which set the target values.
     */
    private void applyStatefulAnimation() {
        // Smoothly interpolate current values toward targets
        rotationX = interpolateToward(rotationX, targetRotationX, INTERPOLATION_SPEED);
        rotationY = interpolateToward(rotationY, targetRotationY, INTERPOLATION_SPEED);
        rotationZ = interpolateToward(rotationZ, targetRotationZ, INTERPOLATION_SPEED);
        translateX = interpolateToward(translateX, targetTranslateX, INTERPOLATION_SPEED * 0.01f);
        translateY = interpolateToward(translateY, targetTranslateY, INTERPOLATION_SPEED * 0.01f);
        translateZ = interpolateToward(translateZ, targetTranslateZ, INTERPOLATION_SPEED * 0.01f);
        scaleX = interpolateToward(scaleX, targetScaleX, INTERPOLATION_SPEED * 0.01f);
        scaleY = interpolateToward(scaleY, targetScaleY, INTERPOLATION_SPEED * 0.01f);
        scaleZ = interpolateToward(scaleZ, targetScaleZ, INTERPOLATION_SPEED * 0.01f);
    }

    /**
     * Interpolate a value toward a target by a fixed step per tick.
     */
    private static float interpolateToward(float current, float target, float speed) {
        if (current < target) {
            return Math.min(current + speed, target);
        } else if (current > target) {
            return Math.max(current - speed, target);
        }
        return current;
    }

    /**
     * Set the target value for a stateful animation input.
     * Called from Dart via JNI when animation state changes.
     *
     * Supports two types of keys:
     * 1. Direct transform keys: rotationX/Y/Z, translateX/Y/Z, scaleX/Y/Z, pivotX/Y/Z
     *    - These set the transform values directly for immediate use by the renderer
     * 2. Abstract state keys (e.g., "lidOpen")
     *    - These are interpolated and can be used by stateful animation configs
     *
     * @param key The state key (e.g., "rotationX", "lidOpen")
     * @param targetValue The target value
     * @param speed Interpolation speed per tick (ignored for direct transform keys)
     */
    public void setAnimationTarget(String key, double targetValue, double speed) {
        // Handle direct transform keys - set TARGET values for smooth interpolation
        switch (key) {
            case "rotationX" -> targetRotationX = (float) targetValue;
            case "rotationY" -> targetRotationY = (float) targetValue;
            case "rotationZ" -> targetRotationZ = (float) targetValue;
            case "translateX" -> targetTranslateX = (float) targetValue;
            case "translateY" -> targetTranslateY = (float) targetValue;
            case "translateZ" -> targetTranslateZ = (float) targetValue;
            case "scaleX" -> targetScaleX = (float) targetValue;
            case "scaleY" -> targetScaleY = (float) targetValue;
            case "scaleZ" -> targetScaleZ = (float) targetValue;
            // Pivots are set immediately (no interpolation needed)
            case "pivotX" -> pivotX = (float) targetValue;
            case "pivotY" -> pivotY = (float) targetValue;
            case "pivotZ" -> pivotZ = (float) targetValue;
            default -> {
                // Abstract state value handling - interpolated over time
                AnimationStateValue stateValue = stateValues.computeIfAbsent(key, k -> new AnimationStateValue());
                stateValue.target = targetValue;
                stateValue.speed = speed;
            }
        }

        // Notify clients of the change (server-side only)
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            setChanged();
        }
    }

    /**
     * Get the interpolated value for a state key.
     * Used by the renderer to get smooth animation values.
     */
    public double getStateValue(String key, float partialTick) {
        AnimationStateValue stateValue = stateValues.get(key);
        if (stateValue == null) return 0.0;

        double rawValue = stateValue.getInterpolated(partialTick);
        return applyEasing(rawValue, easingType);
    }

    /**
     * Get all state keys for this animation.
     */
    public Set<String> getStateKeys() {
        return stateValues.keySet();
    }

    /**
     * Check if this block has a stateful animation.
     */
    public boolean isStatefulAnimation() {
        return isStatefulAnimation;
    }

    /**
     * Apply easing function to a value.
     * Value should be between 0.0 and 1.0 for best results.
     */
    private static double applyEasing(double t, String easing) {
        // Clamp t to 0-1 range for easing functions
        t = Math.max(0.0, Math.min(1.0, t));

        return switch (easing) {
            case "easeIn" -> t * t;
            case "easeOut" -> 1.0 - (1.0 - t) * (1.0 - t);
            case "easeInOut" -> t < 0.5 ? 2.0 * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 2) / 2.0;
            case "bounce" -> {
                double n1 = 7.5625;
                double d1 = 2.75;
                double x = t;
                if (x < 1.0 / d1) {
                    yield n1 * x * x;
                } else if (x < 2.0 / d1) {
                    x -= 1.5 / d1;
                    yield n1 * x * x + 0.75;
                } else if (x < 2.5 / d1) {
                    x -= 2.25 / d1;
                    yield n1 * x * x + 0.9375;
                } else {
                    x -= 2.625 / d1;
                    yield n1 * x * x + 0.984375;
                }
            }
            case "elastic" -> {
                if (t == 0.0 || t == 1.0) yield t;
                double c4 = (2.0 * Math.PI) / 3.0;
                yield Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0;
            }
            default -> t; // linear
        };
    }

    /**
     * Update animation state. Called from Dart via JNI.
     *
     * @param rotX Rotation around X axis in degrees
     * @param rotY Rotation around Y axis in degrees
     * @param rotZ Rotation around Z axis in degrees
     * @param transX Translation along X axis
     * @param transY Translation along Y axis
     * @param transZ Translation along Z axis
     * @param scaleX Scale factor along X axis
     * @param scaleY Scale factor along Y axis
     * @param scaleZ Scale factor along Z axis
     * @param pivotX Pivot point X (0-1, relative to block)
     * @param pivotY Pivot point Y (0-1, relative to block)
     * @param pivotZ Pivot point Z (0-1, relative to block)
     */
    public void setAnimationState(
        float rotX, float rotY, float rotZ,
        float transX, float transY, float transZ,
        float scaleX, float scaleY, float scaleZ,
        float pivotX, float pivotY, float pivotZ
    ) {
        this.rotationX = rotX;
        this.rotationY = rotY;
        this.rotationZ = rotZ;
        this.translateX = transX;
        this.translateY = transY;
        this.translateZ = transZ;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.scaleZ = scaleZ;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.pivotZ = pivotZ;
    }

    // Getters for handler and position
    public int getHandlerId() { return handlerId; }
    public long getBlockPosHash() { return blockPosHash; }

    // Getters for current state
    public float getRotationX() { return rotationX; }
    public float getRotationY() { return rotationY; }
    public float getRotationZ() { return rotationZ; }
    public float getTranslateX() { return translateX; }
    public float getTranslateY() { return translateY; }
    public float getTranslateZ() { return translateZ; }
    public float getScaleX() { return scaleX; }
    public float getScaleY() { return scaleY; }
    public float getScaleZ() { return scaleZ; }
    public float getPivotX() { return pivotX; }
    public float getPivotY() { return pivotY; }
    public float getPivotZ() { return pivotZ; }

    // Getters for previous state (for interpolation)
    public float getORotationX() { return oRotationX; }
    public float getORotationY() { return oRotationY; }
    public float getORotationZ() { return oRotationZ; }
    public float getOTranslateX() { return oTranslateX; }
    public float getOTranslateY() { return oTranslateY; }
    public float getOTranslateZ() { return oTranslateZ; }
    public float getOScaleX() { return oScaleX; }
    public float getOScaleY() { return oScaleY; }
    public float getOScaleZ() { return oScaleZ; }

    public int getTickCount() { return tickCount; }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);

        // Notify Dart that this block entity was added to a level
        if (DartBridge.isInitialized()) {
            try {
                DartBridge.onBlockEntitySetLevel(handlerId, blockPosHash);
            } catch (Exception e) {
                LOGGER.error("Error notifying Dart of animated block entity setLevel: {}", e.getMessage());
            }
        }
    }

    @Override
    protected void loadAdditional(ValueInput valueInput) {
        super.loadAdditional(valueInput);
        tickCount = valueInput.getIntOr("TickCount", 0);

        // Load animation state from nested child (for client sync and world loading)
        ValueInput animStateInput = valueInput.childOrEmpty("AnimState");
        loadAnimationStateFromInput(animStateInput);

        // Notify Dart that this block entity was loaded
        if (DartBridge.isInitialized()) {
            try {
                DartBridge.onBlockEntityLoad(handlerId, blockPosHash, "{}");
            } catch (Exception e) {
                LOGGER.error("Error notifying Dart of animated block entity load: {}", e.getMessage());
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput valueOutput) {
        super.saveAdditional(valueOutput);
        valueOutput.putInt("TickCount", tickCount);

        // Save animation state values as nested child (consistent with getUpdateTag)
        if (!stateValues.isEmpty()) {
            ValueOutput animStateOutput = valueOutput.child("AnimState");
            for (Map.Entry<String, AnimationStateValue> entry : stateValues.entrySet()) {
                animStateOutput.putDouble(entry.getKey() + "_target", entry.getValue().target);
                animStateOutput.putDouble(entry.getKey() + "_speed", entry.getValue().speed);
            }
        }
    }

    // ===== Client-Server Sync Methods =====

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Include animation state in the update tag for client sync
        CompoundTag tag = super.getUpdateTag(registries);

        // Serialize stateValues map - for each key, save target and speed
        CompoundTag animState = new CompoundTag();
        for (Map.Entry<String, AnimationStateValue> entry : stateValues.entrySet()) {
            animState.putDouble(entry.getKey() + "_target", entry.getValue().target);
            animState.putDouble(entry.getKey() + "_speed", entry.getValue().speed);
        }

        // Serialize TARGET values for client sync (client will interpolate toward these)
        animState.putFloat("targetRotationX", targetRotationX);
        animState.putFloat("targetRotationY", targetRotationY);
        animState.putFloat("targetRotationZ", targetRotationZ);
        animState.putFloat("targetTranslateX", targetTranslateX);
        animState.putFloat("targetTranslateY", targetTranslateY);
        animState.putFloat("targetTranslateZ", targetTranslateZ);
        animState.putFloat("targetScaleX", targetScaleX);
        animState.putFloat("targetScaleY", targetScaleY);
        animState.putFloat("targetScaleZ", targetScaleZ);
        animState.putFloat("pivotX", pivotX);
        animState.putFloat("pivotY", pivotY);
        animState.putFloat("pivotZ", pivotZ);

        tag.put("AnimState", animState);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        // Send block entity data to clients when block updates
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Load animation state values from a ValueInput child (for client sync and loading).
     * This is called from loadAdditional when the block entity data is received from server.
     */
    private void loadAnimationStateFromInput(ValueInput animStateInput) {
        // The animation config defines which keys we should look for
        loadAnimationConfig();

        // Load values for all known state keys
        for (String stateKey : stateValues.keySet()) {
            AnimationStateValue stateValue = stateValues.get(stateKey);
            stateValue.target = animStateInput.getDoubleOr(stateKey + "_target", stateValue.target);
            stateValue.speed = animStateInput.getDoubleOr(stateKey + "_speed", stateValue.speed);
        }

        // Load TARGET values (client will interpolate current values toward these)
        targetRotationX = animStateInput.getFloatOr("targetRotationX", targetRotationX);
        targetRotationY = animStateInput.getFloatOr("targetRotationY", targetRotationY);
        targetRotationZ = animStateInput.getFloatOr("targetRotationZ", targetRotationZ);
        targetTranslateX = animStateInput.getFloatOr("targetTranslateX", targetTranslateX);
        targetTranslateY = animStateInput.getFloatOr("targetTranslateY", targetTranslateY);
        targetTranslateZ = animStateInput.getFloatOr("targetTranslateZ", targetTranslateZ);
        targetScaleX = animStateInput.getFloatOr("targetScaleX", targetScaleX);
        targetScaleY = animStateInput.getFloatOr("targetScaleY", targetScaleY);
        targetScaleZ = animStateInput.getFloatOr("targetScaleZ", targetScaleZ);
        pivotX = animStateInput.getFloatOr("pivotX", pivotX);
        pivotY = animStateInput.getFloatOr("pivotY", pivotY);
        pivotZ = animStateInput.getFloatOr("pivotZ", pivotZ);

        // Initialize previous values to current for smooth interpolation
        oRotationX = rotationX;
        oRotationY = rotationY;
        oRotationZ = rotationZ;
        oTranslateX = translateX;
        oTranslateY = translateY;
        oTranslateZ = translateZ;
        oScaleX = scaleX;
        oScaleY = scaleY;
        oScaleZ = scaleZ;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();

        // Notify Dart that this block entity was removed
        if (DartBridge.isInitialized()) {
            try {
                DartBridge.onBlockEntityRemoved(handlerId, blockPosHash);
            } catch (Exception e) {
                LOGGER.error("Error notifying Dart of animated block entity removal: {}", e.getMessage());
            }
        }
    }
}
