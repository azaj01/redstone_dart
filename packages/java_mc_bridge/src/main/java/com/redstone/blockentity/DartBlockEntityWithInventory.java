package com.redstone.blockentity;

import com.redstone.DartBridge;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block entity with inventory and ContainerData support.
 *
 * Extends AnimatedBlockEntity with Container and WorldlyContainer implementations
 * for hopper/pipe interaction. By extending AnimatedBlockEntity, all container
 * blocks inherit animation support (even if not used).
 *
 * ContainerData is managed dynamically, calling into Dart via DartBridge for
 * get/set operations. This allows mods to define any processing logic in Dart.
 */
public class DartBlockEntityWithInventory extends AnimatedBlockEntity implements Container, WorldlyContainer, ExtendedScreenHandlerFactory<DartBlockEntityMenu.MenuConfig> {
    private static final Logger LOGGER = LoggerFactory.getLogger("DartBlockEntityWithInventory");

    /** Inventory slots. */
    protected NonNullList<ItemStack> items;

    /** Slot indices for each face direction (for WorldlyContainer). */
    private int[] allSlots;

    /** Display name for the container. */
    protected Component displayName;

    /** Number of data slots for ContainerData synchronization. */
    protected final int dataSlotCount;

    /** ContainerData for syncing state to client GUI. */
    protected final ContainerData containerData;

    public DartBlockEntityWithInventory(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                        int handlerId, int inventorySize, Component displayName,
                                        int dataSlotCount) {
        super(type, pos, state, handlerId);
        this.items = NonNullList.withSize(inventorySize, ItemStack.EMPTY);
        this.displayName = displayName;
        this.dataSlotCount = dataSlotCount;

        // Create array of all slot indices
        this.allSlots = new int[inventorySize];
        for (int i = 0; i < inventorySize; i++) {
            this.allSlots[i] = i;
        }

        // Create ContainerData with dynamic slot count that delegates to Dart
        this.containerData = new ContainerData() {
            @Override
            public int get(int index) {
                if (DartBridge.isInitialized()) {
                    try {
                        return DartBridge.getBlockEntityDataSlot(
                            DartBlockEntityWithInventory.this.handlerId,
                            DartBlockEntityWithInventory.this.blockPosHash,
                            index);
                    } catch (Exception e) {
                        LOGGER.warn("Error getting data slot {}: {}", index, e.getMessage());
                    }
                }
                return 0;
            }

            @Override
            public void set(int index, int value) {
                if (DartBridge.isInitialized()) {
                    try {
                        DartBridge.setBlockEntityDataSlot(
                            DartBlockEntityWithInventory.this.handlerId,
                            DartBlockEntityWithInventory.this.blockPosHash,
                            index,
                            value);
                    } catch (Exception e) {
                        LOGGER.warn("Error setting data slot {}: {}", index, e.getMessage());
                    }
                }
            }

            @Override
            public int getCount() {
                return DartBlockEntityWithInventory.this.dataSlotCount;
            }
        };
    }

    // ========================================================================
    // Container implementation
    // ========================================================================

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= items.size()) {
            return ItemStack.EMPTY;
        }
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            setChanged();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    /**
     * Called when a player opens this container.
     * Notifies Dart to handle the container open event.
     */
    @Override
    public void startOpen(ContainerUser containerUser) {
        if (containerUser instanceof Player player) {
            if (!this.isRemoved() && !player.isSpectator()) {
                if (DartBridge.isInitialized()) {
                    try {
                        DartBridge.onBlockEntityContainerOpen(handlerId, blockPosHash);
                    } catch (Exception e) {
                        LOGGER.error("Error notifying Dart of container open: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Called when a player closes this container.
     * Notifies Dart to handle the container close event.
     */
    @Override
    public void stopOpen(ContainerUser containerUser) {
        if (containerUser instanceof Player player) {
            if (!this.isRemoved() && !player.isSpectator()) {
                if (DartBridge.isInitialized()) {
                    try {
                        DartBridge.onBlockEntityContainerClose(handlerId, blockPosHash);
                    } catch (Exception e) {
                        LOGGER.error("Error notifying Dart of container close: {}", e.getMessage());
                    }
                }
            }
        }
    }

    // ========================================================================
    // WorldlyContainer implementation (for hopper interaction)
    // ========================================================================

    @Override
    public int[] getSlotsForFace(Direction side) {
        // By default, allow access to all slots from any direction
        return allSlots;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return true;
    }

    /**
     * Check if an item can be placed in a specific slot.
     * Framework default: allow all items in all slots.
     * Subclasses or Dart callbacks can override for slot-specific logic.
     *
     * @param slot The slot index
     * @param stack The item stack to check
     * @return true if the item can be placed in the slot
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        // Default implementation allows all items in all slots
        // TODO: Could delegate to Dart via DartBridge callback if needed
        return true;
    }

    // ========================================================================
    // ContainerData support
    // ========================================================================

    /**
     * Get the ContainerData for menu synchronization.
     */
    public ContainerData getContainerData() {
        return containerData;
    }

    /**
     * Get the number of data slots.
     */
    public int getDataSlotCount() {
        return dataSlotCount;
    }

    // ========================================================================
    // ExtendedScreenHandlerFactory implementation
    // ========================================================================

    @Override
    public Component getDisplayName() {
        return displayName;
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new DartBlockEntityMenu(containerId, playerInventory, this, this.containerData);
    }

    /**
     * Provides the MenuConfig data to be sent to the client when opening this container.
     * This is required by ExtendedScreenHandlerFactory to properly serialize
     * the inventory size and data slot count for client-side menu construction.
     *
     * @param player The server player opening the menu
     * @return MenuConfig containing inventory size and data slot count
     */
    @Override
    public DartBlockEntityMenu.MenuConfig getScreenOpeningData(ServerPlayer player) {
        return new DartBlockEntityMenu.MenuConfig(getContainerSize(), dataSlotCount);
    }

    // ========================================================================
    // Tick handling
    // ========================================================================

    /**
     * Server tick method - called each game tick for this block entity.
     * Delegates to Dart for processing logic.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  DartBlockEntityWithInventory blockEntity) {
        if (level.isClientSide()) {
            return;
        }

        if (DartBridge.isInitialized()) {
            try {
                DartBridge.onBlockEntityTick(blockEntity.handlerId, blockEntity.blockPosHash);
            } catch (Exception e) {
                LOGGER.error("Error during block entity tick: {}", e.getMessage());
            }
        }
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    @Override
    protected void loadAdditional(ValueInput valueInput) {
        super.loadAdditional(valueInput);

        // Load inventory
        this.items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(valueInput, this.items);
    }

    @Override
    protected void saveAdditional(ValueOutput valueOutput) {
        super.saveAdditional(valueOutput);

        // Save inventory
        ContainerHelper.saveAllItems(valueOutput, this.items);
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Get the items list directly.
     */
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    /**
     * Set the items list directly.
     */
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }
}
