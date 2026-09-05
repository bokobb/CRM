import 'package:flutter/material.dart';

import 'app_list_tile.dart';

/// Полноэкранный bottom sheet с полем поиска — тап открывает лист, ввод сразу фильтрует
/// список. Тот же паттерн, что уже использовался в order_form_screen.dart для выбора
/// клиента/товара, вынесен сюда для переиспользования (см. recipe_screen.dart).
Future<T?> pickFromList<T>({
  required BuildContext context,
  required String title,
  required List<T> items,
  required String Function(T) label,
  String Function(T)? subtitle,
  Widget Function(T)? leading,
}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (context) {
      var query = '';
      return StatefulBuilder(
        builder: (context, setSheetState) {
          final filtered = query.isEmpty
              ? items
              : items.where((i) => label(i).toLowerCase().contains(query.toLowerCase())).toList();
          return Padding(
            padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
            child: SizedBox(
              height: MediaQuery.of(context).size.height * 0.75,
              child: Column(
                children: [
                  const SizedBox(height: 10),
                  Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: const Color(0xFFE7E0D8),
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
                    child: TextField(
                      autofocus: true,
                      decoration: InputDecoration(labelText: title, prefixIcon: const Icon(Icons.search)),
                      onChanged: (v) => setSheetState(() => query = v),
                    ),
                  ),
                  Expanded(
                    child: ListView.builder(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      itemCount: filtered.length,
                      itemBuilder: (context, i) {
                        final item = filtered[i];
                        return AppListTile(
                          leading: leading != null ? leading(item) : InitialAvatar(text: label(item)),
                          title: label(item),
                          subtitle: subtitle != null ? subtitle(item) : null,
                          onTap: () => Navigator.pop(context, item),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      );
    },
  );
}
