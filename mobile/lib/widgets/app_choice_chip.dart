import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Чип для рядов фильтров по статусу — единый вид на экранах "Заказы"/"Заявки на сырьё".
class AppChoiceChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const AppChoiceChip({super.key, required this.label, required this.selected, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        label: Text(
          label,
          style: TextStyle(
            color: selected ? context.pal.bg : context.pal.ink,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
          ),
        ),
        selected: selected,
        showCheckmark: false,
        selectedColor: context.pal.ink,
        backgroundColor: context.pal.card,
        side: BorderSide(color: selected ? context.pal.ink : context.pal.line),
        onSelected: (_) => onTap(),
      ),
    );
  }
}
