/// ContainerScope - InheritedWidget for providing containers to descendant widgets.
///
/// Provides automatic rebuild when container data changes via push-based events.
/// Also handles bidirectional sync - local changes are sent back to the server.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:dart_mod_common/dart_mod_common.dart';

import '../events/container_data_events.dart';
import '../inventory/client_container_view.dart';
import '../network.dart';

/// Provides a container to descendant widgets with automatic rebuild on data changes.
///
/// Usage:
/// ```dart
/// // In your screen builder:
/// ContainerScope<MyContainer>(
///   container: container,
///   menuId: menuId,
///   child: MyContainerScreen(),
/// )
///
/// // In descendant widgets:
/// final container = ContainerScope.of<MyContainer>(context);
/// return Text('Value: ${container.someValue.value}');
/// ```
///
/// Widgets that call `ContainerScope.of()` will automatically rebuild when
/// any SyncedValue in the container changes.
class ContainerScope<T extends ContainerDefinition> extends StatefulWidget {
  /// The container definition providing synced values.
  final T container;

  /// The menu ID for filtering data change events.
  final int menuId;

  /// The child widget tree that can access the container.
  final Widget child;

  const ContainerScope({
    super.key,
    required this.container,
    required this.menuId,
    required this.child,
  });

  /// Get the container from the nearest ContainerScope ancestor.
  ///
  /// The calling widget will rebuild when container data changes.
  static T of<T extends ContainerDefinition>(BuildContext context) {
    // First try exact type match
    var scope =
        context.dependOnInheritedWidgetOfExactType<_ContainerScopeInherited<T>>();

    // If not found, try base ContainerDefinition type and cast
    // This handles the case where GuiRouter creates ContainerScope<ContainerDefinition>
    // but the screen requests a specific subtype
    if (scope == null) {
      final baseScope = context
          .dependOnInheritedWidgetOfExactType<_ContainerScopeInherited<ContainerDefinition>>();
      if (baseScope != null && baseScope.container is T) {
        return baseScope.container as T;
      }
    }

    if (scope == null) {
      throw FlutterError(
        'ContainerScope.of<$T>() called without a ContainerScope<$T> ancestor.\n'
        'Make sure your screen is wrapped in a ContainerScope.',
      );
    }
    return scope.container;
  }

  /// Get the container without registering as a dependent (no auto-rebuild).
  ///
  /// Use this when you need the container but don't want to rebuild when data changes.
  static T read<T extends ContainerDefinition>(BuildContext context) {
    // First try exact type match
    var scope =
        context.getInheritedWidgetOfExactType<_ContainerScopeInherited<T>>();

    // If not found, try base ContainerDefinition type and cast
    if (scope == null) {
      final baseScope = context
          .getInheritedWidgetOfExactType<_ContainerScopeInherited<ContainerDefinition>>();
      if (baseScope != null && baseScope.container is T) {
        return baseScope.container as T;
      }
    }

    if (scope == null) {
      throw FlutterError(
        'ContainerScope.read<$T>() called without a ContainerScope<$T> ancestor.',
      );
    }
    return scope.container;
  }

  /// Try to get the container, returning null if not found.
  ///
  /// Useful when the container may not be available.
  static T? maybeOf<T extends ContainerDefinition>(BuildContext context) {
    // First try exact type match
    var scope =
        context.dependOnInheritedWidgetOfExactType<_ContainerScopeInherited<T>>();
    if (scope != null) {
      return scope.container;
    }

    // If not found, try base ContainerDefinition type and cast
    final baseScope = context
        .dependOnInheritedWidgetOfExactType<_ContainerScopeInherited<ContainerDefinition>>();
    if (baseScope != null && baseScope.container is T) {
      return baseScope.container as T;
    }

    return null;
  }

  @override
  State<ContainerScope<T>> createState() => _ContainerScopeState<T>();
}

class _ContainerScopeState<T extends ContainerDefinition>
    extends State<ContainerScope<T>> {
  StreamSubscription<ContainerDataChangedEvent>? _subscription;
  int _updateCount = 0;

  /// Track values we're updating locally to avoid sending back server echoes.
  final Set<int> _locallyUpdatingSlots = {};

  /// The data source for pulling initial/cached values.
  static const _dataSource = ClientContainerView();

  @override
  void initState() {
    super.initState();
    // Initialize container from Java-side cache (pull) + subscribe to updates (push).
    // This pattern prevents race conditions where initial values are sent before
    // the Flutter widget tree is built.
    widget.container.initializeFromCache(_dataSource);
    _subscribeToDataChanges();
    _subscribeToLocalChanges();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _unsubscribeFromLocalChanges();
    super.dispose();
  }

  /// Subscribe to local value changes so we can send them to the server.
  void _subscribeToLocalChanges() {
    for (final syncedValue in widget.container.syncedValuesList) {
      syncedValue.addListener(() => _onLocalValueChanged(syncedValue));
    }
  }

  /// Unsubscribe from local value changes.
  void _unsubscribeFromLocalChanges() {
    for (final syncedValue in widget.container.syncedValuesList) {
      syncedValue.removeListener(() => _onLocalValueChanged(syncedValue));
    }
  }

  /// Called when a SyncedInt value is changed locally (e.g., by UI).
  void _onLocalValueChanged(SyncedInt syncedValue) {
    // Debug: Log the update
    // ignore: avoid_print
    print('[ContainerScope] Local value changed: slot=${syncedValue.dataSlotIndex}, value=${syncedValue.value}, menuId=${widget.menuId}');

    // Send update to server
    ClientNetwork.sendContainerData(
      widget.menuId,
      syncedValue.dataSlotIndex,
      syncedValue.value,
    );

    // Trigger rebuild
    if (mounted) {
      setState(() {
        _updateCount++;
      });
    }
  }

  void _subscribeToDataChanges() {
    _subscription = ContainerDataEvents.onDataChanged.listen((event) {
      // Only handle events for our menu
      if (event.menuId != widget.menuId) return;

      // Find the SyncedValue with this slot index and update it
      final syncedValues = widget.container.syncedValuesList;
      for (final syncedValue in syncedValues) {
        if (syncedValue.dataSlotIndex == event.slotIndex) {
          // Use updateFromSync to avoid triggering local change handler
          syncedValue.updateFromSync(event.value);
          // Trigger rebuild
          if (mounted) {
            setState(() {
              _updateCount++;
            });
          }
          break;
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return _ContainerScopeInherited<T>(
      container: widget.container,
      updateCount: _updateCount,
      child: widget.child,
    );
  }
}

class _ContainerScopeInherited<T extends ContainerDefinition>
    extends InheritedWidget {
  final T container;
  final int updateCount;

  const _ContainerScopeInherited({
    required this.container,
    required this.updateCount,
    required super.child,
  });

  @override
  bool updateShouldNotify(_ContainerScopeInherited<T> oldWidget) {
    // Notify when update count changes (data was updated)
    return updateCount != oldWidget.updateCount;
  }
}
