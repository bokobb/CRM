import 'package:flutter/material.dart';

/// Палитра и типографика техкарт (экран «Производство») — повторяет образец-артефакт
/// «Техкарты выпечки»: Unbounded для заголовков, Onest для текста, JetBrains Mono для цифр.
class TK {
  static const bg = Color(0xFFF3F4F2);
  static const paper = Color(0xFFFFFFFF);
  static const ink = Color(0xFF1F2421);
  static const muted = Color(0xFF667068);
  static const line = Color(0xFFDCE0DB);
  static const soft = Color(0xFFEEF0EC);
  static const accent = Color(0xFF5B3A2E);
  static const yolk = Color(0xFFE0A526);
  static const yolkBg = Color(0xFFFBF0D3);
  static const yolkInk = Color(0xFF6B4A00);
  static const good = Color(0xFF2F7D4F);
  static const bad = Color(0xFFB23A2E);

  static const display = 'Unbounded';
  static const body = 'Onest';
  static const mono = 'JetBrainsMono';

  static const h1 = TextStyle(fontFamily: display, fontSize: 20, fontWeight: FontWeight.w700, color: ink, height: 1.2);
  static const h2 = TextStyle(fontFamily: display, fontSize: 14, fontWeight: FontWeight.w500, color: ink, letterSpacing: 0.3, height: 1.2);
  static const label = TextStyle(fontFamily: body, fontSize: 11, fontWeight: FontWeight.w600, color: muted, letterSpacing: 0.9);
  static const note = TextStyle(fontFamily: body, fontSize: 12, color: muted);
  static const text = TextStyle(fontFamily: body, fontSize: 14, color: ink);
  static const num = TextStyle(fontFamily: mono, fontSize: 13.5, color: ink, fontFeatures: [FontFeature.tabularFigures()]);

  /// Тема для экранов техкарт: шрифт Onest, фон и поля ввода как в образце.
  static ThemeData theme(ThemeData base) => base.copyWith(
        scaffoldBackgroundColor: bg,
        textTheme: base.textTheme.apply(fontFamily: body, bodyColor: ink, displayColor: ink),
        appBarTheme: base.appBarTheme.copyWith(
          backgroundColor: bg,
          foregroundColor: ink,
          surfaceTintColor: Colors.transparent,
          titleTextStyle: const TextStyle(fontFamily: display, fontSize: 17, fontWeight: FontWeight.w700, color: ink),
        ),
        inputDecorationTheme: const InputDecorationTheme(
          filled: true,
          fillColor: soft,
          isDense: true,
          labelStyle: TextStyle(fontFamily: body, color: muted),
          contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 10),
          border: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(6)), borderSide: BorderSide.none),
          enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(6)), borderSide: BorderSide.none),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.all(Radius.circular(6)),
            borderSide: BorderSide(color: yolk, width: 2),
          ),
        ),
        colorScheme: base.colorScheme.copyWith(primary: accent, surface: paper),
      );
}

/// Подпись капсом, как `.label` в образце.
class TKLabel extends StatelessWidget {
  final String text;
  final Color? color;

  const TKLabel(this.text, {super.key, this.color});

  @override
  Widget build(BuildContext context) =>
      Text(text.toUpperCase(), style: color == null ? TK.label : TK.label.copyWith(color: color));
}

/// Таблетка-переключатель (вкладки товаров, фильтры): выбранная — коричневая.
class TKPill extends StatelessWidget {
  final String text;
  final bool selected;
  final VoidCallback? onTap;

  const TKPill({super.key, required this.text, this.selected = false, this.onTap});

  @override
  Widget build(BuildContext context) => Material(
        color: selected ? TK.accent : TK.paper,
        shape: StadiumBorder(side: BorderSide(color: selected ? TK.accent : TK.line)),
        child: InkWell(
          customBorder: const StadiumBorder(),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            child: Text(
              text,
              style: TextStyle(
                fontFamily: TK.body,
                fontSize: 14,
                fontWeight: FontWeight.w500,
                color: selected ? Colors.white : TK.ink,
              ),
            ),
          ),
        ),
      );
}

/// Значок ККТ — жёлтая таблетка.
class TKCcp extends StatelessWidget {
  const TKCcp({super.key});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: TK.yolkBg,
          border: Border.all(color: TK.yolk),
          borderRadius: BorderRadius.circular(999),
        ),
        child: const Text('ККТ',
            style: TextStyle(fontFamily: TK.body, fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: TK.yolkInk)),
      );
}
