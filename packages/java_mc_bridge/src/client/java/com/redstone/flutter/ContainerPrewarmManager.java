package com.redstone.flutter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import com.redstone.DartBridgeClient;
import com.redstone.blockentity.DartBlockEntityWithInventory;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages pre-warming of Flutter container screens for instant opening.
 *
 * When a player LOOKS at a container block (before clicking), this manager
 * triggers Flutter to start rendering the container UI in the background.
 * When the player actually opens the container, the frame is already ready
 * for instant display - matching vanilla Minecraft's responsiveness.
 *
 * This is called every client tick from DartModClientLoader.
 */
@Environment(EnvType.CLIENT)
public class ContainerPrewarmManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ContainerPrewarmManager");

    /** Last block position we were looking at (for detecting changes). */
    private static BlockPos lastLookedAtContainer = null;

    /** Container ID of the pre-warmed screen. */
    private static String prewarmedContainerId = null;

    /** Whether the pre-warmed frame is ready. */
    private static boolean prewarmedFrameReady = false;

    /** Minimum time to wait before checking for new frame (avoid excessive checks). */
    private static int ticksSincePrewarm = 0;

    /**
     * Called every client tick to check what the player is looking at.
     * If looking at a container block, trigger pre-warm.
     */
    public static void tick() {
        // Don't run if client isn't initialized
        if (!DartBridgeClient.isClientInitialized()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Don't pre-warm while a screen is already open
        if (mc.screen != null) {
            return;
        }

        HitResult hitResult = mc.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            // Not looking at a block - clear prewarm state
            if (lastLookedAtContainer != null) {
                clearPrewarmState();
            }
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos pos = blockHit.getBlockPos();

        // Check if it's the same block we're already pre-warming
        if (pos.equals(lastLookedAtContainer)) {
            // Increment tick counter
            ticksSincePrewarm++;

            // Check if frame became ready (after a short delay to avoid excessive checks)
            if (!prewarmedFrameReady && ticksSincePrewarm >= 2) {
                if (DartBridgeClient.hasNewFrame()) {
                    prewarmedFrameReady = true;
                    LOGGER.info("[ContainerPrewarm] Frame ready for container '{}' at {}", prewarmedContainerId, pos);
                }
            }
            return;
        }

        // Check if this is a container block entity
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (blockEntity instanceof DartBlockEntityWithInventory dartContainer) {
            // New container to pre-warm!
            lastLookedAtContainer = pos;
            prewarmedFrameReady = false;
            ticksSincePrewarm = 0;

            // Get container display name - we'll use this as the ID for matching
            // (FlutterContainerScreen uses DartBridge.getContainerIdByTitle as fallback)
            prewarmedContainerId = dartContainer.getDisplayName().getString();

            LOGGER.info("[ContainerPrewarm] Pre-warming container with title '{}' at {}", prewarmedContainerId, pos);

            // Get screen dimensions
            int width = mc.getWindow().getGuiScaledWidth();
            int height = mc.getWindow().getGuiScaledHeight();
            int guiScale = (int) mc.getWindow().getGuiScale();

            // Trigger Flutter to pre-render this container's UI
            DartBridgeClient.prewarmContainerScreen(prewarmedContainerId, width, height, guiScale);
        } else {
            // Not a container - clear state
            if (lastLookedAtContainer != null) {
                clearPrewarmState();
            }
        }
    }

    /**
     * Clear the pre-warm state.
     */
    private static void clearPrewarmState() {
        lastLookedAtContainer = null;
        prewarmedContainerId = null;
        prewarmedFrameReady = false;
        ticksSincePrewarm = 0;
    }

    /**
     * Check if a container screen is pre-warmed and ready.
     *
     * @param containerId The container type ID to check
     * @return true if the container is pre-warmed and the frame is ready
     */
    public static boolean isPrewarmed(String containerId) {
        return prewarmedFrameReady && containerId != null && containerId.equals(prewarmedContainerId);
    }

    /**
     * Check if ANY container screen is pre-warmed and ready.
     * Used when container ID is not available at check time.
     *
     * @return true if any container frame is pre-warmed and ready
     */
    public static boolean isAnyPrewarmed() {
        return prewarmedFrameReady && prewarmedContainerId != null;
    }

    /**
     * Get the pre-warmed container ID.
     *
     * @return The container ID, or null if nothing is pre-warmed
     */
    public static String getPrewarmedContainerId() {
        return prewarmedContainerId;
    }

    /**
     * Clear prewarm state (called when screen actually opens).
     */
    public static void clearPrewarm() {
        LOGGER.info("[ContainerPrewarm] Clearing pre-warm state (container opened)");
        clearPrewarmState();
    }
}
