import 'package:flutter/material.dart';

/// Карточка-строка списка: используется вместо плоского ListTile+Divider на всех
/// экранах со списками (клиенты, товары, заказы, остатки и т.п.) для единого,
/// более "воздушного" дизайна списков во всём приложении.
class AppListTile extends StatelessWidget {
  final IconData? leadingIcon;
  final Color? leadingColor;
  final Widget? leading;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  const AppListTile({
    super.key,
    this.leadingIcon,
    this.leadingColor,
    this.leading,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final color = leadingColor ?? const Color(0xFFB8324A);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        leading: leading ??
            (leadingIcon != null
                ? CircleAvatar(
                    radius: 20,
                    backgroundColor: color.withValues(alpha: 0.12),
                    child: Icon(leadingIcon, color: color, size: 20),
                  )
                : null),
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: subtitle != null && subtitle!.isNotEmpty
            ? Text(subtitle!, style: const TextStyle(color: Colors.grey, fontSize: 12.5))
            : null,
        trailing: trailing,
        onTap: onTap,
        onLongPress: onLongPress,
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
    final c = color ?? const Color(0xFFB8324A);
    final letter = text.trim().isEmpty ? '?' : text.trim()[0].toUpperCase();
    return CircleAvatar(
      radius: 20,
      backgroundColor: c.withValues(alpha: 0.14),
      child: Text(letter, style: TextStyle(color: c, fontWeight: FontWeight.bold, fontSize: 16)),
    );
  }
}
