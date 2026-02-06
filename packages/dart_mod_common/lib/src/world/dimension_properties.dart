/// Properties of a Minecraft dimension type.
library;

import 'dart:convert';

/// Runtime properties of a Minecraft dimension type.
///
/// These are read-only values that describe the physical characteristics
/// of a dimension (e.g., whether it has a sky, ceiling height, etc.).
class DimensionProperties {
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
  final double ambientLight;
  final bool piglinSafe;
  final bool hasRaids;

  const DimensionProperties({
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
    this.ambientLight = 0.0,
    this.piglinSafe = false,
    this.hasRaids = true,
  });

  factory DimensionProperties.fromJson(Map<String, dynamic> json) {
    return DimensionProperties(
      hasSkylight: json['hasSkylight'] as bool? ?? true,
      hasCeiling: json['hasCeiling'] as bool? ?? false,
      ultrawarm: json['ultrawarm'] as bool? ?? false,
      natural: json['natural'] as bool? ?? true,
      coordinateScale: (json['coordinateScale'] as num?)?.toDouble() ?? 1.0,
      bedWorks: json['bedWorks'] as bool? ?? true,
      respawnAnchorWorks: json['respawnAnchorWorks'] as bool? ?? false,
      minY: json['minY'] as int? ?? -64,
      height: json['height'] as int? ?? 384,
      logicalHeight: json['logicalHeight'] as int? ?? 384,
      ambientLight: (json['ambientLight'] as num?)?.toDouble() ?? 0.0,
      piglinSafe: json['piglinSafe'] as bool? ?? false,
      hasRaids: json['hasRaids'] as bool? ?? true,
    );
  }

  /// Parse from a JSON string returned by the Java bridge.
  static DimensionProperties? fromJsonString(String? jsonString) {
    if (jsonString == null || jsonString.isEmpty) return null;
    final map = jsonDecode(jsonString) as Map<String, dynamic>;
    return DimensionProperties.fromJson(map);
  }

  Map<String, dynamic> toJson() => {
        'hasSkylight': hasSkylight,
        'hasCeiling': hasCeiling,
        'ultrawarm': ultrawarm,
        'natural': natural,
        'coordinateScale': coordinateScale,
        'bedWorks': bedWorks,
        'respawnAnchorWorks': respawnAnchorWorks,
        'minY': minY,
        'height': height,
        'logicalHeight': logicalHeight,
        'ambientLight': ambientLight,
        'piglinSafe': piglinSafe,
        'hasRaids': hasRaids,
      };

  @override
  String toString() => 'DimensionProperties(${toJson()})';
}
