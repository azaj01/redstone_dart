/// API for defining custom dimensions in Dart.
library;

/// Configuration for registering a custom dimension.
///
/// Custom dimensions are data-driven and registered via data packs.
/// The registry writes dimension and dimension_type JSON files
/// during datagen.
///
/// Example:
/// ```dart
/// DimensionRegistry.register(CustomDimension(
///   id: 'mymod:mining_dimension',
///   type: CustomDimensionType(
///     hasSkylight: true,
///     hasCeiling: true,
///     natural: false,
///     height: 128,
///     minY: 0,
///   ),
///   generator: FlatGenerator(
///     layers: [
///       FlatLayer('minecraft:bedrock', 1),
///       FlatLayer('minecraft:deepslate', 60),
///       FlatLayer('minecraft:stone', 30),
///     ],
///     biome: 'minecraft:plains',
///   ),
/// ));
/// ```
class CustomDimension {
  /// Full ID like "mymod:my_dimension".
  final String id;

  /// The dimension type configuration.
  final CustomDimensionType type;

  /// The world generator configuration.
  final DimensionGenerator generator;

  const CustomDimension({
    required this.id,
    required this.type,
    this.generator = const VoidGenerator(),
  });
}

/// Configuration for a Minecraft dimension type.
///
/// This maps to MC's `dimension_type` JSON and defines the physical
/// characteristics of the dimension. Different from the runtime
/// [DimensionProperties] which is for reading properties at runtime.
class CustomDimensionType {
  final bool hasSkylight;
  final bool hasCeiling;
  final bool ultrawarm;
  final bool natural;
  final double coordinateScale;
  final bool bedWorks;
  final bool respawnAnchorWorks;
  final int minY;
  final int height;
  final int logicalHeight;
  final String infiniburn;
  final String effects;
  final double ambientLight;
  final bool piglinSafe;
  final bool hasRaids;
  final int monsterSpawnLightLevel;
  final int monsterSpawnBlockLightLimit;

  const CustomDimensionType({
    this.hasSkylight = true,
    this.hasCeiling = false,
    this.ultrawarm = false,
    this.natural = true,
    this.coordinateScale = 1.0,
    this.bedWorks = true,
    this.respawnAnchorWorks = false,
    this.minY = -64,
    this.height = 384,
    this.logicalHeight = 384,
    this.infiniburn = '#minecraft:infiniburn_overworld',
    this.effects = 'minecraft:overworld',
    this.ambientLight = 0.0,
    this.piglinSafe = false,
    this.hasRaids = true,
    this.monsterSpawnLightLevel = 0,
    this.monsterSpawnBlockLightLimit = 0,
  });

  /// Preset: Overworld-like dimension type.
  static const overworld = CustomDimensionType();

  /// Preset: Nether-like dimension type.
  static const nether = CustomDimensionType(
    hasSkylight: false,
    hasCeiling: true,
    ultrawarm: true,
    natural: false,
    coordinateScale: 8.0,
    bedWorks: false,
    respawnAnchorWorks: true,
    minY: 0,
    height: 256,
    logicalHeight: 128,
    infiniburn: '#minecraft:infiniburn_nether',
    effects: 'minecraft:the_nether',
    ambientLight: 0.1,
    piglinSafe: true,
    hasRaids: false,
  );

  /// Preset: End-like dimension type.
  static const end = CustomDimensionType(
    hasSkylight: false,
    natural: false,
    bedWorks: false,
    minY: 0,
    height: 256,
    logicalHeight: 256,
    infiniburn: '#minecraft:infiniburn_end',
    effects: 'minecraft:the_end',
    hasRaids: false,
  );

  Map<String, dynamic> toJson() => {
        'has_skylight': hasSkylight,
        'has_ceiling': hasCeiling,
        'ultrawarm': ultrawarm,
        'natural': natural,
        'coordinate_scale': coordinateScale,
        'bed_works': bedWorks,
        'respawn_anchor_works': respawnAnchorWorks,
        'min_y': minY,
        'height': height,
        'logical_height': logicalHeight,
        'infiniburn': infiniburn,
        'effects': effects,
        'ambient_light': ambientLight,
        'piglin_safe': piglinSafe,
        'has_raids': hasRaids,
        'monster_spawn_light_level': monsterSpawnLightLevel,
        'monster_spawn_block_light_limit': monsterSpawnBlockLightLimit,
      };
}

// =============================================================================
// Dimension Generators
// =============================================================================

/// Base class for dimension generators.
abstract class DimensionGenerator {
  const DimensionGenerator();

  Map<String, dynamic> toJson();
}

/// Void world generator — empty flat world with no layers.
class VoidGenerator extends DimensionGenerator {
  final String biome;

  const VoidGenerator({this.biome = 'minecraft:the_void'});

  @override
  Map<String, dynamic> toJson() => {
        'type': 'minecraft:flat',
        'settings': {
          'layers': <Map<String, dynamic>>[],
          'biome': biome,
        },
      };
}

/// Flat world generator with configurable layers.
class FlatGenerator extends DimensionGenerator {
  final List<FlatLayer> layers;
  final String biome;

  const FlatGenerator({
    this.layers = const [
      FlatLayer('minecraft:bedrock', 1),
      FlatLayer('minecraft:stone', 63),
    ],
    this.biome = 'minecraft:plains',
  });

  @override
  Map<String, dynamic> toJson() => {
        'type': 'minecraft:flat',
        'settings': {
          'layers': layers.map((l) => l.toJson()).toList(),
          'biome': biome,
        },
      };
}

/// A single layer in a flat world generator.
class FlatLayer {
  final String block;
  final int height;

  const FlatLayer(this.block, this.height);

  Map<String, dynamic> toJson() => {'block': block, 'height': height};
}

/// Noise-based generation (like overworld, nether, or end).
class NoiseGenerator extends DimensionGenerator {
  /// Noise settings preset: 'minecraft:overworld', 'minecraft:nether',
  /// 'minecraft:end', etc.
  final String settings;

  /// Biome source type: 'minecraft:multi_noise', 'minecraft:the_end', etc.
  final String biomeSource;

  const NoiseGenerator({
    this.settings = 'minecraft:overworld',
    this.biomeSource = 'minecraft:multi_noise',
  });

  @override
  Map<String, dynamic> toJson() => {
        'type': 'minecraft:noise',
        'settings': settings,
        'biome_source': {'type': biomeSource},
      };
}
