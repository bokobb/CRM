import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Короткий "успех"-фидбек на ключевых шагах жизненного цикла заказа (подтверждение
/// сборки, отгрузка, завершение, подтверждение заказа из WhatsApp): звук + анимация
/// зелёной галочки (~0.7с). Через Overlay, а не через диалог/route — так исключается
/// зависание на экране, если что-то пойдёт не так со стеком навигации сразу после вызова
/// (например, экран, вызвавший анимацию, сам параллельно уходит на другой роут).
Future<void> playSuccessFeedback(BuildContext context) async {
  SystemSound.play(SystemSoundType.alert);
  final overlay = Overlay.of(context, rootOverlay: true);
  final entry = OverlayEntry(
    builder: (context) => IgnorePointer(
      child: Container(
        color: Colors.black26,
        alignment: Alignment.center,
        child: TweenAnimationBuilder<double>(
          tween: Tween(begin: 0, end: 1),
          duration: const Duration(milliseconds: 350),
          curve: Curves.elasticOut,
          builder: (context, value, child) => Transform.scale(scale: value, child: child),
          child: Container(
            width: 110,
            height: 110,
            decoration: const BoxDecoration(
              color: Colors.white,
              shape: BoxShape.circle,
              boxShadow: [BoxShadow(color: Color(0x33000000), blurRadius: 16)],
            ),
            child: const Icon(Icons.check_circle_rounded, color: Color(0xFF25D366), size: 84),
          ),
        ),
      ),
    ),
  );
  overlay.insert(entry);
  await Future.delayed(const Duration(milliseconds: 700));
  entry.remove();
}
