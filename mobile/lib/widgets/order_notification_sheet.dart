import 'package:flutter/material.dart';

import '../models/notification.dart';
import 'format.dart';
import 'status_badge.dart';
import '../theme/app_theme.dart';

/// Нижний лист с полной историей уведомлений по одному заказу — используется и на
/// дашборде, и на экране "Уведомления", чтобы группировка "один заказ — одна карточка"
/// выглядела одинаково в обоих местах.
void showOrderNotificationHistory(BuildContext context, OrderNotificationGroup g) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    builder: (context) => DraggableScrollableSheet(
      initialChildSize: 0.6,
      minChildSize: 0.3,
      maxChildSize: 0.9,
      expand: false,
      builder: (context, scrollController) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
            child: Row(
              children: [
                Expanded(
                  child: Text('Заказ №${g.orderId} (${g.clientName})',
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                ),
                StatusBadge(status: g.status, label: g.statusLabel),
              ],
            ),
          ),
          const Divider(height: 20),
          Expanded(
            child: ListView.separated(
              controller: scrollController,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              itemCount: g.history.length,
              separatorBuilder: (_, _) => const Divider(height: 20),
              itemBuilder: (context, i) {
                // history приходит от новых к старым — разворачиваем в хронологический порядок
                final n = g.history[g.history.length - 1 - i];
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(n.message),
                    const SizedBox(height: 2),
                    Text(formatDateTime(n.createdAt), style: TextStyle(color: context.pal.muted, fontSize: 12)),
                  ],
                );
              },
            ),
          ),
          const SizedBox(height: 8),
        ],
      ),
    ),
  );
}
