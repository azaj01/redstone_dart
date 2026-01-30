import 'package:flutter/widgets.dart';
import '../theme/mc_colors.dart';
import '../theme/mc_theme.dart';

/// A Minecraft-style slider widget for integer values.
///
/// This is a convenience widget that wraps the slider functionality
/// for integer ranges (e.g., 0-100, 1-250, etc.) with automatic
/// value formatting.
class McIntSlider extends StatefulWidget {
  /// Current integer value.
  final int value;

  /// Minimum value (inclusive).
  final int min;

  /// Maximum value (inclusive).
  final int max;

  /// Called when the value changes.
  final ValueChanged<int>? onChanged;

  /// Called when dragging ends.
  final ValueChanged<int>? onChangeEnd;

  /// Optional prefix label (e.g., "Slider" shows "Slider: 250").
  final String? label;

  /// Whether to show the value in the label.
  final bool showValue;

  /// Optional custom value formatter.
  final String Function(int value)? valueFormatter;

  /// Whether the slider is enabled.
  final bool enabled;

  /// Slider width.
  final double width;

  /// Step size for value changes (e.g., 1 for every integer, 5 for multiples of 5).
  final int step;

  const McIntSlider({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    this.onChanged,
    this.onChangeEnd,
    this.label,
    this.showValue = true,
    this.valueFormatter,
    this.enabled = true,
    this.width = McSizes.buttonDefaultWidth,
    this.step = 1,
  }) : assert(min < max, 'min must be less than max'),
       assert(step > 0, 'step must be positive');

  @override
  State<McIntSlider> createState() => _McIntSliderState();
}

class _McIntSliderState extends State<McIntSlider> {
  bool _isDragging = false;
  bool _isHovered = false;

  /// Clamp value to valid range, then normalize to 0.0-1.0.
  double get _normalizedValue {
    final clampedValue = widget.value.clamp(widget.min, widget.max);
    return (clampedValue - widget.min) / (widget.max - widget.min);
  }

  /// The effective value, clamped to the valid range.
  int get _effectiveValue => widget.value.clamp(widget.min, widget.max);

  int _valueFromNormalized(double normalized) {
    final rawValue = widget.min + (normalized * (widget.max - widget.min));
    // Round to nearest step
    final steppedValue = ((rawValue - widget.min) / widget.step).round() * widget.step + widget.min;
    return steppedValue.clamp(widget.min, widget.max);
  }

  void _updateValue(double localX, double width, double scale) {
    final handleWidth = McSizes.sliderHandleWidth * scale;
    final trackWidth = width - handleWidth;
    final normalized = ((localX - handleWidth / 2) / trackWidth).clamp(0.0, 1.0);
    final newValue = _valueFromNormalized(normalized);
    if (newValue != widget.value) {
      widget.onChanged?.call(newValue);
    }
  }

  String get _displayLabel {
    final displayValue = _effectiveValue;
    final valueText = widget.valueFormatter?.call(displayValue) ?? displayValue.toString();
    if (widget.label != null && widget.showValue) {
      return '${widget.label}: $valueText';
    } else if (widget.label != null) {
      return widget.label!;
    } else if (widget.showValue) {
      return valueText;
    }
    return '';
  }

  @override
  Widget build(BuildContext context) {
    final scale = McTheme.scaleOf(context);
    final width = widget.width * scale;
    final height = McSizes.sliderHeight * scale;

    return MouseRegion(
      onEnter: widget.enabled ? (_) => setState(() => _isHovered = true) : null,
      onExit: widget.enabled ? (_) => setState(() => _isHovered = false) : null,
      cursor: widget.enabled ? SystemMouseCursors.click : SystemMouseCursors.basic,
      child: Listener(
        behavior: HitTestBehavior.opaque,
        onPointerDown: widget.enabled
            ? (event) {
                setState(() => _isDragging = true);
                _updateValue(event.localPosition.dx, width, scale);
              }
            : null,
        onPointerMove: widget.enabled
            ? (event) {
                if (_isDragging) {
                  _updateValue(event.localPosition.dx, width, scale);
                }
              }
            : null,
        onPointerUp: widget.enabled
            ? (_) {
                setState(() => _isDragging = false);
                widget.onChangeEnd?.call(_effectiveValue);
              }
            : null,
        onPointerCancel: widget.enabled
            ? (_) {
                setState(() => _isDragging = false);
              }
            : null,
        child: CustomPaint(
          painter: _McIntSliderPainter(
            value: _normalizedValue,
            isHovered: _isHovered,
            isDragging: _isDragging,
            isEnabled: widget.enabled,
            scale: scale,
          ),
          child: SizedBox(
            width: width,
            height: height,
            child: _displayLabel.isNotEmpty
                ? Center(
                    child: Text(
                      _displayLabel,
                      style: TextStyle(
                        fontFamily: 'Minecraft',
                        package: 'minecraft_ui',
                        fontSize: McTypography.fontHeight * scale,
                        color: widget.enabled ? McColors.white : McColors.lightGray,
                        shadows: [
                          Shadow(
                            color: McColors.black.withValues(alpha: 0.4),
                            offset: Offset(scale, scale),
                          ),
                        ],
                      ),
                    ),
                  )
                : null,
          ),
        ),
      ),
    );
  }
}

class _McIntSliderPainter extends CustomPainter {
  final double value;
  final bool isHovered;
  final bool isDragging;
  final bool isEnabled;
  final double scale;

  _McIntSliderPainter({
    required this.value,
    required this.isHovered,
    required this.isDragging,
    required this.isEnabled,
    required this.scale,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final borderWidth = 2.0 * scale;
    final handleWidth = McSizes.sliderHandleWidth * scale;

    // Track background
    final trackColor = isEnabled
        ? (isHovered ? McColors.buttonTopHovered : McColors.slotBackground)
        : McColors.buttonTopDisabled;
    canvas.drawRect(
      Rect.fromLTWH(0, 0, size.width, size.height),
      Paint()..color = trackColor,
    );

    // Track border
    final borderPaint = Paint()
      ..color = McColors.slotBorderDark
      ..style = PaintingStyle.stroke
      ..strokeWidth = borderWidth;
    canvas.drawRect(
      Rect.fromLTWH(
        borderWidth / 2,
        borderWidth / 2,
        size.width - borderWidth,
        size.height - borderWidth,
      ),
      borderPaint,
    );

    // Handle position
    final handleX = borderWidth + value * (size.width - handleWidth - borderWidth * 2);

    // Handle
    final handleColor = isDragging
        ? McColors.white
        : (isHovered ? McColors.buttonTopHovered : McColors.buttonTopNormal);
    canvas.drawRect(
      Rect.fromLTWH(handleX, borderWidth, handleWidth, size.height - borderWidth * 2),
      Paint()..color = handleColor,
    );

    // Handle border
    canvas.drawRect(
      Rect.fromLTWH(handleX, borderWidth, handleWidth, size.height - borderWidth * 2),
      Paint()
        ..color = McColors.slotBorderDark
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1 * scale,
    );
  }

  @override
  bool shouldRepaint(_McIntSliderPainter oldDelegate) {
    return value != oldDelegate.value ||
        isHovered != oldDelegate.isHovered ||
        isDragging != oldDelegate.isDragging ||
        isEnabled != oldDelegate.isEnabled;
  }
}
