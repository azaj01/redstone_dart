import 'package:flutter/widgets.dart';
import '../theme/mc_colors.dart';
import '../theme/mc_theme.dart';

/// A Minecraft-style toggle button that switches between On and Off states.
///
/// Visually similar to [McButton] but displays "On" or "Off" (or custom labels)
/// and maintains a boolean state.
class McToggleButton extends StatefulWidget {
  /// Whether the toggle is currently on.
  final bool value;

  /// Called when the value changes.
  final ValueChanged<bool>? onChanged;

  /// Label shown when the toggle is on.
  final String onLabel;

  /// Label shown when the toggle is off.
  final String offLabel;

  /// Optional prefix text shown before On/Off label.
  final String? prefix;

  /// The button width.
  final double width;

  /// Whether the button is enabled.
  final bool enabled;

  const McToggleButton({
    super.key,
    required this.value,
    this.onChanged,
    this.onLabel = 'ON',
    this.offLabel = 'OFF',
    this.prefix,
    this.width = McSizes.buttonDefaultWidth,
    this.enabled = true,
  });

  @override
  State<McToggleButton> createState() => _McToggleButtonState();
}

class _McToggleButtonState extends State<McToggleButton> {
  bool _isHovered = false;
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    final scale = McTheme.scaleOf(context);
    final isEnabled = widget.enabled && widget.onChanged != null;

    // Determine colors based on state
    Color topColor;
    Color bottomColor;
    Color borderColor;
    Color textColor;
    Color valueColor;

    if (!isEnabled) {
      topColor = McColors.buttonTopDisabled;
      bottomColor = McColors.buttonBottomDisabled;
      borderColor = McColors.buttonBorder;
      textColor = McColors.lightGray;
      valueColor = McColors.lightGray;
    } else if (_isPressed) {
      topColor = McColors.buttonTopPressed;
      bottomColor = McColors.buttonBottomPressed;
      borderColor = McColors.buttonBorderPressed;
      textColor = McColors.white;
      valueColor = widget.value ? McColors.toggleOnPressed : McColors.toggleOffPressed;
    } else if (_isHovered) {
      topColor = McColors.buttonTopHovered;
      bottomColor = McColors.buttonBottomHovered;
      borderColor = McColors.buttonBorder;
      textColor = McColors.white;
      valueColor = widget.value ? McColors.toggleOn : McColors.toggleOff;
    } else {
      topColor = McColors.buttonTopNormal;
      bottomColor = McColors.buttonBottomNormal;
      borderColor = McColors.buttonBorder;
      textColor = McColors.white;
      valueColor = widget.value ? McColors.toggleOn : McColors.toggleOff;
    }

    final labelText = widget.value ? widget.onLabel : widget.offLabel;
    final displayText = widget.prefix != null ? '${widget.prefix}: $labelText' : labelText;

    return MouseRegion(
      onEnter: isEnabled ? (_) => setState(() => _isHovered = true) : null,
      onExit: isEnabled ? (_) => setState(() => _isHovered = false) : null,
      cursor: isEnabled ? SystemMouseCursors.click : SystemMouseCursors.basic,
      child: GestureDetector(
        onTapDown: isEnabled ? (_) => setState(() => _isPressed = true) : null,
        onTapUp: isEnabled
            ? (_) {
                setState(() => _isPressed = false);
                widget.onChanged?.call(!widget.value);
              }
            : null,
        onTapCancel: isEnabled ? () => setState(() => _isPressed = false) : null,
        child: CustomPaint(
          painter: _McToggleButtonPainter(
            topColor: topColor,
            bottomColor: bottomColor,
            borderColor: borderColor,
            borderWidth: McSizes.buttonBorderWidth * scale,
            isPressed: _isPressed,
          ),
          child: Container(
            width: widget.width * scale,
            height: McSizes.buttonHeight * scale,
            alignment: Alignment.center,
            padding: EdgeInsets.symmetric(
              horizontal: McSizes.buttonTextMargin * scale,
            ),
            child: widget.prefix != null
                ? RichText(
                    text: TextSpan(
                      style: TextStyle(
                        fontFamily: 'Minecraft',
                        package: 'minecraft_ui',
                        fontSize: McTypography.fontHeight * scale,
                        shadows: [
                          Shadow(
                            color: McColors.black.withValues(alpha: 0.4),
                            offset: Offset(
                              McTypography.shadowOffset * scale,
                              McTypography.shadowOffset * scale,
                            ),
                          ),
                        ],
                      ),
                      children: [
                        TextSpan(
                          text: '${widget.prefix}: ',
                          style: TextStyle(color: textColor),
                        ),
                        TextSpan(
                          text: labelText,
                          style: TextStyle(color: valueColor),
                        ),
                      ],
                    ),
                  )
                : Text(
                    displayText,
                    style: TextStyle(
                      fontFamily: 'Minecraft',
                      package: 'minecraft_ui',
                      fontSize: McTypography.fontHeight * scale,
                      color: valueColor,
                      shadows: [
                        Shadow(
                          color: McColors.black.withValues(alpha: 0.4),
                          offset: Offset(
                            McTypography.shadowOffset * scale,
                            McTypography.shadowOffset * scale,
                          ),
                        ),
                      ],
                    ),
                    textAlign: TextAlign.center,
                    overflow: TextOverflow.ellipsis,
                  ),
          ),
        ),
      ),
    );
  }
}

class _McToggleButtonPainter extends CustomPainter {
  final Color topColor;
  final Color bottomColor;
  final Color borderColor;
  final double borderWidth;
  final bool isPressed;

  _McToggleButtonPainter({
    required this.topColor,
    required this.bottomColor,
    required this.borderColor,
    required this.borderWidth,
    required this.isPressed,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final borderPaint = Paint()
      ..color = borderColor
      ..filterQuality = FilterQuality.none
      ..isAntiAlias = false;

    // Draw outer border
    canvas.drawRect(
      Rect.fromLTWH(0, 0, size.width, size.height),
      borderPaint,
    );

    // Draw gradient background inside border
    final gradientRect = Rect.fromLTWH(
      borderWidth,
      borderWidth,
      size.width - borderWidth * 2,
      size.height - borderWidth * 2,
    );

    final gradient = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [topColor, bottomColor],
    );

    final gradientPaint = Paint()
      ..shader = gradient.createShader(gradientRect)
      ..filterQuality = FilterQuality.none
      ..isAntiAlias = false;
    canvas.drawRect(gradientRect, gradientPaint);

    // Draw 3D edge highlights
    if (!isPressed) {
      final highlightPaint = Paint()
        ..color = McColors.white.withValues(alpha: 0.2)
        ..filterQuality = FilterQuality.none
        ..isAntiAlias = false;
      // Light top edge
      canvas.drawRect(
        Rect.fromLTWH(borderWidth, borderWidth, size.width - borderWidth * 2, 1),
        highlightPaint,
      );
      // Light left edge
      canvas.drawRect(
        Rect.fromLTWH(borderWidth, borderWidth, 1, size.height - borderWidth * 2),
        highlightPaint,
      );
    }
  }

  @override
  bool shouldRepaint(_McToggleButtonPainter oldDelegate) {
    return topColor != oldDelegate.topColor ||
        bottomColor != oldDelegate.bottomColor ||
        borderColor != oldDelegate.borderColor ||
        borderWidth != oldDelegate.borderWidth ||
        isPressed != oldDelegate.isPressed;
  }
}
