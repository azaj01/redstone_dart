/// Animated chest block demonstrating stateful animations.
library;

import 'package:dart_mod_server/dart_mod_server.dart';

import 'animated_chest_entity.dart';

/// A chest block with an animated lid that opens and closes.
///
/// Demonstrates the `BlockAnimation.stateful()` API with per-element animation:
/// - Body (element 0): static, does not move
/// - Lid (element 1): rotates -90 degrees around X axis when opened
/// - Latch (element 2): rotates with the lid
///
/// Per-element animation is achieved by setting `animated: false` on elements
/// that should remain static. By default, elements have `animated: true`.
///
/// ## Example Usage
///
/// Place the block, then right-click to open. The lid and latch animate
/// smoothly open and closed while the body stays in place.
class AnimatedChestBlock extends CustomBlock {
  /// The block ID for the animated chest.
  static const String blockId = 'example_mod:animated_chest';

  /// Singleton instance.
  static final AnimatedChestBlock instance = AnimatedChestBlock._();

  AnimatedChestBlock._()
      : super(
          id: blockId,
          settings: const BlockSettings(
            hardness: 2.5,
            resistance: 2.5,
            requiresTool: false,
          ),
          // Custom chest model with per-element animation:
          // - Body: static (animated: false)
          // - Lid & Latch: animated (animated: true, the default)
          model: BlockModel.elements(
            textures: {
              'chest': 'assets/textures/block/animated_chest.png',
              'particle': 'assets/textures/block/animated_chest.png',
            },
            elements: [
              // Element 0: Body (lower part of chest) - STATIC
              BlockElement(
                name: 'body',
                animated: false, // Body stays in place when lid opens
                from: const ModelVec3(1, 0, 1),
                to: const ModelVec3(15, 10, 15),
                faces: {
                  Direction.north: const ElementFace(texture: 'chest'),
                  Direction.south: const ElementFace(texture: 'chest'),
                  Direction.east: const ElementFace(texture: 'chest'),
                  Direction.west: const ElementFace(texture: 'chest'),
                  Direction.up: const ElementFace(texture: 'chest'),
                  Direction.down: const ElementFace(
                    texture: 'chest',
                    cullface: Direction.down,
                  ),
                },
              ),
              // Element 1: Lid (top part of chest) - ANIMATED
              BlockElement(
                name: 'lid',
                // animated: true is the default, so we can omit it
                from: const ModelVec3(1, 10, 1),
                to: const ModelVec3(15, 14, 15),
                faces: {
                  Direction.north: const ElementFace(texture: 'chest'),
                  Direction.south: const ElementFace(texture: 'chest'),
                  Direction.east: const ElementFace(texture: 'chest'),
                  Direction.west: const ElementFace(texture: 'chest'),
                  Direction.up: const ElementFace(texture: 'chest'),
                  Direction.down: const ElementFace(texture: 'chest'),
                },
              ),
              // Element 2: Latch (small protrusion on front) - ANIMATED with lid
              BlockElement(
                name: 'latch',
                // animated: true by default, rotates with the lid
                from: const ModelVec3(7, 7, 0),
                to: const ModelVec3(9, 11, 1),
                faces: {
                  Direction.north: const ElementFace(
                    texture: 'chest',
                    cullface: Direction.north,
                  ),
                  Direction.south: const ElementFace(texture: 'chest'),
                  Direction.east: const ElementFace(texture: 'chest'),
                  Direction.west: const ElementFace(texture: 'chest'),
                  Direction.up: const ElementFace(texture: 'chest'),
                  Direction.down: const ElementFace(texture: 'chest'),
                },
              ),
            ],
          ),
          // Stateful animation that responds to lid open state
          animation: BlockAnimation.stateful(
            inputs: {
              'lidOpen': StatefulAnimationInput(
                interpolationSpeed: 0.15, // ~7 ticks to fully open
                defaultValue: 0.0, // Start closed
              ),
            },
            easing: AnimationEasing.easeOut,
            transform: (values, state) {
              final lidOpen = values['lidOpen']!;
              // Rotate lid around the back edge
              state.rotationX = lidOpen * -90; // 0° closed, -90° open
              // Pivot at back-top edge of the block
              state.pivot = AnimationVec3(0.5, 1.0, 0.0);
            },
          ),
        );

  /// Creates an AnimatedChestBlock instance.
  factory AnimatedChestBlock() => instance;

  /// Register the block and its block entity.
  ///
  /// Call this during mod initialization.
  static void register() {
    // Register the block entity type
    BlockEntityRegistry.registerType(
      blockId,
      AnimatedChestEntity.new,
      inventorySize: 27, // Standard chest size
      containerTitle: 'Animated Chest',
    );

    // Register the block
    BlockRegistry.register(instance);
  }
}
