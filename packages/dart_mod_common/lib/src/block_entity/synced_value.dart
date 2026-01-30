/// Synced value types for block entities.
///
/// These values automatically sync to clients via ContainerData.
library;

/// A synchronized value that syncs to clients via ContainerData.
///
/// Implements a [ValueNotifier]-like pattern for reactive updates.
/// Can be used with Flutter's [ValueListenableBuilder] for UI binding.
///
/// The [dataSlotIndex] maps to the ContainerData slot index, which is
/// auto-assigned by [ContainerDefinition.syncedValues].
///
/// For furnace-like block entities:
/// - 0: litTime (current burn time remaining)
/// - 1: litDuration (total burn time of current fuel)
/// - 2: cookingProgress (current cooking progress)
/// - 3: cookingTotalTime (total cooking time for recipe)
class SyncedValue<T> {
  /// The ContainerData slot index for this value.
  /// This is assigned automatically by [ContainerDefinition.syncedValues].
  int dataSlotIndex;

  T _value;
  final List<void Function()> _listeners = [];

  /// Creates a synced value with an initial value.
  ///
  /// The [dataSlotIndex] will be assigned by [ContainerDefinition.syncedValues].
  SyncedValue(this._value) : dataSlotIndex = -1;

  /// Creates a synced value with a specific data slot index.
  ///
  /// Use this when manually managing slot indices outside of [ContainerDefinition].
  SyncedValue.withIndex(this.dataSlotIndex, this._value);

  /// The current value.
  T get value => _value;

  /// Set the value and notify listeners.
  ///
  /// On the server side, this writes to ContainerData for syncing to clients.
  set value(T v) {
    if (_value != v) {
      _value = v;
      _notifyListeners();
    }
  }

  /// Update the value from a sync without triggering server-side write.
  ///
  /// Use this on the client side when receiving updates from the server.
  /// This updates the local value and notifies listeners for UI updates,
  /// but does not mark the value as needing to sync back to the server.
  void updateFromSync(T v) {
    if (_value != v) {
      _value = v;
      _notifyListeners();
    }
  }

  /// Add a listener that will be called when the value changes.
  void addListener(void Function() listener) {
    _listeners.add(listener);
  }

  /// Remove a previously added listener.
  void removeListener(void Function() listener) {
    _listeners.remove(listener);
  }

  /// Notify all listeners of a value change.
  void _notifyListeners() {
    for (final listener in _listeners) {
      listener();
    }
  }

  /// Whether this value has any listeners.
  bool get hasListeners => _listeners.isNotEmpty;

  @override
  String toString() => 'SyncedValue<$T>(index: $dataSlotIndex, value: $_value)';
}

/// A synchronized integer value that syncs to clients via ContainerData.
///
/// This is the most common synced value type, used for furnace progress,
/// burn time, and other numeric container data.
///
/// Provides additional methods for ContainerData interop.
///
/// Optional debug metadata can be provided for MCP/inspection tools:
/// ```dart
/// final litTime = SyncedInt(0,
///   name: 'litTime',
///   description: 'Remaining burn time',
///   min: 0,
///   max: 1600,
///   unit: 'ticks',
/// );
/// ```
class SyncedInt extends SyncedValue<int> {
  /// Debug name for MCP/inspection tools.
  /// If not provided, defaults to 'data_$dataSlotIndex'.
  final String? name;

  /// Human-readable description of what this value represents.
  final String? description;

  /// Minimum valid value (for validation and UI display).
  final int? min;

  /// Maximum valid value (for validation and UI display).
  final int? max;

  /// Unit label (e.g., 'ticks', 'items', 'degrees').
  final String? unit;

  /// Creates a synced integer with an optional initial value and debug metadata.
  ///
  /// The [dataSlotIndex] will be assigned by [ContainerDefinition.syncedValues].
  SyncedInt({
    int value = 0,
    this.name,
    this.description,
    this.min,
    this.max,
    this.unit,
  }) : super(value);

  /// Creates a synced integer with a specific data slot index and debug metadata.
  ///
  /// Use this when manually managing slot indices outside of [ContainerDefinition].
  SyncedInt.withIndex(
    int dataSlotIndex, {
    int value = 0,
    this.name,
    this.description,
    this.min,
    this.max,
    this.unit,
  }) : super.withIndex(dataSlotIndex, value);

  /// Called by Java via native bridge to get current value for ContainerData.
  int toContainerData() => _value;

  /// Called by Java via native bridge to set value from ContainerData.
  void fromContainerData(int v) => updateFromSync(v);

  /// Convert to JSON for debug API / MCP inspection.
  Map<String, dynamic> toDebugJson() => {
        'index': dataSlotIndex,
        'name': name ?? 'data_$dataSlotIndex',
        'value': value,
        if (description != null) 'description': description,
        if (min != null) 'min': min,
        if (max != null) 'max': max,
        if (unit != null) 'unit': unit,
      };

  @override
  String toString() =>
      'SyncedInt(index: $dataSlotIndex, name: ${name ?? 'unnamed'}, value: $_value)';
}
