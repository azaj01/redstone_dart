import 'dart:io';

import 'package:args/command_runner.dart';
import 'package:path/path.dart' as p;

import '../assets/asset_generator.dart';
import '../project/redstone_project.dart';
import '../util/logger.dart';

/// Command to create a distributable mod JAR with AOT-compiled Dart.
class PackageCommand extends Command<int> {
  @override
  final name = 'package';

  @override
  final description = 'Create a distributable mod JAR with AOT-compiled Dart.';

  PackageCommand() {
    argParser.addOption(
      'output',
      abbr: 'o',
      help: 'Output directory for the packaged JAR.',
    );
    argParser.addOption(
      'platform',
      abbr: 'p',
      help: 'Target platform for AOT compilation.',
      allowed: ['current', 'all'],
      defaultsTo: 'current',
    );
    argParser.addFlag(
      'verbose',
      abbr: 'v',
      help: 'Show verbose output.',
      negatable: false,
    );
  }

  @override
  Future<int> run() async {
    final project = RedstoneProject.find();
    if (project == null) {
      Logger.error('No Redstone project found.');
      Logger.info('Run this command from within a Redstone project directory.');
      return 1;
    }

    final outputDir = argResults!['output'] as String?;
    final platform = argResults!['platform'] as String;
    final verbose = argResults!['verbose'] as bool;

    if (verbose) {
      Logger.verbose = true;
    }

    Logger.newLine();
    Logger.header('Packaging ${project.name}...');
    Logger.newLine();

    try {
      // Step 1: Run datagen to generate manifest
      final datagenResult = await _runDatagen(project);
      if (datagenResult != 0) return datagenResult;

      // Step 2: Generate assets from manifest
      final assetResult = await _generateAssets(project);
      if (assetResult != 0) return assetResult;

      // Step 3: AOT compile Dart code
      final aotResult = await _compileAot(project, platform);
      if (aotResult != 0) return aotResult;

      // Step 4: Copy native libraries to staging
      final nativeResult = await _copyNativeLibs(project);
      if (nativeResult != 0) return nativeResult;

      // Step 5: Invoke Gradle to build the JAR
      final gradleResult = await _buildJar(project, outputDir);
      if (gradleResult != 0) return gradleResult;

      Logger.newLine();
      Logger.success('Package completed successfully!');
      if (outputDir != null) {
        Logger.info('Output: $outputDir');
      } else {
        Logger.info(
            'Output: ${p.join(project.minecraftDir, 'build', 'libs')}');
      }
      Logger.newLine();

      return 0;
    } catch (e) {
      Logger.error('Package failed: $e');
      return 1;
    }
  }

  /// Run datagen to generate manifest.json
  Future<int> _runDatagen(RedstoneProject project) async {
    // Use the configured datagen entry point, or fall back to main_datagen.dart
    // for Flutter mods, or main.dart as the final fallback
    final datagenScript = File(project.datagenEntry);
    final mainDatagenScript =
        File(p.join(project.rootDir, 'lib', 'main_datagen.dart'));

    String scriptPath;
    if (datagenScript.existsSync()) {
      scriptPath = p.relative(project.datagenEntry, from: project.rootDir);
    } else if (mainDatagenScript.existsSync()) {
      scriptPath = 'lib/main_datagen.dart';
    } else {
      scriptPath = 'lib/main.dart';
    }

    Logger.progress('Running datagen');
    final result = await Process.run(
      'dart',
      ['run', scriptPath],
      workingDirectory: project.rootDir,
      environment: {'REDSTONE_DATAGEN': 'true'},
    );

    if (result.exitCode != 0) {
      Logger.progressFailed();
      Logger.error('Datagen failed:');
      Logger.info(result.stderr.toString());
      return 1;
    }
    Logger.progressDone();
    return 0;
  }

  /// Generate assets (blockstates, models, textures) from manifest
  Future<int> _generateAssets(RedstoneProject project) async {
    Logger.progress('Generating assets');
    try {
      final generator = AssetGenerator(project);
      await generator.generate();
      Logger.progressDone();
      return 0;
    } catch (e) {
      Logger.progressFailed();
      Logger.error('Asset generation failed: $e');
      return 1;
    }
  }

  /// Compile Dart code to AOT snapshot
  Future<int> _compileAot(RedstoneProject project, String platform) async {
    Logger.progress('Compiling Dart to AOT');

    // Create staging directory for AOT artifacts
    final stagingDir = Directory(p.join(project.redstoneDir, 'package'));
    if (!stagingDir.existsSync()) {
      stagingDir.createSync(recursive: true);
    }

    // Determine entry point - for dual runtime, use server entry; otherwise main
    final entryPoint = project.hasDualRuntime
        ? project.serverEntry
        : project.entryPoint;

    final entryPointFile = File(entryPoint);
    if (!entryPointFile.existsSync()) {
      Logger.progressFailed();
      Logger.error('Entry point not found: $entryPoint');
      return 1;
    }

    // AOT snapshot output path
    final aotOutputPath = p.join(stagingDir.path, 'mod.aot');

    // Run dart compile aot-snapshot
    final result = await Process.run(
      'dart',
      [
        'compile',
        'aot-snapshot',
        '-o',
        aotOutputPath,
        entryPoint,
      ],
      workingDirectory: project.rootDir,
    );

    if (result.exitCode != 0) {
      Logger.progressFailed();
      Logger.error('AOT compilation failed:');
      Logger.info(result.stdout.toString());
      Logger.info(result.stderr.toString());
      return 1;
    }

    Logger.progressDone();
    Logger.debug('AOT snapshot created: $aotOutputPath');
    return 0;
  }

  /// Copy native libraries to staging directory
  Future<int> _copyNativeLibs(RedstoneProject project) async {
    Logger.progress('Staging native libraries');

    final nativeDir = Directory(p.join(project.redstoneDir, 'native'));
    final stagingDir = Directory(p.join(project.redstoneDir, 'package', 'natives'));

    if (!nativeDir.existsSync()) {
      Logger.progressDone();
      Logger.debug('No native libraries to copy');
      return 0;
    }

    if (!stagingDir.existsSync()) {
      stagingDir.createSync(recursive: true);
    }

    try {
      await for (final entity in nativeDir.list()) {
        if (entity is File) {
          final targetPath = p.join(stagingDir.path, p.basename(entity.path));
          entity.copySync(targetPath);
        }
      }
      Logger.progressDone();
      return 0;
    } catch (e) {
      Logger.progressFailed();
      Logger.error('Failed to copy native libraries: $e');
      return 1;
    }
  }

  /// Build the distributable JAR using Gradle
  Future<int> _buildJar(RedstoneProject project, String? outputDir) async {
    Logger.progress('Building JAR');

    // Prepare Gradle arguments
    final gradleArgs = ['packageMod'];

    // Pass staging directory paths to Gradle
    final stagingDir = p.join(project.redstoneDir, 'package');
    gradleArgs.add('-PaotPath=$stagingDir/mod.aot');
    gradleArgs.add('-PnativesPath=$stagingDir/natives');

    if (outputDir != null) {
      gradleArgs.add('-PoutputDir=$outputDir');
    }

    final result = await Process.run(
      './gradlew',
      gradleArgs,
      workingDirectory: project.minecraftDir,
    );

    if (result.exitCode != 0) {
      Logger.progressFailed();
      Logger.error('Gradle build failed:');
      Logger.info(result.stdout.toString());
      Logger.info(result.stderr.toString());
      Logger.newLine();
      Logger.info('Note: The "packageMod" Gradle task may not exist yet.');
      Logger.info('You may need to add it to your build.gradle file.');
      return 1;
    }

    Logger.progressDone();
    return 0;
  }
}
