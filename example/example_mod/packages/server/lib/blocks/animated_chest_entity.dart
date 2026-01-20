/// Animated chest block entity demonstrating stateful animations.
library;

import 'package:dart_mod_server/dart_mod_server.dart';

/// A chest block entity that animates its lid when opened/closed.
///
/// This demonstrates:
/// - [AnimatedBlockEntity] mixin for controlling animation state
/// - [ContainerOpenCloseHandler] for open/close callbacks
/// - Integration with [BlockAnimation.stateful()]
class AnimatedChestEntity extends ContainerBlockEntity<AnimatedChestContainer>
    with ContainerOpenCloseHandler, AnimatedBlockEntity {
  AnimatedChestEntity() : super(container: AnimatedChestContainer());

  @override
  void onContainerOpen() {
    // Rotate the lid -90 degrees around the X axis (opens like a chest lid)
    // The pivot is set at the back-top edge of the block
    setAnimationRotation(x: -90.0);
    setAnimationPivot(x: 0.5, y: 10.0 / 16.0, z: 1.0);
  }

  @override
  void onContainerClose() {
    // Return to closed position (0 degrees)
    setAnimationRotation(x: 0.0);
  }
}

/// Container definition for the animated chest.
///
/// This is a simple chest container with 27 slots (standard chest size)
/// and no synced data values - it just demonstrates the animation system.
class AnimatedChestContainer extends ContainerDefinition {
  @override
  String get id => 'example_mod:animated_chest';

  @override
  int get slotCount => 27; // Standard chest size (3 rows of 9)

  AnimatedChestContainer();
}
