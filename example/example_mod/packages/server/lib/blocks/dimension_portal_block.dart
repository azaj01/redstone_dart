import 'package:dart_mod_server/dart_mod_server.dart';

/// Example block demonstrating the Dimension API.
///
/// When a player right-clicks this block, it teleports them to the
/// custom mining dimension. If they're already in the mining dimension,
/// it sends them back to the overworld.
class DimensionPortalBlock extends CustomBlock {
  static const _miningDimensionId = 'example_mod:mining_dimension';

  DimensionPortalBlock()
      : super(
          id: 'example_mod:dimension_portal',
          settings: BlockSettings(
            hardness: 5.0,
            resistance: 6.0,
            luminance: 10,
          ),
          model: BlockModel.cubeAll(
            texture: 'assets/textures/block/hello_block.png',
          ),
        );

  @override
  ActionResult onUse(int worldId, int x, int y, int z, int playerId, int hand) {
    final player = Players.getPlayer(playerId);
    if (player == null) return ActionResult.pass;

    final currentDim = player.dimension.dimensionId;
    if (currentDim == _miningDimensionId) {
      // Send back to overworld
      player.teleportToDimension('minecraft:overworld', Vec3(0, 64, 0));
      player.sendMessage('§5[Dimension Portal] §fReturning to the overworld...');
    } else {
      // Send to mining dimension
      player.teleportToDimension(_miningDimensionId, Vec3(0, 67, 0));
      player.sendMessage('§5[Dimension Portal] §fEntering the mining dimension...');
    }

    // Visual feedback
    final world = ServerWorld(currentDim);
    world.spawnParticles(
      Particles.portal,
      Vec3(x + 0.5, y + 1.0, z + 0.5),
      count: 30,
      delta: Vec3(0.5, 0.5, 0.5),
    );
    world.playSound(Vec3(x + 0.5, y + 0.5, z + 0.5), Sounds.levelUp, volume: 1.0);

    return ActionResult.success;
  }
}
