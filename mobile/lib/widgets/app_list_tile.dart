import 'package:flutter/material.dart';

import 'hidden_search.dart';
import '../theme/app_theme.dart';

/// Карточка-строка списка: используется вместо плоского ListTile+Divider на всех
/// экранах со списками (клиенты, товары, заказы, остатки и т.п.) для единого,
/// более "воздушного" дизайна списков во всём приложении.
class AppListTile extends StatelessWidget {
  final IconData? leadingIcon;
  final Color? leadingColor;
  final Widget? leading;
  final String title;
  final String? subtitle;

  /// Доп. строка под обычным (серым) subtitle — например, красным текстом "Недостаточно:
  /// ..." на карточке заявки (см. production_requests_screen.dart).
  final Widget? subtitleExtra;
  final Widget? trailing;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  /// Переопределяет цвет фона карточки — например, серым, чтобы визуально отличить завершённую
  /// заявку от остальных (см. production_requests_screen.dart). Null — обычный цвет темы.
  final Color? backgroundColor;

  /// Подсветить совпадения с этим текстом в заголовке (поиск лупой, см. HiddenSearch).
  final String? highlight;

  const AppListTile({
    super.key,
    this.leadingIcon,
    this.leadingColor,
    this.leading,
    required this.title,
    this.subtitle,
    this.subtitleExtra,
    this.trailing,
    this.onTap,
    this.onLongPress,
    this.backgroundColor,
    this.highlight,
  });

  @override
  Widget build(BuildContext context) {
    // Строгий вид: иконка нейтральная в сером квадрате; цветная — только если её задали явно.
    final color = leadingColor ?? context.pal.muted;
    return Card(
      color: backgroundColor,
      margin: const EdgeInsets.only(bottom: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(context.pal.radius - 2),
        side: BorderSide(color: context.pal.cardBorder),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
            leading: leading ??
                (leadingIcon != null
                    ? Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          color: leadingColor == null ? context.pal.seg : context.pal.tint(color),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Icon(leadingIcon, color: color, size: 20),
                      )
                    : null),
            title: Text.rich(
              TextSpan(children: highlightSpans(title, highlight)),
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
            ),
            subtitle: subtitle != null && subtitle!.isNotEmpty
                ? Text(subtitle!, style: TextStyle(color: context.pal.muted, fontSize: 12.5))
                : null,
            trailing: trailing,
            onTap: onTap,
            onLongPress: onLongPress,
          ),
          if (subtitleExtra != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 0, 14, 10),
              child: subtitleExtra!,
            ),
        ],
      ),
    );
  }
}

/// Круглый аватар с инициалом — для списков клиентов и похожих сущностей без иконки.
class InitialAvatar extends StatelessWidget {
  final String text;
  final Color? color;

  const InitialAvatar({super.key, required this.text, this.color});

  @override
  Widget build(BuildContext context) {
    final c = color ?? context.pal.accent;
    final letter = text.trim().isEmpty ? '?' : text.trim()[0].toUpperCase();
    return CircleAvatar(
      radius: 20,
      backgroundColor: c.withValues(alpha: 0.14),
      child: Text(letter, style: TextStyle(color: c, fontWeight: FontWeight.bold, fontSize: 16)),
    );
  }
}
