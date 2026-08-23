import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/status_badge.dart';

class OrderDetailScreen extends StatefulWidget {
  final int orderId;

  const OrderDetailScreen({super.key, required this.orderId});

  @override
  State<OrderDetailScreen> createState() => _OrderDetailScreenState();
}

class _OrderDetailScreenState extends State<OrderDetailScreen> {
  final _api = ApiClient();
  late Future<Order> _future;
  bool _actionInProgress = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _api.getOrder(widget.orderId);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getOrder(widget.orderId);
    });
    await _future;
  }

  Future<void> _runAction(Future<Order> Function() action) async {
    setState(() {
      _actionInProgress = true;
      _error = null;
    });
    try {
      await action();
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      setState(() => _actionInProgress = false);
    }
  }

  Future<void> _shipDialog(Order o) async {
    final controller = TextEditingController();
    final plate = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Отгрузка со склада'),
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
    if (plate != null && plate.isNotEmpty) {
      _runAction(() => _api.shipOrder(o.id, plate));
    }
  }

  void _share(Order o) {
    final buf = StringBuffer()
      ..writeln('Накладная #${o.id}')
      ..writeln('Клиент: ${o.clientName}')
      ..writeln('Дата: ${formatDateTime(o.createdAt)}')
      ..writeln('---');
    for (final item in o.items) {
      buf.writeln('${item.productName}: ${formatQty(item.quantity)} × ${formatMoney(item.price)} = '
          '${formatMoney(item.price * item.quantity)}');
    }
    buf
      ..writeln('---')
      ..writeln('Итого: ${formatMoney(o.totalPrice)}');
    if (o.vehicleNumber != null) {
      buf.writeln('ТС: ${o.vehicleNumber}');
      if (o.shippedAt != null) buf.writeln('Отгружено: ${formatDateTime(o.shippedAt!)}');
    }
    Share.share(buf.toString(), subject: 'Накладная #${o.id}');
  }

  @override
  Widget build(BuildContext context) {
    final role = ApiClient.currentSession?.role;
    final canManageOrder = role == 'SALES' || role == 'ADMIN';
    final canShip = role == 'WAREHOUSE_FG' || role == 'ADMIN';
    return Scaffold(
      appBar: AppBar(
        title: Text('Заказ #${widget.orderId}'),
        actions: [
          FutureBuilder<Order>(
            future: _future,
            builder: (context, snapshot) {
              if (!snapshot.hasData) return const SizedBox.shrink();
              return IconButton(
                icon: const Icon(Icons.share_outlined),
                onPressed: () => _share(snapshot.data!),
              );
            },
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<Order>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final o = snapshot.data!;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(o.clientName, style: Theme.of(context).textTheme.titleMedium),
                          Text(
                            'создан ${formatDateTime(o.createdAt)}'
                            '${o.dueDate != null ? ' · срок ${formatDate(o.dueDate!)}' : ''}',
                            style: const TextStyle(color: Colors.grey, fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                    StatusBadge(status: o.status, label: o.statusLabel),
                  ],
                ),
                const SizedBox(height: 16),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),
                if (canManageOrder || canShip)
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      if (o.status == 'NEW' && canManageOrder)
                        FilledButton(
                          onPressed: _actionInProgress ? null : () => _runAction(() => _api.confirmOrder(o.id)),
                          child: const Text('Подтвердить (готов к отгрузке)'),
                        ),
                      if (o.status == 'READY' && canShip)
                        FilledButton(
                          onPressed: _actionInProgress ? null : () => _shipDialog(o),
                          child: const Text('Отгрузил'),
                        ),
                      if ((o.status == 'NEW' || o.status == 'IN_PRODUCTION' || o.status == 'READY') && canManageOrder)
                        OutlinedButton(
                          style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFB8433F)),
                          onPressed: _actionInProgress
                              ? null
                              : () async {
                                  final confirmed = await showDialog<bool>(
                                    context: context,
                                    builder: (context) => AlertDialog(
                                      title: const Text('Отменить заказ?'),
                                      actions: [
                                        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Нет')),
                                        TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Да')),
                                      ],
                                    ),
                                  );
                                  if (confirmed == true) {
                                    _runAction(() => _api.cancelOrder(o.id));
                                  }
                                },
                          child: const Text('Отменить заказ'),
                        ),
                    ],
                  ),
                if (o.vehicleNumber != null) ...[
                  const SizedBox(height: 12),
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.local_shipping_outlined),
                      title: Text('ТС: ${o.vehicleNumber}'),
                      subtitle: Text(
                          'Отгрузил ${o.shippedByName ?? "—"}${o.shippedAt != null ? ' · ${formatDateTime(o.shippedAt!)}' : ''}'),
                    ),
                  ),
                ],
                const SizedBox(height: 20),
                Text('Состав заказа', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                Card(
                  child: Column(
                    children: [
                      for (final item in o.items)
                        ListTile(
                          title: Text(item.productName),
                          subtitle: Text('${formatQty(item.quantity)} × ${formatMoney(item.price)}'),
                          trailing: Text(formatMoney(item.price * item.quantity)),
                        ),
                      const Divider(height: 1),
                      ListTile(
                        title: const Text('Итого', style: TextStyle(fontWeight: FontWeight.bold)),
                        trailing: Text(formatMoney(o.totalPrice), style: const TextStyle(fontWeight: FontWeight.bold)),
                      ),
                    ],
                  ),
                ),
                if (o.notes != null && o.notes!.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(o.notes!, style: const TextStyle(color: Colors.grey)),
                ],
                if (role != 'ACCOUNTANT' && role != 'WAREHOUSE_FG') ...[
                  const SizedBox(height: 20),
                  Text('Задачи цеха', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  if (o.productionTasks.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 12),
                      child: Text('Для этого заказа цеховых задач нет', style: TextStyle(color: Colors.grey)),
                    )
                  else
                    Card(
                      child: Column(
                        children: [
                          for (final t in o.productionTasks)
                            ListTile(
                              title: Text('${t.productName} × ${formatQty(t.quantity)}'),
                              trailing: StatusBadge(status: t.status, label: t.statusLabel),
                            ),
                        ],
                      ),
                    ),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}
