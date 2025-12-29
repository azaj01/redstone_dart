import 'dart:io';

import 'package:redstone_cli/src/commands/command_runner.dart';

Future<void> main(List<String> args) async {
  final runner = RedstoneCommandRunner();

  try {
    await runner.run(args);
  } catch (e) {
    stderr.writeln('Error: $e');
    exit(1);
  }
}
