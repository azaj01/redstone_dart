/// HUD overlay rendering layer.
///
/// This widget renders all active HUD overlays from the [HudRegistry].
/// It should be placed at the root of your Flutter app, above other content.
library;

import 'dart:async';

import 'package:flutter/material.dart';

import 'hud_overlay.dart';
import 'hud_position.dart';
import 'hud_registry.dart';

/// A widget that renders all active HUD overlays.
///
/// Place this widget in your app's widget tree (typically via [HudLayerScope])
/// to display registered HUD overlays.
///
/// Usage:
/// ```dart
/// MaterialApp(
///   home: HudLayerScope(
///     child: YourGameContent(),
///   ),
/// )
/// ```
class HudLayer extends StatefulWidget {
  /// Child widget rendered behind the HUD overlays.
  final Widget? child;

  const HudLayer({super.key, this.child});

  @override
  State<HudLayer> createState() => _HudLayerState();
}

class _HudLayerState extends State<HudLayer> {
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    // Poll for changes since HudRegistry doesn't have change notifications
    // This is a simple approach - could be improved with ChangeNotifier
    _refreshTimer = Timer.periodic(const Duration(milliseconds: 100), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final activeOverlays = HudRegistry.activeStates;

    return Stack(
      children: [
        // Child content
        if (widget.child != null) widget.child!,

        // HUD overlays
        for (final state in activeOverlays)
          _buildOverlayPositioned(state.overlay),
      ],
    );
  }

  Widget _buildOverlayPositioned(HudOverlay overlay) {
    final position = overlay.position;
    final offset = overlay.offset;

    // Calculate positioning based on HudPosition
    double? top, bottom, left, right;

    switch (position) {
      case HudPosition.topLeft:
        top = offset.y;
        left = offset.x;
      case HudPosition.topCenter:
        top = offset.y;
        // Center horizontally - handled by Align
      case HudPosition.topRight:
        top = offset.y;
        right = offset.x;
      case HudPosition.centerLeft:
        left = offset.x;
        // Center vertically - handled by Align
      case HudPosition.center:
        // Full center - handled by Align
        break;
      case HudPosition.centerRight:
        right = offset.x;
        // Center vertically - handled by Align
      case HudPosition.bottomLeft:
        bottom = offset.y;
        left = offset.x;
      case HudPosition.bottomCenter:
        bottom = offset.y;
        // Center horizontally - handled by Align
      case HudPosition.bottomRight:
        bottom = offset.y;
        right = offset.x;
    }

    // Use Positioned for explicit positioning
    if (top != null || bottom != null || left != null || right != null) {
      return Positioned(
        top: top,
        bottom: bottom,
        left: left,
        right: right,
        child: SizedBox(
          width: overlay.width,
          height: overlay.height,
          child: overlay,
        ),
      );
    }

    // Use Align for center positions
    Alignment alignment;
    switch (position) {
      case HudPosition.topCenter:
        alignment = Alignment.topCenter;
      case HudPosition.centerLeft:
        alignment = Alignment.centerLeft;
      case HudPosition.center:
        alignment = Alignment.center;
      case HudPosition.centerRight:
        alignment = Alignment.centerRight;
      case HudPosition.bottomCenter:
        alignment = Alignment.bottomCenter;
      default:
        alignment = Alignment.center;
    }

    return Align(
      alignment: alignment,
      child: Padding(
        padding: EdgeInsets.only(
          top: offset.y,
          left: offset.x,
        ),
        child: SizedBox(
          width: overlay.width,
          height: overlay.height,
          child: overlay,
        ),
      ),
    );
  }
}

/// A convenience widget that wraps content with the HUD overlay layer.
///
/// This is the recommended way to add HUD support to your app.
///
/// Usage:
/// ```dart
/// MaterialApp(
///   home: HudLayerScope(
///     child: GuiRouter(
///       routes: [...],
///     ),
///   ),
/// )
/// ```
class HudLayerScope extends StatelessWidget {
  /// The child widget to wrap.
  final Widget child;

  const HudLayerScope({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return HudLayer(child: child);
  }
}
