/// Minecraft-style widgets for Flutter-based GUIs.
library;

import 'package:flutter/widgets.dart';
import 'package:minecraft_ui/minecraft_ui.dart';

/// A Minecraft-style button widget.
class MinecraftButton extends StatelessWidget {
  /// The button label.
  final String label;

  /// Callback when the button is pressed.
  final VoidCallback? onPressed;

  /// Width of the button.
  final double width;

  /// Height of the button.
  final double height;

  /// Whether the button is enabled.
  final bool enabled;

  const MinecraftButton({
    super.key,
    required this.label,
    this.onPressed,
    this.width = 200,
    this.height = 20,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    return McButton(
      text: label,
      onPressed: onPressed,
      width: width,
      enabled: enabled,
    );
  }
}

/// A Minecraft-style text input widget.
class MinecraftTextField extends StatelessWidget {
  /// Initial text value.
  final String initialValue;

  /// Callback when text changes.
  final ValueChanged<String>? onChanged;

  /// Width of the text field.
  final double width;

  /// Height of the text field.
  final double height;

  /// Maximum text length.
  final int? maxLength;

  /// Placeholder text when empty.
  final String? placeholder;

  const MinecraftTextField({
    super.key,
    this.initialValue = '',
    this.onChanged,
    this.width = 200,
    this.height = 20,
    this.maxLength,
    this.placeholder,
  });

  @override
  Widget build(BuildContext context) {
    return McTextField(
      initialValue: initialValue,
      onChanged: onChanged,
      placeholder: placeholder,
      maxLength: maxLength,
      width: width,
    );
  }
}

/// A Minecraft-style slider widget.
class MinecraftSlider extends StatelessWidget {
  /// Current value (0.0 to 1.0).
  final double value;

  /// Callback when value changes.
  final ValueChanged<double>? onChanged;

  /// Width of the slider.
  final double width;

  /// Height of the slider.
  final double height;

  /// Label formatter.
  final String Function(double)? labelFormatter;

  const MinecraftSlider({
    super.key,
    required this.value,
    this.onChanged,
    this.width = 200,
    this.height = 20,
    this.labelFormatter,
  });

  @override
  Widget build(BuildContext context) {
    final label = labelFormatter?.call(value) ?? '${(value * 100).round()}%';
    return McSlider(
      value: value,
      onChanged: onChanged,
      width: width,
      label: label,
    );
  }
}

/// A Minecraft-style checkbox widget.
class MinecraftCheckbox extends StatelessWidget {
  /// Whether the checkbox is checked.
  final bool value;

  /// Callback when value changes.
  final ValueChanged<bool>? onChanged;

  /// Label text.
  final String? label;

  const MinecraftCheckbox({
    super.key,
    required this.value,
    this.onChanged,
    this.label,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => onChanged?.call(!value),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 20,
            height: 20,
            decoration: BoxDecoration(
              color: value ? const Color(0xFF00FF00) : const Color(0xFF404040),
              border: Border.all(color: const Color(0xFF000000)),
            ),
            child: value
                ? const Center(
                    child: Text(
                      'X',
                      style: TextStyle(color: Color(0xFF000000), fontSize: 14),
                    ),
                  )
                : null,
          ),
          if (label != null) ...[
            const SizedBox(width: 8),
            Text(
              label!,
              style: const TextStyle(color: Color(0xFFFFFFFF), fontSize: 14),
            ),
          ],
        ],
      ),
    );
  }
}
