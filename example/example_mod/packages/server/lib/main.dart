// Server entry point for dual-runtime mode
//
// This file handles ALL game logic:
// - Block/Item/Entity registrations
// - Commands
// - Recipes
// - Loot tables
// - Event handlers
// - Custom AI goals
//
// It runs on the Server thread using the pure Dart VM (dart_dll).

// Server-side Dart mod API
import 'package:dart_mod_server/dart_mod_server.dart';

// MCP runtime for AI-controlled testing
import 'package:minecraft_mcp/runtime.dart';

// Feature modules
import 'block_entities/block_entities.dart';
import 'blocks/blocks.dart';
import 'commands/commands.dart';
import 'containers/containers.dart';
import 'entities/entities.dart';
import 'events/events.dart';
import 'items/items.dart';
import 'loot_tables/loot_tables.dart';
import 'recipes/recipes.dart';

/// Server-side entry point for the mod.
///
/// This is called when the server-side Dart VM is initialized.
/// Bridge.run() handles all initialization (native bridge, proxy handlers, etc.)
/// and defers registration until Java signals that registries are ready.
void main() {
  print('Server mod initialized!');

  Bridge.run(() {
    print('Registry ready - registering items, blocks, entities...');

    // =========================================================================
    // Register container types first
    // Containers must be registered BEFORE blocks that use them
    // =========================================================================
    registerContainers();

    // =========================================================================
    // Register your custom items here
    // Items must be registered BEFORE blocks that reference them as drops
    // =========================================================================
    registerItems();

    // =========================================================================
    // Register block entity types
    // Block entities must be registered BEFORE blocks that use them
    // =========================================================================
    registerBlockEntities();

    // =========================================================================
    // Register your custom blocks here
    // This MUST happen before the registry freezes (during mod initialization)
    // =========================================================================
    registerBlocks();

    // =========================================================================
    // Register custom entities
    // Demonstrates the Entity System API (CustomMonster, CustomAnimal, CustomProjectile)
    // =========================================================================
    registerEntities();

    // =========================================================================
    // Register custom commands
    // Demonstrates the Commands API
    // =========================================================================
    registerCommands();

    // =========================================================================
    // Register crafting recipes
    // Demonstrates the Recipes API
    // =========================================================================
    registerRecipes();

    // =========================================================================
    // Register loot table modifications
    // Demonstrates the LootTables API
    // =========================================================================
    registerLootTables();

    // =========================================================================
    // Register event handlers
    // Demonstrates the Events API
    // =========================================================================
    registerEventHandlers();

    print(
        'Server mod ready with ${BlockRegistry.blockCount} custom blocks and ${EntityRegistry.entityCount} custom entities!');
    print('  Commands: /heal, /feed, /fly, /spawn, /dtime, /diminfo, /spawnzombie, /spawncow, /fireball, /spawncustomzombie');
    print('  Items: DartItem, EffectWand, LightningWand, HealingOrb, TeleportStaff');
    print('  Blocks: HelloBlock, TerraformerBlock, MidasBlock, LightningRodBlock,');
    print('          MobSpawnerBlock, PartyBlock, WeatherControlBlock, EntityRadarBlock, TestChestBlock,');
    print('          ExampleFurnace (block entity demo with smelting!), DimensionPortalBlock');
    print('  Entities: DartZombie, DartCow, DartFireball, CustomGoalZombie (with Dart AI!)');
    print('  Containers: TestChest (Flutter slot integration demo)');
    print('  Dimensions: example_mod:mining_dimension (custom flat world with ores)');

    // Initialize MCP runtime for AI-controlled testing (if enabled)
    // This starts an HTTP server that allows external AI agents to control Minecraft
    initializeMcpRuntime();
  });
}
