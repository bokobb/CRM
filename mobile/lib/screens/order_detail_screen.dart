import 'package:flutter/material.dart';
import 'package:printing/printing.dart';

import '../main.dart';
import '../models/order.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../services/invoice_pdf.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/status_badge.dart';
import '../widgets/success_feedback.dart';

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

  /// [celebrate] — звук + анимация + переход в список заказов на ключевых шагах жизненного
  /// цикла (подтверждение сборки, отгрузка, завершение, долг); на "Отменить"/"Редактировать
  /// состав" не уместна, поэтому по умолчанию выключена — там просто обновляем экран.
  Future<void> _runAction(Future<Order> Function() action, {bool celebrate = false}) async {
    setState(() {
      _actionInProgress = true;
      _error = null;
    });
    try {
      await action();
      if (celebrate) {
        if (!mounted) return;
        await playSuccessFeedback(context);
        if (!mounted) return;
        Navigator.of(context).pushNamedAndRemoveUntil('/orders', (route) => false);
        return;
      }
      await _refresh();
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _actionInProgress = false);
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
      _runAction(() => _api.shipOrder(o.id, plate), celebrate: true);
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

  /// Открывает выбор товара вместо позиции [item] — нужен, когда ИИ при разборе заказа из
  /// WhatsApp распознал не тот товар (например, "Коржик" вместо "Коржик Сдоба"). Список
  /// товаров подгружается один раз при открытии диалога редактирования состава.
  Future<void> _pickProduct(
    Map<int, Product> productOverrides,
    Map<int, String> productNames,
    StateSetter setSheetState,
    OrderItem item,
    List<Product> products,
  ) async {
    final picked = await showModalBottomSheet<Product>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (context) {
        var query = '';
        return StatefulBuilder(
          builder: (context, setPickerState) {
            final filtered = query.isEmpty
                ? products
                : products.where((p) => p.name.toLowerCase().contains(query.toLowerCase())).toList();
            return Padding(
              padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
              child: SizedBox(
                height: MediaQuery.of(context).size.height * 0.75,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                      child: TextField(
                        autofocus: true,
                        decoration: InputDecoration(
                          labelText: 'Товар вместо "${productNames[item.id]}"',
                          prefixIcon: const Icon(Icons.search),
                        ),
                        onChanged: (v) => setPickerState(() => query = v),
                      ),
                    ),
                    Expanded(
                      child: ListView.builder(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        itemCount: filtered.length,
                        itemBuilder: (context, i) {
                          final p = filtered[i];
                          return AppListTile(
                            leading: ProductAvatar(name: p.name, imageUrl: p.imageUrl),
                            title: p.name,
                            subtitle: formatMoney(p.price),
                            onTap: () => Navigator.pop(context, p),
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
    if (picked != null) {
      setSheetState(() {
        productOverrides[item.id] = picked;
        productNames[item.id] = picked.name;
      });
    }
  }

  Future<void> _editItemsDialog(Order o) async {
    List<Product> products;
    try {
      products = await _api.getProducts();
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
      return;
    }
    if (!mounted) return;
    final controllers = {
      for (final item in o.items)
        item.id: TextEditingController(text: formatQty(item.quantity)),
    };
    final productOverrides = <int, Product>{};
    final productNames = {
      for (final item in o.items) item.id: item.productName,
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
                      final isOverridden = productOverrides.containsKey(item.id);
                      return Row(
                        children: [
                          Expanded(
                            child: InkWell(
                              onTap: () => _pickProduct(
                                productOverrides,
                                productNames,
                                setSheetState,
                                item,
                                products,
                              ),
                              child: Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      productNames[item.id]!,
                                      overflow: TextOverflow.ellipsis,
                                      style: isOverridden
                                          ? const TextStyle(color: accent, fontWeight: FontWeight.w600)
                                          : null,
                                    ),
                                  ),
                                  const SizedBox(width: 4),
                                  const Icon(Icons.edit, size: 15, color: Colors.grey),
                                ],
                              ),
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
                            final productIdByItemId = {
                              for (final e in productOverrides.entries) e.key: e.value.id,
                            };
                            try {
                              await _api.updateOrderItems(
                                o.id,
                                quantities,
                                productIdByItemId: productIdByItemId.isEmpty ? null : productIdByItemId,
                              );
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
      _runAction(() => _api.setOrderDebt(o.id, dueDate), celebrate: true);
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
        title: FutureBuilder<Order>(
          future: _future,
          builder: (context, snapshot) {
            final fromWhatsapp = snapshot.data?.fromWhatsapp ?? false;
            return Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('Заказ #${widget.orderId}'),
                if (fromWhatsapp) ...[
                  const SizedBox(width: 8),
                  const Icon(Icons.chat_bubble_rounded, size: 18, color: Color(0xFF25D366)),
                ],
              ],
            );
          },
        ),
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
                          leading: ProductAvatar(
                            name: item.productName,
                            imageUrl: item.productImageUrl,
                          ),
                          title: Text(item.productName),
                          subtitle: RichText(
                            text: TextSpan(
                              style: DefaultTextStyle.of(context).style.copyWith(color: Colors.grey),
                              children: [
                                TextSpan(
                                  text: formatQty(item.quantity),
                                  style: const TextStyle(
                                    fontWeight: FontWeight.bold,
                                    color: Color(0xFF8F2438),
                                  ),
                                ),
                                TextSpan(text: ' × ${formatMoney(item.price)}'),
                              ],
                            ),
                          ),
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
                            : () => _runAction(() => _api.confirmOrder(o.id), celebrate: true),
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
                            : () => _runAction(() => _api.completeOrder(o.id), celebrate: true),
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
