/// Client-side network module for server-client communication.
library;

// ignore_for_file: unused_field

import 'dart:ffi';
import 'dart:typed_data';

import 'package:dart_mod_common/dart_mod_common.dart';
import 'package:ffi/ffi.dart';

/// Typedef for the native packet received callback.
typedef _PacketReceivedCallbackNative = Void Function(
    Int32 packetType, Pointer<Uint8> data, Int32 dataLength);

/// Client-side network handler for receiving packets from server.
///
/// This class provides the client-side API for receiving packets from
/// the server and sending packets back to the server.
class ClientNetwork {
  static DynamicLibrary? _lib;
  static bool _initialized = false;

  // Dart-side packet handlers
  static final List<void Function(ModPacket packet)> _packetHandlers = [];

  // Specific typed handlers for common packet types
  static final List<void Function(BlockUpdatePacket packet)>
      _blockUpdateHandlers = [];
  static final List<void Function(EntityUpdatePacket packet)>
      _entityUpdateHandlers = [];
  static final List<void Function(ScreenDataPacket packet)>
      _screenDataHandlers = [];
  static final List<void Function(SyncStatePacket packet)>
      _syncStateHandlers = [];
  static final List<void Function(ServerEventPacket packet)>
      _serverEventHandlers = [];

  /// Initialize the client network module.
  ///
  /// If [libraryPath] is null, uses DynamicLibrary.process() to access
  /// symbols from the already-loaded native library.
  static void init([String? libraryPath]) {
    if (_initialized) return;

    if (libraryPath != null) {
      _lib = DynamicLibrary.open(libraryPath);
    } else {
      // Use the process's loaded symbols (native library already loaded by embedder)
      _lib = DynamicLibrary.process();
    }
    _initialized = true;

    // Bind functions
    _bindFunctions();

    // Register packet decoders
    registerS2CPackets();
    registerC2SPackets();

    // Register native callback for receiving packets
    _registerNativeCallback();
  }

  // Native function bindings
  static late final _ClientRegisterPacketReceivedHandler
      _clientRegisterPacketReceivedHandler;
  static late final _ClientSetSendPacketToServerCallback
      _clientSetSendPacketToServerCallback;
  static late final _ClientSendPacketToServer _clientSendPacketToServer;

  // NativeCallable for thread-safe packet receiving
  // Uses .listener() to safely handle calls from any thread (e.g., JNI/render thread)
  static NativeCallable<_PacketReceivedCallbackNative>? _packetReceivedCallable;

  static void _bindFunctions() {
    final lib = _lib!;

    _clientRegisterPacketReceivedHandler = lib.lookupFunction<
        Void Function(Pointer<NativeFunction<_PacketReceivedCallbackNative>>),
        void Function(Pointer<NativeFunction<_PacketReceivedCallbackNative>>)>(
        'client_register_packet_received_handler');

    _clientSetSendPacketToServerCallback = lib.lookupFunction<
        Void Function(Pointer<NativeFunction<_SendPacketToServerCallbackNative>>),
        void Function(Pointer<NativeFunction<_SendPacketToServerCallbackNative>>)>(
        'client_set_send_packet_to_server_callback');

    _clientSendPacketToServer = lib.lookupFunction<
        Void Function(Int32, Pointer<Uint8>, Int32),
        void Function(int, Pointer<Uint8>, int)>(
        'client_send_packet_to_server');
  }

  static void _registerNativeCallback() {
    // Create NativeCallable.listener - this is safe to call from ANY thread
    // The Dart VM will automatically post the callback to our isolate's event loop
    // This fixes "Cannot invoke native callback outside an isolate" crashes
    _packetReceivedCallable = NativeCallable<_PacketReceivedCallbackNative>.listener(
      _onPacketReceivedFromNative,
    );
    _clientRegisterPacketReceivedHandler(_packetReceivedCallable!.nativeFunction);
  }

  /// Internal callback from native code.
  /// This is called from the Dart isolate's event loop (safe, thanks to NativeCallable.listener).
  /// IMPORTANT: The data pointer is malloc'd by native code and must be freed here.
  static void _onPacketReceivedFromNative(
      int packetType, Pointer<Uint8> data, int dataLength) {
    // Copy data to Dart memory
    final bytes = Uint8List(dataLength);
    for (var i = 0; i < dataLength; i++) {
      bytes[i] = data[i];
    }

    // Free the malloc'd native buffer (allocated by client_dispatch_server_packet)
    malloc.free(data);

    // Process the packet
    _processReceivedPacket(packetType, bytes);
  }

  /// Process a received packet (called from event loop after native callback).
  static void _processReceivedPacket(int packetType, Uint8List bytes) {
    // Decode the packet
    final packet = PacketRegistry.decode(packetType, bytes);
    if (packet == null) {
      print('[ClientNetwork] Unknown packet type: $packetType');
      return;
    }

    // Dispatch to typed handlers first
    _dispatchTypedHandlers(packet);

    // Then notify general handlers
    for (final handler in _packetHandlers) {
      try {
        handler(packet);
      } catch (e) {
        print('[ClientNetwork] Error in packet handler: $e');
      }
    }
  }

  static void _dispatchTypedHandlers(ModPacket packet) {
    switch (packet) {
      case BlockUpdatePacket():
        for (final handler in _blockUpdateHandlers) {
          try {
            handler(packet);
          } catch (e) {
            print('[ClientNetwork] Error in block update handler: $e');
          }
        }
        break;
      case EntityUpdatePacket():
        for (final handler in _entityUpdateHandlers) {
          try {
            handler(packet);
          } catch (e) {
            print('[ClientNetwork] Error in entity update handler: $e');
          }
        }
        break;
      case ScreenDataPacket():
        for (final handler in _screenDataHandlers) {
          try {
            handler(packet);
          } catch (e) {
            print('[ClientNetwork] Error in screen data handler: $e');
          }
        }
        break;
      case SyncStatePacket():
        for (final handler in _syncStateHandlers) {
          try {
            handler(packet);
          } catch (e) {
            print('[ClientNetwork] Error in sync state handler: $e');
          }
        }
        break;
      case ServerEventPacket():
        for (final handler in _serverEventHandlers) {
          try {
            handler(packet);
          } catch (e) {
            print('[ClientNetwork] Error in server event handler: $e');
          }
        }
        break;
    }
  }

  /// Register a handler for all received packets.
  static void onPacketReceived(void Function(ModPacket packet) handler) {
    _packetHandlers.add(handler);
  }

  /// Register a handler specifically for block update packets.
  static void onBlockUpdate(void Function(BlockUpdatePacket packet) handler) {
    _blockUpdateHandlers.add(handler);
  }

  /// Register a handler specifically for entity update packets.
  static void onEntityUpdate(void Function(EntityUpdatePacket packet) handler) {
    _entityUpdateHandlers.add(handler);
  }

  /// Register a handler specifically for screen data packets.
  static void onScreenData(void Function(ScreenDataPacket packet) handler) {
    _screenDataHandlers.add(handler);
  }

  /// Register a handler specifically for state sync packets.
  static void onSyncState(void Function(SyncStatePacket packet) handler) {
    _syncStateHandlers.add(handler);
  }

  /// Register a handler specifically for server event packets.
  static void onServerEvent(void Function(ServerEventPacket packet) handler) {
    _serverEventHandlers.add(handler);
  }

  /// Send a packet to the server.
  ///
  /// ```dart
  /// ClientNetwork.sendToServer(UIActionPacket(
  ///   screenId: currentScreenId,
  ///   widgetId: buttonId,
  ///   actionType: UIActionType.buttonClick,
  /// ));
  /// ```
  static void sendToServer(ModPacket packet) {
    if (!_initialized) {
      // ignore: avoid_print
      print('[ClientNetwork] sendToServer: Not initialized!');
      return;
    }

    final bytes = packet.encodePayload();
    // ignore: avoid_print
    print('[ClientNetwork] sendToServer: typeId=0x${packet.typeId.toRadixString(16)}, bytes=${bytes.length}');
    final nativeBytes = calloc<Uint8>(bytes.length);

    try {
      for (var i = 0; i < bytes.length; i++) {
        nativeBytes[i] = bytes[i];
      }
      _clientSendPacketToServer(packet.typeId, nativeBytes, bytes.length);
      // ignore: avoid_print
      print('[ClientNetwork] sendToServer: Sent to native!');
    } finally {
      calloc.free(nativeBytes);
    }
  }

  /// Send a UI action packet to the server.
  static void sendUIAction(int screenId, int widgetId, UIActionType actionType,
      {Map<String, dynamic>? data}) {
    sendToServer(UIActionPacket(
      screenId: screenId,
      widgetId: widgetId,
      actionType: actionType,
      data: data,
    ));
  }

  /// Request data from the server.
  static void requestData(RequestDataType requestType, Map<String, dynamic> parameters,
      {int? requestId}) {
    sendToServer(RequestDataPacket(
      requestType: requestType,
      parameters: parameters,
      requestId: requestId,
    ));
  }

  /// Send a custom client event to the server.
  static void sendClientEvent(String eventName, Map<String, dynamic> payload) {
    sendToServer(ClientEventPacket(
      eventName: eventName,
      payload: payload,
    ));
  }

  /// Send a container data update to the server.
  ///
  /// Used when client-side UI changes a SyncedInt value that needs to be
  /// synchronized back to the server (e.g., slider changes, numeric inputs).
  static void sendContainerData(int menuId, int slotIndex, int value) {
    // ignore: avoid_print
    print('[ClientNetwork] sendContainerData: menuId=$menuId, slotIndex=$slotIndex, value=$value');
    sendToServer(ContainerDataUpdatePacket(
      menuId: menuId,
      slotIndex: slotIndex,
      value: value,
    ));
  }
}

// Native callback signatures
typedef _SendPacketToServerCallbackNative = Void Function(
    Int32 packetType, Pointer<Uint8> data, Int32 dataLength);

// Native function typedefs
typedef _ClientRegisterPacketReceivedHandler = void Function(
    Pointer<NativeFunction<_PacketReceivedCallbackNative>>);
typedef _ClientSetSendPacketToServerCallback = void Function(
    Pointer<NativeFunction<_SendPacketToServerCallbackNative>>);
typedef _ClientSendPacketToServer = void Function(
    int, Pointer<Uint8>, int);
