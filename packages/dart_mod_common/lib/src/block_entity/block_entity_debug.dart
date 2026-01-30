/// Debug introspection support for block entities.
///
/// Provides a common interface for programmatically inspecting and
/// manipulating block entity state during testing.
library;

import '../item/item_stack.dart';
import 'synced_value.dart';

/// Mixin for block entities that expose debug introspection.
///
/// Implement this mixin in block entities that should be accessible
/// via the MCP debug API for testing purposes.
///
/// ## Example
///
/// ```dart
/// class SimpleFurnaceEntity extends ContainerBlockEntity<SimpleFurnaceContainer>
///     with DebuggableBlockEntity {
///   @override
///   List<SyncedInt> get debugSyncedValues => [
///     container.burnProgress,
///     container.maxBurnTime,
///   ];
/// }
/// ```
mixin DebuggableBlockEntity {
  /// Get all synced values exposed for debugging.
  ///
  /// These values can be read and modified via the debug API.
  List<SyncedInt> get debugSyncedValues;

  /// Get the number of inventory slots for debug access.
  int get debugSlotCount;

  /// Get the item in a slot for debug inspection.
  ItemStack debugGetSlot(int index);

  /// Set the item in a slot for debug manipulation.
  void debugSetSlot(int index, ItemStack item);

  /// Convert this block entity's debug state to JSON.
  ///
  /// Used by the MCP debug API to serialize block entity state.
  Map<String, dynamic> toDebugJson() {
    final syncedValues = <Map<String, dynamic>>[];
    for (final value in debugSyncedValues) {
      syncedValues.add(value.toDebugJson());
    }

    final slots = <Map<String, dynamic>>[];
    for (var i = 0; i < debugSlotCount; i++) {
      final stack = debugGetSlot(i);
      slots.add({
        'slot': i,
        'item': stack.isEmpty ? null : stack.item.id,
        'count': stack.count,
      });
    }

    return {
      'syncedValues': syncedValues,
      'inventory': slots,
    };
  }

  /// Set a synced value by name.
  ///
  /// Returns true if successful, false if value not found or validation failed.
  bool debugSetValueByName(String name, int value) {
    for (final syncedValue in debugSyncedValues) {
      if (syncedValue.name == name) {
        // Validate bounds if specified
        if (syncedValue.min != null && value < syncedValue.min!) return false;
        if (syncedValue.max != null && value > syncedValue.max!) return false;
        syncedValue.value = value;
        return true;
      }
    }
    return false;
  }

  /// Set a synced value by index.
  ///
  /// Returns true if successful, false if index not found or validation failed.
  bool debugSetValueByIndex(int index, int value) {
    for (final syncedValue in debugSyncedValues) {
      if (syncedValue.dataSlotIndex == index) {
        // Validate bounds if specified
        if (syncedValue.min != null && value < syncedValue.min!) return false;
        if (syncedValue.max != null && value > syncedValue.max!) return false;
        syncedValue.value = value;
        return true;
      }
    }
    return false;
  }
}
