import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lottie/lottie.dart';

import '../theme/app_theme.dart';

/// Короткий "успех"-фидбек на ключевых шагах жизненного цикла заказа (оформление заказа,
/// подтверждение сборки, отгрузка, завершение, подтверждение заказа из WhatsApp): звук +
/// Lottie-анимация галочки (2с), перекрашенная в цвет кнопок приложения (context.pal.accent) через
/// ColorFiltered. Через Overlay, а не через диалог/route — так исключается зависание на
/// экране, если что-то пойдёт не так со стеком навигации сразу после вызова (например,
/// экран, вызвавший анимацию, сам параллельно уходит на другой роут).
Future<void> playSuccessFeedback(BuildContext context) async {
  SystemSound.play(SystemSoundType.alert);
  final overlay = Overlay.of(context, rootOverlay: true);
  final entry = OverlayEntry(
    builder: (context) => IgnorePointer(
      child: Container(
        color: Colors.black26,
        alignment: Alignment.center,
        child: SizedBox(
          width: 180,
          height: 180,
          child: ColorFiltered(
            colorFilter: ColorFilter.mode(context.pal.accent, BlendMode.srcIn),
            child: Lottie.asset('assets/animations/check_success.json', repeat: false),
          ),
        ),
      ),
    ),
  );
  overlay.insert(entry);
  await Future.delayed(const Duration(milliseconds: 2000));
  entry.remove();
}
