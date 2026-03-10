import 'package:flutter/material.dart';

/// Animated pulsing microphone indicator shown while voice recording is active.
class VoiceListeningIndicator extends StatefulWidget {
  final bool isListening;
  final VoidCallback onTap;
  final double size;

  const VoiceListeningIndicator({
    super.key,
    required this.isListening,
    required this.onTap,
    this.size = 24,
  });

  @override
  State<VoiceListeningIndicator> createState() =>
      _VoiceListeningIndicatorState();
}

class _VoiceListeningIndicatorState extends State<VoiceListeningIndicator>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _scaleAnimation;
  late Animation<double> _opacityAnimation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    );

    _scaleAnimation = Tween<double>(begin: 1.0, end: 1.8).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOut),
    );

    _opacityAnimation = Tween<double>(begin: 0.6, end: 0.0).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOut),
    );

    if (widget.isListening) {
      _controller.repeat();
    }
  }

  @override
  void didUpdateWidget(covariant VoiceListeningIndicator oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isListening && !oldWidget.isListening) {
      _controller.repeat();
    } else if (!widget.isListening && oldWidget.isListening) {
      _controller.stop();
      _controller.reset();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onTap,
      child: SizedBox(
        width: widget.size * 2,
        height: widget.size * 2,
        child: Stack(
          alignment: Alignment.center,
          children: [
            // Pulsing ripple rings (only when listening)
            if (widget.isListening) ...[
              AnimatedBuilder(
                animation: _controller,
                builder: (context, child) {
                  return Transform.scale(
                    scale: _scaleAnimation.value,
                    child: Opacity(
                      opacity: _opacityAnimation.value,
                      child: Container(
                        width: widget.size * 1.6,
                        height: widget.size * 1.6,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: Colors.redAccent.withValues(alpha: 0.5),
                            width: 2,
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ],
            // Mic icon
            Icon(
              widget.isListening ? Icons.mic : Icons.mic_none,
              color: widget.isListening
                  ? Colors.redAccent
                  : Theme.of(context).iconTheme.color,
              size: widget.size,
            ),
          ],
        ),
      ),
    );
  }
}
