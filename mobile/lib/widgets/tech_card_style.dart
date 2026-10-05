import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Типографика техкарт (экран «Производство») — на общем деловом стиле приложения.
class TK {
  // Техкарты переведены на общий деловой стиль приложения (см. theme/app_theme.dart): цвета —
  // из темы через context.pal (день/ночь), шрифт — IBM Plex Sans; здесь остались только размеры.
  static const display = appFont;
  static const body = appFont;
  static const mono = appFont;

  static const h1 = TextStyle(fontFamily: appFont, fontSize: 20, fontWeight: FontWeight.w600, height: 1.2);
  static const h2 = TextStyle(fontFamily: appFont, fontSize: 14, fontWeight: FontWeight.w600, letterSpacing: 0.2, height: 1.2);
  static const label = TextStyle(fontFamily: appFont, fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 0.9);
  static const note = TextStyle(fontFamily: appFont, fontSize: 12);
  static const text = TextStyle(fontFamily: appFont, fontSize: 14);
  static const num = TextStyle(fontFamily: appFont, fontSize: 13.5, fontFeatures: [FontFeature.tabularFigures()]);

  /// Экраны техкарт используют общую тему приложения.
  static ThemeData theme(ThemeData base) => base;
}

/// Подпись капсом, как `.label` в образце.
class TKLabel extends StatelessWidget {
  final String text;
  final Color? color;

  const TKLabel(this.text, {super.key, this.color});

  @override
  Widget build(BuildContext context) =>
      Text(text.toUpperCase(), style: TK.label.copyWith(color: color ?? context.pal.muted));
}

/// Таблетка-переключатель (вкладки товаров, фильтры): выбранная — коричневая.
class TKPill extends StatelessWidget {
  final String text;
  final bool selected;
  final VoidCallback? onTap;

  const TKPill({super.key, required this.text, this.selected = false, this.onTap});

  @override
  Widget build(BuildContext context) => Material(
        color: selected ? context.pal.accent : context.pal.card,
        shape: StadiumBorder(side: BorderSide(color: selected ? context.pal.accent : context.pal.line)),
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
                color: selected ? Colors.white : context.pal.ink,
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
          color: context.pal.tint(context.pal.warn),
          border: Border.all(color: context.pal.warn),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text('ККТ',
            style: TextStyle(fontFamily: TK.body, fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: context.pal.warn)),
      );
}
