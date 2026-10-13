import 'package:flutter/material.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';
import 'format.dart';
import 'hidden_search.dart';

Color orderStatusColor(AppPalette p, String status) => switch (status) {
      'PLACED' => p.accent,
      'NEW' => p.warn,
      'CONFIRMED' || 'IN_PRODUCTION' || 'READY' => p.info,
      'DELIVERED' => p.success,
      'CANCELLED' || 'NO_SHOW' => p.danger,
      _ => p.muted,
    };

/// Компоновка заказов «Таблица»: плотные строки №/клиент/сумма/статус и «Итого» внизу.
class OrdersTableView extends StatelessWidget {
  final List<OrderSummary> orders;
  final String query;
  final void Function(OrderSummary) onOpen;

  const OrdersTableView({super.key, required this.orders, required this.query, required this.onOpen});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final active = orders.where((o) => o.status != 'CANCELLED');
    final total = active.fold<double>(0, (s, o) => s + o.totalPrice);
    const num = TextStyle(fontFeatures: [FontFeature.tabularFigures()]);
    Widget row(List<Widget> cells, {Color? bg, VoidCallback? onTap, double height = 46}) => Material(
          color: bg ?? Colors.transparent,
          child: InkWell(
            onTap: onTap,
            child: SizedBox(
              height: height,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Row(children: cells),
              ),
            ),
          ),
        );
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      child: Container(
        decoration: BoxDecoration(
          color: p.card,
          borderRadius: BorderRadius.circular(p.radius - 2),
          border: Border.all(color: p.line),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          children: [
            row(
              [
                SizedBox(width: 52, child: Text('№', style: TextStyle(fontSize: 12, color: p.muted))),
                Expanded(child: Text('КЛИЕНТ', style: TextStyle(fontSize: 12, color: p.muted, letterSpacing: 0.4))),
                SizedBox(
                  width: 104,
                  child: Text('СУММА', textAlign: TextAlign.right, style: TextStyle(fontSize: 12, color: p.muted, letterSpacing: 0.4)),
                ),
                const SizedBox(width: 30),
              ],
              bg: p.seg,
              height: 36,
            ),
            Expanded(
              child: ListView.separated(
                padding: EdgeInsets.zero,
                itemCount: orders.length,
                separatorBuilder: (_, _) => Divider(height: 1, color: p.line),
                itemBuilder: (context, i) {
                  final o = orders[i];
                  final c = orderStatusColor(p, o.status);
                  return row(
                    [
                      SizedBox(width: 52, child: Text('${o.id}', style: num.copyWith(fontSize: 13, color: p.muted))),
                      Expanded(
                        child: Text.rich(
                          TextSpan(children: highlightSpans(o.clientName, query)),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 14,
                            decoration: o.status == 'CANCELLED' ? TextDecoration.lineThrough : null,
                          ),
                        ),
                      ),
                      SizedBox(
                        width: 104,
                        child: Text(formatMoney(o.totalPrice), textAlign: TextAlign.right, style: num.copyWith(fontSize: 13.5)),
                      ),
                      SizedBox(
                        width: 30,
                        child: Center(
                          child: Tooltip(
                            message: o.statusLabel,
                            child: Container(width: 9, height: 9, decoration: BoxDecoration(color: c, shape: BoxShape.circle)),
                          ),
                        ),
                      ),
                    ],
                    bg: i.isOdd ? p.seg.withValues(alpha: 0.35) : null,
                    onTap: () => onOpen(o),
                  );
                },
              ),
            ),
            Container(
              color: p.seg,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
              child: Row(
                children: [
                  Expanded(
                    child: Text('Итого, ${active.length} заказ(ов)', style: TextStyle(fontSize: 13.5, color: p.muted)),
                  ),
                  Text(formatMoney(total), style: num.copyWith(fontSize: 14, fontWeight: FontWeight.w600)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Компоновка заказов «Доска по статусам»: колонки «Сборка → К отгрузке → Отгружен», листаются
/// вбок. Карточку можно перетащить (долгое нажатие) в следующую колонку — это то же действие,
/// что кнопки в карточке заказа: «Заказ собран» (склад ГП/админ) и «Отгрузил» с гос. номером.
class OrdersBoardView extends StatelessWidget {
  final List<OrderSummary> orders;
  final String query;
  final void Function(OrderSummary) onOpen;
  final VoidCallback onChanged;

  const OrdersBoardView({
    super.key,
    required this.orders,
    required this.query,
    required this.onOpen,
    required this.onChanged,
  });

  static const _columns = [
    ('PLACED', 'Новые'),
    ('CONFIRMED', 'Принят'),
    ('NEW', 'Сборка'),
    ('READY', 'К отгрузке'),
    ('DELIVERED', 'Отгружен'),
  ];

  /// «Не забрали» лежит в колонке «К отгрузке» (оттуда же его отгружают после переноса выдачи).
  static String _column(String status) => status == 'NO_SHOW' ? 'READY' : status;

  bool get _canMove {
    final role = ApiClient.currentSession?.role;
    return role == 'WAREHOUSE_FG' || role == 'ADMIN';
  }

  Future<void> _move(BuildContext context, OrderSummary o, String to) async {
    final api = ApiClient();
    final messenger = ScaffoldMessenger.of(context);
    try {
      if (o.status == 'PLACED' && to == 'CONFIRMED') {
        await api.acceptOrder(o.id);
      } else if (o.status == 'CONFIRMED' && to == 'NEW') {
        await api.startOrder(o.id);
      } else if (o.status == 'NEW' && to == 'READY') {
        final ok = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text('Заказ №${o.id} собран?'),
            content: Text('${o.clientName} — перевести в «Готов к отгрузке».'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Нет')),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Собран')),
            ],
          ),
        );
        if (ok != true) return;
        await api.confirmOrder(o.id);
      } else if ((o.status == 'READY' || o.status == 'NO_SHOW') && to == 'DELIVERED') {
        final controller = TextEditingController();
        final plate = await showDialog<String>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text('Отгрузка №${o.id}'),
            content: TextField(
              controller: controller,
              autofocus: true,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(labelText: 'Гос. номер ТС'),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
              FilledButton(onPressed: () => Navigator.pop(context, controller.text.trim()), child: const Text('Отгрузил')),
            ],
          ),
        );
        if (plate == null || plate.isEmpty) return;
        await api.shipOrder(o.id, plate);
      } else {
        return;
      }
      onChanged();
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    return LayoutBuilder(
      builder: (context, constraints) {
        final colWidth = (constraints.maxWidth * 0.78).clamp(240.0, 340.0);
        return ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          children: [
            for (var c = 0; c < _columns.length; c++) ...[
              if (c > 0) const SizedBox(width: 12),
              SizedBox(
                width: colWidth,
                child: DragTarget<OrderSummary>(
                  onWillAcceptWithDetails: (d) =>
                      _canMove && c > 0 && _column(d.data.status) == _columns[c - 1].$1,
                  onAcceptWithDetails: (d) => _move(context, d.data, _columns[c].$1),
                  builder: (context, candidate, _) {
                    final items = orders.where((o) => _column(o.status) == _columns[c].$1).toList();
                    return Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: candidate.isNotEmpty ? p.tint(p.accent) : p.seg,
                        borderRadius: BorderRadius.circular(p.radius + 2),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Padding(
                            padding: const EdgeInsets.fromLTRB(4, 2, 4, 8),
                            child: Row(
                              children: [
                                Container(
                                  width: 10,
                                  height: 10,
                                  decoration: BoxDecoration(color: orderStatusColor(p, _columns[c].$1), shape: BoxShape.circle),
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(_columns[c].$2, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                                ),
                                Text('${items.length}', style: TextStyle(color: p.muted)),
                              ],
                            ),
                          ),
                          Expanded(
                            child: items.isEmpty
                                ? Center(child: Text('Пусто', style: TextStyle(color: p.muted)))
                                : ListView.separated(
                                    itemCount: items.length,
                                    separatorBuilder: (_, _) => const SizedBox(height: 8),
                                    itemBuilder: (context, i) {
                                      final card = _BoardCard(order: items[i], query: query, onTap: () => onOpen(items[i]));
                                      if (!_canMove || c == _columns.length - 1) return card;
                                      return LongPressDraggable<OrderSummary>(
                                        data: items[i],
                                        feedback: Material(
                                          color: Colors.transparent,
                                          child: SizedBox(width: colWidth - 20, child: Opacity(opacity: 0.9, child: card)),
                                        ),
                                        childWhenDragging: Opacity(opacity: 0.35, child: card),
                                        child: card,
                                      );
                                    },
                                  ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
            ],
          ],
        );
      },
    );
  }
}

class _BoardCard extends StatelessWidget {
  final OrderSummary order;
  final String query;
  final VoidCallback onTap;

  const _BoardCard({required this.order, required this.query, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final o = order;
    final date = o.shippedAt ?? o.createdAt;
    return Material(
      color: p.card,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(p.radius - 2),
        side: BorderSide(color: p.cardBorder),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(p.radius - 2),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text.rich(
                TextSpan(children: highlightSpans(o.clientName, query)),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w500),
              ),
              const SizedBox(height: 6),
              Row(
                children: [
                  Expanded(
                    child: Text('№${o.id} · ${formatDate(date)}', style: TextStyle(fontSize: 13, color: p.muted)),
                  ),
                  Text(
                    formatMoney(o.totalPrice),
                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w500, fontFeatures: [FontFeature.tabularFigures()]),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
