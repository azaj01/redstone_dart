/// Registry for custom dimensions.
///
/// Custom dimensions are data-driven (no JNI registration needed at runtime).
/// The registry stores registered dimensions and writes them to the manifest
/// so the CLI can generate the data pack JSON files.
library;

import 'dart:convert';
import 'dart:io';

import 'package:dart_mod_common/src/world/custom_dimension.dart';

/// Registry for custom dimensions.
///
/// Dimensions are data-driven and loaded from data packs, so no native
/// bridge registration is needed. The registry writes to the manifest
/// for the CLI asset generator to create the data pack files.
///
/// Example:
/// ```dart
/// DimensionRegistry.register(CustomDimension(
///   id: 'mymod:mining_dimension',
///   type: CustomDimensionType.overworld,
///   generator: VoidGenerator(),
/// ));
/// ```
class DimensionRegistry {
  static final List<CustomDimension> _dimensions = [];

  /// Register a custom dimension.
  ///
  /// The dimension will be written to the manifest for datagen.
  /// The CLI asset generator will create:
  /// - `data/<namespace>/dimension/<path>.json`
  /// - `data/<namespace>/dimension_type/<path>.json`
  static void register(CustomDimension dimension) {
    // Validate ID format
    final parts = dimension.id.split(':');
    if (parts.length != 2) {
      throw ArgumentError(
        'Invalid dimension ID: ${dimension.id}. Must be namespace:path',
      );
    }

    // Check for duplicates
    if (_dimensions.any((d) => d.id == dimension.id)) {
      throw StateError('Dimension ${dimension.id} is already registered');
    }

    _dimensions.add(dimension);
    _writeToManifest();

    print('DimensionRegistry: Registered ${dimension.id}');
  }

  /// Get all registered dimensions.
  static List<CustomDimension> get registeredDimensions =>
      List.unmodifiable(_dimensions);

  /// Write all dimensions to the manifest for CLI datagen.
  static void _writeToManifest() {
    // Read existing manifest
    Map<String, dynamic> manifest = {};
    final manifestFile = File('.redstone/manifest.json');
    if (manifestFile.existsSync()) {
      try {
        manifest =
            jsonDecode(manifestFile.readAsStringSync()) as Map<String, dynamic>;
      } catch (_) {
        // Ignore parse errors
      }
    }

    // Build dimensions list
    final dimensionEntries = <Map<String, dynamic>>[];
    for (final dim in _dimensions) {
      dimensionEntries.add({
        'id': dim.id,
        'type': dim.type.toJson(),
        'generator': dim.generator.toJson(),
      });
    }

    manifest['dimensions'] = dimensionEntries;

    // Create .redstone directory if it doesn't exist
    final redstoneDir = Directory('.redstone');
    if (!redstoneDir.existsSync()) {
      redstoneDir.createSync(recursive: true);
    }

    // Write manifest with pretty formatting
    final encoder = JsonEncoder.withIndent('  ');
    manifestFile.writeAsStringSync(encoder.convert(manifest));

    print(
        'DimensionRegistry: Wrote ${dimensionEntries.length} dimension(s) to manifest');
  }
}
