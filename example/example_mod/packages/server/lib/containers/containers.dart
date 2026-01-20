// Barrel export file for all containers
export 'test_chest_container.dart';
export 'simple_furnace_block.dart';
export 'simple_furnace_entity.dart';

import 'package:dart_mod_server/dart_mod_server.dart';

import '../blocks/animated_chest_entity.dart';
import '../blocks/test_chest_block.dart';
import 'test_chest_container.dart';

/// Registers all custom container types.
/// Must be called before blocks that use these containers.
void registerContainers() {
  // Initialize the container registry
  ContainerRegistry.init();

  // Register the test chest container type
  ContainerRegistry.registerSimple<TestChestContainer>(
    id: TestChestBlock.containerId,
    title: 'Test Chest',
    rows: 3,
    columns: 9,
    factory: (menuId) => TestChestContainer(menuId),
  );

  // Register the animated chest container type
  // This registration is needed so that the client can look up
  // the container ID by title when the DartBlockEntityMenu opens.
  // We reuse TestChestContainer since they have the same slot behavior
  // (allow any item in any slot).
  ContainerRegistry.registerSimple<TestChestContainer>(
    id: AnimatedChestContainer().id,
    title: 'Animated Chest',
    rows: 3,
    columns: 9,
    factory: (menuId) => TestChestContainer(menuId),
  );
}
