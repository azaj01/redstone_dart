/// Settings classes for block entities.
library;

/// Base settings for all block entities.
///
/// This class provides the core configuration needed to register a block entity
/// with the Minecraft/Java bridge. Block entities are special blocks that can
/// store additional data (like inventories, processing state, etc.) beyond
/// what the block state provides.
///
/// ## ID Format
///
/// The [id] must follow Minecraft's resource location format: `namespace:path`
///
/// - **namespace**: Your mod's identifier (e.g., `my_mod`, `redstone`)
/// - **path**: The specific block entity name (e.g., `furnace`, `chest`)
///
/// Examples:
/// - `my_mod:custom_furnace`
/// - `redstone:dart_container`
/// - `minecraft:chest` (vanilla format, but use your own namespace)
///
/// ## Usage
///
/// ```dart
/// final settings = BlockEntitySettings(id: 'my_mod:my_furnace');
/// ```
///
/// For block entities with inventories, use [BlockEntityWithInventorySettings].
/// For block entities with data synchronization, use [BlockEntityWithContainerDataSettings].
class BlockEntitySettings {
  /// The unique identifier for this block entity type.
  ///
  /// Must be in the format `namespace:path` (e.g., `my_mod:my_furnace`).
  /// This ID is used to:
  /// - Register the block entity type with Minecraft
  /// - Look up the block entity type when creating instances
  /// - Serialize/deserialize block entity data
  final String id;

  const BlockEntitySettings({required this.id});
}
