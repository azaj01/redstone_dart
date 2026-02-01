/// Custom Goal Registry API for server-side goal dispatch.
///
/// This library provides the registry for custom AI goals that
/// dispatches callbacks from the native bridge to Dart goal implementations.
///
/// Supports two patterns:
/// 1. **Legacy singleton pattern** ([CustomGoal]): One goal instance handles all entities.
///    Requires manual state tracking with maps.
/// 2. **Per-entity pattern** ([Goal] + [CustomGoalFactory]): Each entity gets its own
///    goal instance with its own state. This is the recommended pattern for new code.
library;

import 'dart:ffi';

import 'package:dart_mod_common/dart_mod_common.dart';
import 'package:ffi/ffi.dart';

import 'bridge.dart';

/// Registry for custom goals.
/// Goals must be registered before they can be used.
class CustomGoalRegistry {
  /// Singleton goals (legacy pattern): goalId -> single goal instance
  static final Map<String, CustomGoal> _singletonGoals = {};

  /// Per-entity goal instances: goalId -> (entityId -> goal instance)
  static final Map<String, Map<int, Goal>> _entityGoals = {};

  /// Goal factories: goalId -> factory function
  static final Map<String, Goal Function(int entityId)> _goalFactories = {};

  static bool _handlersRegistered = false;

  // ===========================================================================
  // Registration API
  // ===========================================================================

  /// Register a singleton custom goal type (legacy pattern).
  ///
  /// Use this for goals that don't need per-entity state, or when you
  /// prefer to manage state manually with maps.
  static void register(CustomGoal goal) {
    if (_singletonGoals.containsKey(goal.id)) {
      throw StateError('Goal ${goal.id} is already registered');
    }

    _ensureHandlersRegistered();

    _singletonGoals[goal.id] = goal;
    print('CustomGoalRegistry: Registered singleton goal ${goal.id}');
  }

  /// Register a goal factory for per-entity goal instances (Minecraft-style).
  ///
  /// The factory will be called once per entity that uses this goal,
  /// creating a separate goal instance with its own state for each entity.
  ///
  /// Example:
  /// ```dart
  /// CustomGoalRegistry.registerFactory(
  ///   'mymod:walk_straight',
  ///   (entityId) => WalkStraightGoal(entityId),
  /// );
  /// ```
  static void registerFactory(
    String goalId,
    Goal Function(int entityId) factory, {
    Set<GoalFlag> flags = const {},
    bool requiresUpdateEveryTick = true,
  }) {
    if (_goalFactories.containsKey(goalId)) {
      throw StateError('Goal factory $goalId is already registered');
    }

    _ensureHandlersRegistered();

    _goalFactories[goalId] = factory;
    _entityGoals[goalId] = {};
    print('CustomGoalRegistry: Registered goal factory $goalId');
  }

  /// Get a registered singleton goal by ID.
  static CustomGoal? get(String id) => _singletonGoals[id];

  /// Check if a goal is registered (either singleton or factory).
  static bool isRegistered(String id) =>
      _singletonGoals.containsKey(id) || _goalFactories.containsKey(id);

  /// Get all registered goal IDs.
  static Iterable<String> get registeredIds =>
      {..._singletonGoals.keys, ..._goalFactories.keys};

  /// Remove all goal instances for an entity (called when entity is removed).
  static void removeEntity(int entityId) {
    for (final goals in _entityGoals.values) {
      goals.remove(entityId);
    }
  }

  // ===========================================================================
  // Internal: Get or create goal instance for an entity
  // ===========================================================================

  /// Get or create the goal instance for an entity.
  /// For singleton goals, returns the singleton.
  /// For factory goals, creates a new instance if needed.
  static Goal? _getOrCreateGoal(String goalId, int entityId) {
    // Check if it's a factory-based goal
    if (_goalFactories.containsKey(goalId)) {
      final entityGoalsMap = _entityGoals[goalId]!;
      if (!entityGoalsMap.containsKey(entityId)) {
        // Create a new goal instance for this entity
        final factory = _goalFactories[goalId]!;
        entityGoalsMap[entityId] = factory(entityId);
      }
      return entityGoalsMap[entityId];
    }
    return null;
  }

  /// Ensure native handlers are registered for goal callbacks.
  static void _ensureHandlersRegistered() {
    if (_handlersRegistered) return;
    if (ServerBridge.isDatagenMode) {
      _handlersRegistered = true;
      return;
    }

    ServerBridge.registerCustomGoalCanUseHandler(
      Pointer.fromFunction<_CustomGoalBoolCallbackNative>(
        _onCanUse,
        false,
      ),
    );

    ServerBridge.registerCustomGoalCanContinueToUseHandler(
      Pointer.fromFunction<_CustomGoalBoolCallbackNative>(
        _onCanContinueToUse,
        false,
      ),
    );

    ServerBridge.registerCustomGoalStartHandler(
      Pointer.fromFunction<_CustomGoalVoidCallbackNative>(
        _onStart,
      ),
    );

    ServerBridge.registerCustomGoalTickHandler(
      Pointer.fromFunction<_CustomGoalVoidCallbackNative>(
        _onTick,
      ),
    );

    ServerBridge.registerCustomGoalStopHandler(
      Pointer.fromFunction<_CustomGoalVoidCallbackNative>(
        _onStop,
      ),
    );

    _handlersRegistered = true;
  }

  // ===========================================================================
  // Internal: Called by native bridge for goal callbacks
  // ===========================================================================

  /// Dispatch canUse callback for a goal.
  @pragma('vm:entry-point')
  static bool _onCanUse(Pointer<Utf8> goalIdPtr, int entityId) {
    final goalId = goalIdPtr.toDartString();
    return dispatchCanUse(goalId, entityId);
  }

  /// Dispatch canContinueToUse callback for a goal.
  @pragma('vm:entry-point')
  static bool _onCanContinueToUse(Pointer<Utf8> goalIdPtr, int entityId) {
    final goalId = goalIdPtr.toDartString();
    return dispatchCanContinueToUse(goalId, entityId);
  }

  /// Dispatch start callback for a goal.
  @pragma('vm:entry-point')
  static void _onStart(Pointer<Utf8> goalIdPtr, int entityId) {
    final goalId = goalIdPtr.toDartString();
    dispatchStart(goalId, entityId);
  }

  /// Dispatch tick callback for a goal.
  @pragma('vm:entry-point')
  static void _onTick(Pointer<Utf8> goalIdPtr, int entityId) {
    final goalId = goalIdPtr.toDartString();
    dispatchTick(goalId, entityId);
  }

  /// Dispatch stop callback for a goal.
  @pragma('vm:entry-point')
  static void _onStop(Pointer<Utf8> goalIdPtr, int entityId) {
    final goalId = goalIdPtr.toDartString();
    dispatchStop(goalId, entityId);
  }

  // ===========================================================================
  // Public dispatch methods (can also be called directly if needed)
  // ===========================================================================

  /// Dispatch canUse callback for a goal.
  /// Called by native bridge to check if a goal can start.
  static bool dispatchCanUse(String goalId, int entityId) {
    // Try per-entity goal first
    final goal = _getOrCreateGoal(goalId, entityId);
    if (goal != null) {
      return goal.canUse();
    }
    // Fall back to singleton goal
    return _singletonGoals[goalId]?.canUse(entityId) ?? false;
  }

  /// Dispatch canContinueToUse callback for a goal.
  /// Called by native bridge to check if a goal should continue.
  static bool dispatchCanContinueToUse(String goalId, int entityId) {
    // Try per-entity goal first
    final goal = _getOrCreateGoal(goalId, entityId);
    if (goal != null) {
      return goal.canContinueToUse();
    }
    // Fall back to singleton goal
    return _singletonGoals[goalId]?.canContinueToUse(entityId) ?? false;
  }

  /// Dispatch start callback for a goal.
  /// Called by native bridge when a goal starts.
  static void dispatchStart(String goalId, int entityId) {
    // Try per-entity goal first
    final goal = _getOrCreateGoal(goalId, entityId);
    if (goal != null) {
      goal.start();
      return;
    }
    // Fall back to singleton goal
    _singletonGoals[goalId]?.start(entityId);
  }

  /// Dispatch tick callback for a goal.
  /// Called by native bridge every tick while a goal is active.
  static void dispatchTick(String goalId, int entityId) {
    // Try per-entity goal first
    final goal = _getOrCreateGoal(goalId, entityId);
    if (goal != null) {
      goal.tick();
      return;
    }
    // Fall back to singleton goal
    _singletonGoals[goalId]?.tick(entityId);
  }

  /// Dispatch stop callback for a goal.
  /// Called by native bridge when a goal stops.
  static void dispatchStop(String goalId, int entityId) {
    // Try per-entity goal first
    final goal = _getOrCreateGoal(goalId, entityId);
    if (goal != null) {
      goal.stop();
      return;
    }
    // Fall back to singleton goal
    _singletonGoals[goalId]?.stop(entityId);
  }
}

// =============================================================================
// FFI Callback Type Definitions
// =============================================================================

/// Native callback type for bool-returning goal methods.
typedef _CustomGoalBoolCallbackNative = Bool Function(
  Pointer<Utf8> goalId,
  Int32 entityId,
);

/// Native callback type for void goal methods.
typedef _CustomGoalVoidCallbackNative = Void Function(
  Pointer<Utf8> goalId,
  Int32 entityId,
);
