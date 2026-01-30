/// Container definition for the ExampleFurnace block entity.
library;

import 'package:dart_mod_common/dart_mod_common.dart';

/// Container definition for the ExampleFurnace block.
///
/// This defines the structure of the furnace menu with 3 slots
/// (input, fuel, output) and 4 synced values for client UI updates.
///
/// Matches the server-side ExampleFurnace (ContainerBlockEntity).
class ExampleFurnaceContainer extends ContainerDefinition {
  @override
  String get id => 'example_mod:example_furnace';

  @override
  int get slotCount => 3; // input, fuel, output

  // Slot indices (matching SlotConfig.furnace())
  static const int inputSlot = 0;
  static const int fuelSlot = 1;
  static const int outputSlot = 2;

  // Synced data (matching ContainerDefinition pattern)
  /// Current burn time remaining (ticks). Index 0.
  final SyncedInt litTime = SyncedInt();

  /// Total burn time of current fuel (ticks). Index 1.
  final SyncedInt litDuration = SyncedInt();

  /// Current cooking progress (ticks). Index 2.
  final SyncedInt cookingProgress = SyncedInt();

  /// Total cooking time for the current recipe (ticks). Index 3.
  final SyncedInt cookingTotalTime = SyncedInt();

  ExampleFurnaceContainer() {
    syncedValues([litTime, litDuration, cookingProgress, cookingTotalTime]);
  }

  /// Is the furnace currently burning fuel?
  bool get isLit => litTime.value > 0;

  /// Burn progress as 0.0-1.0 (for UI fire animation).
  double get burnProgress =>
      litDuration.value > 0 ? litTime.value / litDuration.value : 0.0;

  /// Cook progress as 0.0-1.0 (for UI arrow animation).
  double get cookProgress => cookingTotalTime.value > 0
      ? cookingProgress.value / cookingTotalTime.value
      : 0.0;
}
