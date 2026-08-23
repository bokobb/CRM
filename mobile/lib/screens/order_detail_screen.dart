import 'package:flutter/material.dart';
import 'package:printing/printing.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../services/invoice_pdf.dart';
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
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('Отгрузил'),
          ),
        ],
      ),
    );
    if (plate != null && plate.isNotEmpty) {
      _runAction(() => _api.shipOrder(o.id, plate));
    }
  }

  Future<void> _share(Order o) async {
    final bytes = await buildInvoicePdf(o);
    await Printing.sharePdf(bytes: bytes, filename: 'nakladnaya_${o.id}.pdf');
  }

  Future<void> _cancelDialog(
    Order o, {
    required String title,
    required String confirmLabel,
  }) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Нет'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(confirmLabel),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      _runAction(() => _api.cancelOrder(o.id));
    }
  }

  Future<void> _editItemsDialog(Order o) async {
    final controllers = {
      for (final item in o.items)
        item.id: TextEditingController(text: formatQty(item.quantity)),
    };
    String? sheetError;
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) => Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).viewInsets.bottom,
        ),
        child: StatefulBuilder(
          builder: (context, setSheetState) => SizedBox(
            height: MediaQuery.of(context).size.height * 0.75,
            child: Column(
              children: [
                const Padding(
                  padding: EdgeInsets.fromLTRB(16, 16, 16, 4),
                  child: Text(
                    'Скорректировать состав',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                ),
                const Padding(
                  padding: EdgeInsets.fromLTRB(16, 0, 16, 8),
                  child: Text(
                    'Если товара нет в наличии — поставьте 0, позиция будет удалена из заказа',
                    style: TextStyle(color: Colors.grey, fontSize: 12),
                  ),
                ),
                Expanded(
                  child: ListView.separated(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    itemCount: o.items.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 10),
                    itemBuilder: (context, i) {
                      final item = o.items[i];
                      return Row(
                        children: [
                          Expanded(
                            child: Text(
                              item.productName,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 12),
                          SizedBox(
                            width: 90,
                            child: TextField(
                              controller: controllers[item.id],
                              keyboardType:
                                  const TextInputType.numberWithOptions(
                                    decimal: true,
                                  ),
                              textAlign: TextAlign.right,
                              decoration: const InputDecoration(isDense: true),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      if (sheetError != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(
                            sheetError!,
                            style: const TextStyle(color: Colors.red),
                          ),
                        ),
                      SizedBox(
                        width: double.infinity,
                        child: FilledButton(
                          onPressed: () async {
                            final quantities = <int, double>{};
                            for (final entry in controllers.entries) {
                              final v = double.tryParse(
                                entry.value.text.trim().replaceAll(',', '.'),
                              );
                              if (v == null) {
                                setSheetState(
                                  () => sheetError =
                                      'Проверьте введённые количества',
                                );
                                return;
                              }
                              quantities[entry.key] = v;
                            }
                            if (quantities.values.every((v) => v <= 0)) {
                              setSheetState(
                                () => sheetError = 'В заказе должна остаться хотя бы одна позиция',
                              );
                              return;
                            }
                            try {
                              await _api.updateOrderItems(o.id, quantities);
                              if (context.mounted) Navigator.pop(context, true);
                            } catch (e) {
                              setSheetState(() => sheetError = e.toString());
                            }
                          },
                          child: const Text('Сохранить'),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (saved == true) await _refresh();
  }

  Future<void> _setDebtDialog(Order o) async {
    final dueDate = await showDatePicker(
      context: context,
      initialDate: DateTime.now().add(const Duration(days: 7)),
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 365)),
      helpText: 'Срок оплаты долга',
    );
    if (dueDate != null) {
      _runAction(() => _api.setOrderDebt(o.id, dueDate));
    }
  }

  @override
  Widget build(BuildContext context) {
    final role = ApiClient.currentSession?.role;
    final canManageOrder =
        role == 'SALES' || role == 'ADMIN' || role == 'ACCOUNTANT';
    final canShip = role == 'WAREHOUSE_FG' || role == 'ADMIN';
    final canComplete = role == 'ACCOUNTANT' || role == 'ADMIN';
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
              return ErrorView(
                error: snapshot.error.toString(),
                onRetry: _refresh,
              );
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
                          Text(
                            o.clientName,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          Text(
                            'создан ${formatDateTime(o.createdAt)}'
                            '${o.dueDate != null ? ' · срок ${formatDate(o.dueDate!)}' : ''}',
                            style: const TextStyle(
                              color: Colors.grey,
                              fontSize: 12,
                            ),
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
                    child: Text(
                      _error!,
                      style: const TextStyle(color: Colors.red),
                    ),
                  ),
                if (o.vehicleNumber != null) ...[
                  const SizedBox(height: 12),
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.local_shipping_outlined),
                      title: Text('ТС: ${o.vehicleNumber}'),
                      subtitle: Text(
                        'Отгрузил ${o.shippedByName ?? "—"}${o.shippedAt != null ? ' · ${formatDateTime(o.shippedAt!)}' : ''}',
                      ),
                    ),
                  ),
                ],
                const SizedBox(height: 20),
                Text(
                  'Состав заказа',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 8),
                Card(
                  child: Column(
                    children: [
                      for (final item in o.items)
                        ListTile(
                          leading: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 6,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(0xFFB8324A)
                                  .withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              formatQty(item.quantity),
                              style: const TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                                color: Color(0xFF8F2438),
                              ),
                            ),
                          ),
                          title: Text(item.productName),
                          subtitle: Text('× ${formatMoney(item.price)}'),
                          trailing: Text(
                            formatMoney(item.price * item.quantity),
                          ),
                        ),
                      const Divider(height: 1),
                      ListTile(
                        title: const Text(
                          'Итого',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        trailing: Text(
                          formatMoney(o.totalPrice),
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
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
                  Text(
                    'Задачи цеха',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  if (o.productionTasks.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 12),
                      child: Text(
                        'Для этого заказа цеховых задач нет',
                        style: TextStyle(color: Colors.grey),
                      ),
                    )
                  else
                    Card(
                      child: Column(
                        children: [
                          for (final t in o.productionTasks)
                            ListTile(
                              title: Text(
                                '${t.productName} × ${formatQty(t.quantity)}',
                              ),
                              trailing: StatusBadge(
                                status: t.status,
                                label: t.statusLabel,
                              ),
                            ),
                        ],
                      ),
                    ),
                ],
                if (canManageOrder || canShip || canComplete) ...[
                  const SizedBox(height: 28),
                  if (o.status == 'NEW' && canShip) ...[
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: _actionInProgress
                            ? null
                            : () => _runAction(() => _api.confirmOrder(o.id)),
                        icon: const Icon(Icons.check_circle_outline),
                        label: const Text('Заказ собран'),
                      ),
                    ),
                    const SizedBox(height: 10),
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        onPressed: _actionInProgress
                            ? null
                            : () => _editItemsDialog(o),
                        icon: const Icon(Icons.edit_outlined),
                        label: const Text('Редактировать состав'),
                      ),
                    ),
                    const SizedBox(height: 10),
                  ],
                  if (o.status == 'READY' && canShip) ...[
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: _actionInProgress
                            ? null
                            : () => _shipDialog(o),
                        icon: const Icon(Icons.local_shipping_outlined),
                        label: const Text('Отгрузил'),
                      ),
                    ),
                    const SizedBox(height: 10),
                  ],
                  if (o.status == 'DELIVERED' && canComplete) ...[
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: _actionInProgress
                            ? null
                            : () => _runAction(() => _api.completeOrder(o.id)),
                        icon: const Icon(Icons.task_alt),
                        label: Text(o.hasUnpaidDebt ? 'Оплачено, завершить' : 'Завершить заказ'),
                      ),
                    ),
                    const SizedBox(height: 10),
                    if (!o.hasUnpaidDebt) ...[
                      SizedBox(
                        width: double.infinity,
                        child: OutlinedButton.icon(
                          style: OutlinedButton.styleFrom(
                            foregroundColor: const Color(0xFFD9A73F),
                            side: const BorderSide(color: Color(0xFFD9A73F)),
                          ),
                          onPressed: _actionInProgress
                              ? null
                              : () => _setDebtDialog(o),
                          icon: const Icon(Icons.hourglass_bottom_outlined),
                          label: const Text('Долг'),
                        ),
                      ),
                      const SizedBox(height: 10),
                    ],
                  ],
                  if (o.hasUnpaidDebt) ...[
                    Card(
                      color: const Color(0xFFD9A73F).withValues(alpha: 0.12),
                      child: ListTile(
                        leading: const Icon(
                          Icons.hourglass_bottom_outlined,
                          color: Color(0xFFD9A73F),
                        ),
                        title: Text('Долг до ${formatDate(o.debtDueDate!)}'),
                      ),
                    ),
                    const SizedBox(height: 10),
                  ],
                  if ((o.status == 'NEW' ||
                          o.status == 'IN_PRODUCTION' ||
                          o.status == 'READY') &&
                      (canManageOrder || canShip))
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: const Color(0xFFB8433F),
                          side: const BorderSide(color: Color(0xFFB8433F)),
                        ),
                        onPressed: _actionInProgress
                            ? null
                            : () => _cancelDialog(
                                o,
                                title: canShip && !canManageOrder
                                    ? 'Отказаться от заказа?'
                                    : 'Отменить заказ?',
                                confirmLabel: canShip && !canManageOrder
                                    ? 'Отказаться'
                                    : 'Отменить',
                              ),
                        icon: const Icon(Icons.cancel_outlined),
                        label: Text(
                          canShip && !canManageOrder
                              ? 'Отказ'
                              : 'Отменить заказ',
                        ),
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
