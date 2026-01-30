/// Server-side example furnace block entity with smelting logic.
library;

import 'package:dart_mod_server/dart_mod_server.dart';
import 'package:example_mod_common/example_mod_common.dart';

/// Example furnace block entity demonstrating the Container API.
///
/// This furnace can smelt common ores and accepts standard fuels.
/// It uses the new Container API pattern:
/// - [ExampleFurnaceContainer] for synced data (shared with client)
/// - [ContainerBlockEntity] for server-side inventory and ticking
class ExampleFurnace extends ContainerBlockEntity<ExampleFurnaceContainer> {
  ExampleFurnace() : super(container: ExampleFurnaceContainer());

  @override
  void serverTick() {
    // Check if we can smelt
    final input = getSlot(ExampleFurnaceContainer.inputSlot);
    final fuel = getSlot(ExampleFurnaceContainer.fuelSlot);
    final output = getSlot(ExampleFurnaceContainer.outputSlot);

    final wasLit = container.isLit;
    var changed = false;

    // Burn down fuel
    if (container.litTime.value > 0) {
      container.litTime.value--;
      changed = true;
    }

    // Try to consume fuel if we have input and no lit time
    if (!container.isLit && input.isNotEmpty && canSmelt(input)) {
      if (fuel.isNotEmpty && isFuel(fuel)) {
        // Consume fuel
        container.litDuration.value = getBurnTime(fuel);
        container.litTime.value = container.litDuration.value;
        _consumeItem(ExampleFurnaceContainer.fuelSlot, 1);
        changed = true;
      }
    }

    // Progress cooking if lit and have smeltable input
    if (container.isLit && input.isNotEmpty && canSmelt(input)) {
      container.cookingProgress.value++;
      container.cookingTotalTime.value = 200; // 10 seconds (200 ticks)

      if (container.cookingProgress.value >= container.cookingTotalTime.value) {
        // Smelting complete
        final result = getSmeltResult(input);
        if (result != null && _canAcceptOutput(result, output)) {
          // Add to output
          if (output.isEmpty) {
            setSlot(ExampleFurnaceContainer.outputSlot, result);
          } else {
            // Stack if same item
            _incrementItem(ExampleFurnaceContainer.outputSlot, 1);
          }
          _consumeItem(ExampleFurnaceContainer.inputSlot, 1);
        }
        container.cookingProgress.value = 0;
      }
      changed = true;
    } else if (!container.isLit && container.cookingProgress.value > 0) {
      // Lose progress if no fuel
      container.cookingProgress.value = 0;
      changed = true;
    }

    if (changed || wasLit != container.isLit) {
      _markDirty();
    }
  }

  /// Check if the output slot can accept the result.
  bool _canAcceptOutput(ItemStack result, ItemStack output) {
    if (output.isEmpty) return true;
    if (output.item.id != result.item.id) return false;
    return output.count + result.count <= 64;
  }

  /// Consume items from a slot.
  void _consumeItem(int slot, int count) {
    final current = getSlot(slot);
    if (current.isEmpty) return;

    final newCount = current.count - count;
    if (newCount <= 0) {
      setSlot(slot, ItemStack.empty);
    } else {
      setSlot(slot, current.copyWith(count: newCount));
    }
  }

  /// Increment item count in a slot.
  void _incrementItem(int slot, int count) {
    final current = getSlot(slot);
    if (current.isEmpty) return;

    setSlot(slot, current.copyWith(count: current.count + count));
  }

  /// Mark the block entity as dirty (needs save).
  void _markDirty() {
    // Block entity state changed - framework will handle persistence
  }

  /// Check if an item can be smelted.
  bool canSmelt(ItemStack item) {
    return getSmeltResult(item) != null;
  }

  /// Check if an item is valid fuel.
  bool isFuel(ItemStack item) {
    return getBurnTime(item) > 0;
  }

  /// Get the burn time for a fuel item in ticks.
  int getBurnTime(ItemStack item) {
    final id = item.item.id;

    // High-value fuels
    if (id == 'minecraft:lava_bucket') return 20000;
    if (id == 'minecraft:coal_block') return 16000;
    if (id == 'minecraft:dried_kelp_block') return 4001;
    if (id == 'minecraft:blaze_rod') return 2400;

    // Standard fuels
    if (id == 'minecraft:coal' || id == 'minecraft:charcoal') return 1600;

    // Wooden items
    if (id.contains('_log') || id.contains('_wood')) return 300;
    if (id.contains('_planks')) return 300;
    if (id.contains('_slab')) return 150;
    if (id.contains('_stairs')) return 300;
    if (id.contains('_fence')) return 300;
    if (id == 'minecraft:stick') return 100;

    return 0;
  }

  /// Get the result of smelting an input item.
  ///
  /// Returns null if the item cannot be smelted.
  ItemStack? getSmeltResult(ItemStack input) {
    final id = input.item.id;

    // Simple smelting recipes
    final recipes = {
      'minecraft:iron_ore': 'minecraft:iron_ingot',
      'minecraft:deepslate_iron_ore': 'minecraft:iron_ingot',
      'minecraft:gold_ore': 'minecraft:gold_ingot',
      'minecraft:deepslate_gold_ore': 'minecraft:gold_ingot',
      'minecraft:copper_ore': 'minecraft:copper_ingot',
      'minecraft:deepslate_copper_ore': 'minecraft:copper_ingot',
      'minecraft:raw_iron': 'minecraft:iron_ingot',
      'minecraft:raw_gold': 'minecraft:gold_ingot',
      'minecraft:raw_copper': 'minecraft:copper_ingot',
      'minecraft:cobblestone': 'minecraft:stone',
      'minecraft:sand': 'minecraft:glass',
      'minecraft:clay_ball': 'minecraft:brick',
      'minecraft:netherrack': 'minecraft:nether_brick',
      'minecraft:wet_sponge': 'minecraft:sponge',
      // Food
      'minecraft:beef': 'minecraft:cooked_beef',
      'minecraft:porkchop': 'minecraft:cooked_porkchop',
      'minecraft:chicken': 'minecraft:cooked_chicken',
      'minecraft:mutton': 'minecraft:cooked_mutton',
      'minecraft:rabbit': 'minecraft:cooked_rabbit',
      'minecraft:cod': 'minecraft:cooked_cod',
      'minecraft:salmon': 'minecraft:cooked_salmon',
      'minecraft:potato': 'minecraft:baked_potato',
      'minecraft:kelp': 'minecraft:dried_kelp',
    };

    final result = recipes[id];
    if (result != null) {
      return ItemStack.of(result);
    }
    return null;
  }
}
