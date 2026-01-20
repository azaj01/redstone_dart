/// Base class for Dart-defined block entities.
library;

import 'block_entity_settings.dart';
import '../jni/generic_bridge.dart';
import '../types.dart';

// Minecraft BlockPos bit packing constants (matches Java BlockPos.asLong)
// PACKED_HORIZONTAL_LENGTH = 26 bits (for x and z)
// PACKED_Y_LENGTH = 12 bits (for y)
// Y at offset 0, Z at offset 12, X at offset 38
const int _packedHorizontalLength = 26;
const int _packedYLength = 12;
const int _zOffset = _packedYLength; // 12
const int _xOffset = _packedYLength + _packedHorizontalLength; // 38

/// Decode x coordinate from a packed BlockPos long.
int decodeBlockPosX(int packed) {
  // Signed extraction: shift left then arithmetic right
  return (packed << (64 - _xOffset - _packedHorizontalLength)) >>
      (64 - _packedHorizontalLength);
}

/// Decode y coordinate from a packed BlockPos long.
int decodeBlockPosY(int packed) {
  // Signed extraction
  return (packed << (64 - _packedYLength)) >> (64 - _packedYLength);
}

/// Decode z coordinate from a packed BlockPos long.
int decodeBlockPosZ(int packed) {
  // Signed extraction
  return (packed << (64 - _zOffset - _packedHorizontalLength)) >>
      (64 - _packedHorizontalLength);
}

/// Decode a packed BlockPos long to a BlockPos.
BlockPos decodeBlockPos(int packed) {
  return BlockPos(
    decodeBlockPosX(packed),
    decodeBlockPosY(packed),
    decodeBlockPosZ(packed),
  );
}

/// Encode a BlockPos to a packed long (matches Java BlockPos.asLong).
///
/// This is the inverse of [decodeBlockPos].
int encodeBlockPos(BlockPos pos) {
  // Mask to appropriate bit widths
  final x = pos.x & ((1 << _packedHorizontalLength) - 1);
  final y = pos.y & ((1 << _packedYLength) - 1);
  final z = pos.z & ((1 << _packedHorizontalLength) - 1);

  return (x << _xOffset) | (z << _zOffset) | y;
}

/// Base class for all Dart-defined block entities.
///
/// Block entities are tile entities attached to blocks that can store
/// data, perform actions, and sync state to clients.
///
/// The lifecycle mirrors Java's BlockEntity:
/// - [setLevel] - Called when the block entity is added to a level
/// - [loadAdditional] - Called when loading saved NBT data
/// - [saveAdditional] - Called when saving state to NBT
/// - [setRemoved] - Called when removed from the world
///
/// ## Example
///
/// ```dart
/// class MyBlockEntity extends BlockEntity {
///   MyBlockEntity() : super(
///     settings: BlockEntitySettings(id: 'mymod:my_block_entity'),
///   );
///
///   @override
///   void setLevel() {
///     // Called when block entity is added to the world
///   }
///
///   @override
///   void loadAdditional(Map<String, dynamic> nbt) {
///     // Load saved state from NBT
///   }
///
///   @override
///   Map<String, dynamic> saveAdditional() {
///     return {'myData': 42};
///   }
/// }
/// ```
abstract class BlockEntity {
  /// Settings for this block entity type.
  final BlockEntitySettings settings;

  /// Handler ID assigned by the registry.
  /// Used to route callbacks to the correct block entity type.
  int? handlerId;

  /// Hash of the block position in the world.
  /// Used to identify this specific block entity instance.
  int? blockPosHash;

  /// Creates a block entity with the given settings.
  BlockEntity({required this.settings});

  /// Get the string ID of this block entity type.
  String get id => settings.id;

  /// Get the block position of this block entity.
  /// Returns null if blockPosHash is not set.
  BlockPos? get blockPos {
    final hash = blockPosHash;
    if (hash == null) return null;
    return decodeBlockPos(hash);
  }

  /// Called when the block entity is added to a level.
  ///
  /// This is called for both newly placed blocks and blocks loaded from save.
  /// Override to perform initialization that requires access to the world.
  ///
  /// Note: [blockPos] is available when this is called.
  void setLevel() {}

  /// Called when the block entity is loaded from saved NBT data.
  ///
  /// Override to restore state when the chunk is loaded or the world starts.
  /// This is only called when there is saved data to load.
  void loadAdditional(Map<String, dynamic> nbt) {}

  /// Called when the block entity needs to save its state to NBT.
  ///
  /// Override to persist state when the chunk is saved or the world stops.
  /// Return a map of key-value pairs to save.
  Map<String, dynamic> saveAdditional() => {};

  /// Called when the block entity is removed from the world.
  ///
  /// Override to perform cleanup when the block is broken or replaced.
  void setRemoved() {}
}

/// Mixin for block entities that need to handle container open/close events.
///
/// Implement this mixin in your block entity to receive callbacks when
/// a player opens or closes the container UI.
///
/// ## Example
///
/// ```dart
/// class MyChestEntity extends ContainerBlockEntity<MyChestContainer>
///     with ContainerOpenCloseHandler {
///   @override
///   void onContainerOpen() {
///     // Start some action when player opens the chest
///   }
///
///   @override
///   void onContainerClose() {
///     // Stop when player closes the chest
///   }
/// }
/// ```
mixin ContainerOpenCloseHandler on BlockEntity {
  /// Called when a player opens this container.
  void onContainerOpen();

  /// Called when a player closes this container.
  void onContainerClose();
}

/// Mixin for block entities with stateful animations.
///
/// Use this mixin in your block entity to control state-driven animations.
/// The state values are smoothly interpolated in Java for 60fps rendering.
///
/// ## Example
///
/// ```dart
/// class MyChestEntity extends ContainerBlockEntity<MyChestContainer>
///     with ContainerOpenCloseHandler, AnimatedBlockEntity {
///   @override
///   void onContainerOpen() {
///     setAnimationState('lidOpen', true);
///   }
///
///   @override
///   void onContainerClose() {
///     setAnimationState('lidOpen', false);
///   }
/// }
/// ```
mixin AnimatedBlockEntity on BlockEntity {
  /// Set a boolean animation state.
  ///
  /// Converts true to 1.0 and false to 0.0.
  /// The value will be smoothly interpolated in Java.
  ///
  /// [key] must match a state key defined in the block's animation config.
  void setAnimationState(String key, bool value) {
    setAnimationStateDouble(key, value ? 1.0 : 0.0);
  }

  /// Set a double animation state value.
  ///
  /// The value will be smoothly interpolated in Java toward this target.
  /// Use values between 0.0 and 1.0 for best results with easing functions.
  ///
  /// [key] must match a state key defined in the block's animation config.
  /// [speed] optionally overrides the interpolation speed (default uses config value).
  void setAnimationStateDouble(String key, double value, {double? speed}) {
    final hash = blockPosHash;
    if (hash == null) {
      // Block entity not yet placed in world
      return;
    }

    // Call Java's DartBridge.setAnimationState via JNI
    // Signature: (JLjava/lang/String;DD)V
    // J = long (blockPosHash), String (key), D = double (value), D = double (speed)
    GenericJniBridge.callStaticVoidMethod(
      'com/redstone/DartBridge',
      'setAnimationState',
      '(JLjava/lang/String;DD)V',
      [hash, key, value, speed ?? -1.0], // -1.0 means use config speed
    );
  }

  /// Set an integer animation state value.
  ///
  /// Converts to double for interpolation.
  void setAnimationStateInt(String key, int value) {
    setAnimationStateDouble(key, value.toDouble());
  }

  /// Sets the rotation for the animation directly (in degrees).
  ///
  /// Unlike [setAnimationState] which sets abstract values that are
  /// interpreted based on the animation config, this sets the actual
  /// rotation that will be rendered.
  ///
  /// Example:
  /// ```dart
  /// // Rotate 90 degrees around the X axis
  /// setAnimationRotation(x: 90.0);
  ///
  /// // Rotate 45 degrees around Y and 30 degrees around Z
  /// setAnimationRotation(y: 45.0, z: 30.0);
  /// ```
  void setAnimationRotation({double? x, double? y, double? z}) {
    if (x != null) setAnimationStateDouble('rotationX', x);
    if (y != null) setAnimationStateDouble('rotationY', y);
    if (z != null) setAnimationStateDouble('rotationZ', z);
  }

  /// Sets the translation for the animation directly (in blocks).
  ///
  /// Example:
  /// ```dart
  /// // Move up by 0.5 blocks
  /// setAnimationTranslation(y: 0.5);
  /// ```
  void setAnimationTranslation({double? x, double? y, double? z}) {
    if (x != null) setAnimationStateDouble('translateX', x);
    if (y != null) setAnimationStateDouble('translateY', y);
    if (z != null) setAnimationStateDouble('translateZ', z);
  }

  /// Sets the scale for the animation directly.
  ///
  /// A value of 1.0 is normal size.
  ///
  /// Example:
  /// ```dart
  /// // Scale to 1.5x on all axes
  /// setAnimationScale(x: 1.5, y: 1.5, z: 1.5);
  /// ```
  void setAnimationScale({double? x, double? y, double? z}) {
    if (x != null) setAnimationStateDouble('scaleX', x);
    if (y != null) setAnimationStateDouble('scaleY', y);
    if (z != null) setAnimationStateDouble('scaleZ', z);
  }

  /// Sets the pivot point for the animation (0.0 to 1.0 relative to block).
  ///
  /// The pivot is the point around which rotation and scaling occur.
  /// Default is (0.5, 0.5, 0.5) which is the center of the block.
  ///
  /// Example:
  /// ```dart
  /// // Pivot at the back-top edge (like a chest lid hinge)
  /// setAnimationPivot(x: 0.5, y: 1.0, z: 0.0);
  /// ```
  void setAnimationPivot({double? x, double? y, double? z}) {
    if (x != null) setAnimationStateDouble('pivotX', x);
    if (y != null) setAnimationStateDouble('pivotY', y);
    if (z != null) setAnimationStateDouble('pivotZ', z);
  }
}
