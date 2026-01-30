package com.redstone.blockentity;

import com.redstone.proxy.DartBlockProxy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block that has an associated block entity.
 *
 * Extends DartBlockProxy with EntityBlock implementation to support
 * block entities with inventory and processing capabilities.
 */
public class DartBlockWithEntity extends DartBlockProxy implements EntityBlock {
    private static final Logger LOGGER = LoggerFactory.getLogger("DartBlockWithEntity");

    /** Handler ID for the block entity type (used when creating block entities). */
    private final int blockEntityHandlerId;

    /** Number of inventory slots. */
    private final int inventorySize;

    /** Display name for the container. */
    private final Component containerTitle;

    /** The full block ID (namespace:path) for looking up our BlockEntityType. */
    private final String blockId;

    /** Cached BlockEntityType for this block. */
    private BlockEntityType<DartBlockEntityWithInventory> blockEntityType;

    /** Whether this block has an animation. */
    private final boolean hasAnimation;

    public DartBlockWithEntity(Properties settings, long dartHandlerId, Object blockSettings,
                               int blockEntityHandlerId, int inventorySize, String containerTitle,
                               String blockId) {
        super(settings, dartHandlerId, blockSettings);
        this.blockEntityHandlerId = blockEntityHandlerId;
        this.inventorySize = inventorySize;
        this.containerTitle = Component.literal(containerTitle);
        this.blockId = blockId;
        // Check if this block also has animation
        this.hasAnimation = AnimationRegistry.hasAnimation(blockId);
    }

    /**
     * Get the BlockEntityType for this block.
     * Caches the result after first lookup.
     */
    private BlockEntityType<DartBlockEntityWithInventory> getBlockEntityType() {
        if (blockEntityType == null) {
            blockEntityType = DartBlockEntityType.getType(blockId);
            if (blockEntityType == null) {
                LOGGER.error("No BlockEntityType registered for block {}", blockId);
            }
        }
        return blockEntityType;
    }

    // ========================================================================
    // EntityBlock implementation
    // ========================================================================

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<DartBlockEntityWithInventory> type = getBlockEntityType();
        if (type == null) {
            LOGGER.error("Cannot create block entity: no BlockEntityType for {}", blockId);
            return null;
        }

        // Get dataSlotCount from the block entity config
        BlockEntityRegistry.BlockEntityConfig config = BlockEntityRegistry.getConfig(blockId);
        int dataSlotCount = config != null ? config.dataSlotCount() : 0;

        return new DartBlockEntityWithInventory(
            type,
            pos,
            state,
            blockEntityHandlerId,
            inventorySize,
            containerTitle,
            dataSlotCount
        );
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                             BlockEntityType<T> type) {
        BlockEntityType<DartBlockEntityWithInventory> ourType = getBlockEntityType();
        if (ourType == null || type != ourType) {
            return null;
        }

        // For animated containers, we need both animation ticking AND server logic ticking
        // Since DartBlockEntityWithInventory extends AnimatedBlockEntity,
        // we can tick it as an AnimatedBlockEntity for animation updates.
        if (hasAnimation) {
            // Check if server ticks are enabled for block entity logic
            BlockEntityRegistry.BlockEntityConfig config = BlockEntityRegistry.getConfig(blockId);
            boolean needsServerTick = config != null && config.ticks();

            if (level.isClientSide()) {
                // Client: only animation tick
                return (BlockEntityTicker<T>) (lvl, pos, st, be) ->
                    AnimatedBlockEntity.tick(lvl, pos, st, (AnimatedBlockEntity) be);
            } else if (needsServerTick) {
                // Server: both animation tick AND server logic tick
                return (BlockEntityTicker<T>) (lvl, pos, st, be) -> {
                    AnimatedBlockEntity.tick(lvl, pos, st, (AnimatedBlockEntity) be);
                    DartBlockEntityWithInventory.serverTick(lvl, pos, st, (DartBlockEntityWithInventory) be);
                };
            } else {
                // Server: only animation tick (no server logic needed)
                return (BlockEntityTicker<T>) (lvl, pos, st, be) ->
                    AnimatedBlockEntity.tick(lvl, pos, st, (AnimatedBlockEntity) be);
            }
        }

        // Non-animated: only server-side tick
        if (level.isClientSide()) {
            return null;
        }

        BlockEntityRegistry.BlockEntityConfig config = BlockEntityRegistry.getConfig(blockId);
        if (config == null || !config.ticks()) {
            return null;
        }

        return (BlockEntityTicker<T>) (lvl, pos, st, be) ->
            DartBlockEntityWithInventory.serverTick(lvl, pos, st, (DartBlockEntityWithInventory) be);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // Use INVISIBLE for animated blocks to prevent double-rendering
        if (hasAnimation) {
            return RenderShape.INVISIBLE;
        }
        return super.getRenderShape(state);
    }

    // ========================================================================
    // Block lifecycle
    // ========================================================================

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        // Drop inventory contents when block is broken
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof DartBlockEntityWithInventory container) {
            Containers.dropContents(level, pos, container);
        }

        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        // On server side, try to open the container menu
        if (!level.isClientSide()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof MenuProvider menuProvider) {
                player.openMenu(menuProvider);
                return InteractionResult.CONSUME;
            }
        }

        return InteractionResult.SUCCESS;
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public int getBlockEntityHandlerId() {
        return blockEntityHandlerId;
    }

    public int getInventorySize() {
        return inventorySize;
    }

    public Component getContainerTitle() {
        return containerTitle;
    }
}
