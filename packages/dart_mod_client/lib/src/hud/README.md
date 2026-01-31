# HUD Overlay System

## Overview

The HUD (Heads-Up Display) overlay system allows mod developers to render Flutter widgets as persistent overlays on the Minecraft screen. This is useful for health bars, minimaps, status displays, and other UI elements that should be visible during gameplay.

## Current Status

**Dart-side API: Complete**
**Java-side rendering: Not yet implemented**

The Dart API is fully functional for registering, showing, and hiding HUD overlays. However, the overlays currently only render when a Flutter container screen is open (e.g., chest UI). To display HUDs during normal gameplay, Java-side rendering needs to be implemented.

## API Usage

### 1. Create a HUD Overlay

```dart
class HealthOverlay extends HudOverlay {
  const HealthOverlay({super.key});

  @override
  HudPosition get position => HudPosition.topLeft;

  @override
  HudOffset get offset => const HudOffset(10, 10);

  @override
  double get width => 200;

  @override
  double get height => 50;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black54,
      child: Text('Health: 20', style: TextStyle(color: Colors.white)),
    );
  }
}
```

### 2. Register the Overlay

In your client's `main()`:

```dart
void main() {
  // Initialize HUD system
  HudRegistry.initialize();

  // Register your overlays
  HudRegistry.register('mymod:health', () => HealthOverlay());

  // ... rest of initialization
}
```

### 3. Show/Hide Overlays

From server commands or client code:

```dart
// Show
HudRegistry.show('mymod:health');

// Hide
HudRegistry.hide('mymod:health');

// Toggle
HudRegistry.toggle('mymod:health');

// Check state
if (HudRegistry.isShown('mymod:health')) {
  print('Health overlay is visible');
}
```

### 4. Server-to-Client Communication

Send toggle events from server commands:

```dart
// In a server command
ServerNetwork.sendServerEvent(
  player.id,
  'toggle_hud',
  {'overlayId': 'mymod:health'},
);
```

Handle on the client:

```dart
ClientNetwork.onServerEvent((event) {
  if (event.eventName == 'toggle_hud') {
    final overlayId = event.payload['overlayId'] as String?;
    if (overlayId != null) {
      HudRegistry.toggle(overlayId);
    }
  }
});
```

## Position Options

The `HudPosition` enum provides 9 anchor positions:

- `topLeft`, `topCenter`, `topRight`
- `centerLeft`, `center`, `centerRight`
- `bottomLeft`, `bottomCenter`, `bottomRight`

Use `HudOffset` to fine-tune positioning with x/y pixel offsets.

## Future Work: Java-side Rendering

To render HUDs during normal gameplay (not just in container screens), the following needs to be implemented:

1. **Hook into Minecraft's HUD rendering** via Fabric's `HudRenderCallback`
2. **Keep Flutter engine running** even when no screen is open
3. **Render Flutter texture** as an overlay on each frame
4. **Handle input events** for interactive HUD elements

The Java-side already tracks active overlays in `DartBridgeClient.activeHudOverlays`. The rendering integration would use this to determine which overlays to display.

## Files

- `hud_overlay.dart` - Base class for HUD widgets
- `hud_registry.dart` - Registration and state management
- `hud_events.dart` - Event streams for show/hide lifecycle
- `hud_position.dart` - Position enum and offset class
- `hud_layer.dart` - Flutter widget for rendering overlays (works in container screens)
