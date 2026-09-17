import 'package:flutter/material.dart';
import 'package:printing/printing.dart';
import 'package:share_plus/share_plus.dart';

import '../models/order.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../services/invoice_pdf.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/receipt_card.dart';
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
  final Set<int> _checkedItems = {};

  @override
  void initState() {
    super.initState();
    _future = _api.getOrder(widget.orderId);
  }

  Future<void> _refresh() async {
    setState(() {
      _checkedItems.clear();
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

  /// Сначала пробуем накладную, приложенную к документу заказа в 1С (её туда кладёт
  /// бухгалтер/склад вручную) — если она уже есть, делимся именно ей. Если в 1С пока
  /// ничего не приложено (или заказ не выгружен, или 1С недоступна) — как раньше, строим
  /// PDF локально из данных заказа.
  Future<void> _share(Order o) async {
    InvoiceFile? remote;
    try {
      remote = await _api.getInvoiceFile(o.id);
    } catch (_) {
      remote = null;
    }
    if (remote != null) {
      await Share.shareXFiles(
        [XFile.fromData(remote.bytes, mimeType: remote.mimeType, name: remote.filename)],
        fileNameOverrides: [remote.filename],
      );
      return;
    }
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

  /// Общий выбор товара из списка (поиск по названию) — используется и для замены товара
  /// в существующей позиции, и для выбора товара новой позиции.
  Future<Product?> _pickAnyProduct(List<Product> products, String title) {
    return showModalBottomSheet<Product>(
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
                          labelText: title,
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
  }

  /// Точечное изменение количества одной позиции при сборке — без замены товара. Аналог
  /// _changeQuantity в production_request_detail_screen.dart.
  Future<void> _changeItemQuantity(Order o, OrderItem item) async {
    final qtyController = TextEditingController(text: formatQty(item.quantity));
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Количество «${item.productName}»'),
        content: TextField(
          controller: qtyController,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Количество'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Сохранить')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    final qty = double.tryParse(qtyController.text.replaceAll(',', '.'));
    if (qty == null || qty <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Введите корректное количество')));
      return;
    }
    try {
      await _api.updateOrderItems(o.id, {item.id: qty});
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  /// Удалить одну позицию из состава заказа при сборке (замена quantity=0 — см.
  /// OrderService.updateItems на бэкенде).
  Future<void> _deleteItem(Order o, OrderItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Удалить «${item.productName}» из заказа?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    try {
      await _api.updateOrderItems(o.id, {item.id: 0});
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  /// Добавить новую позицию в состав заказа при сборке — кнопка внизу чек-листа.
  Future<void> _addItemDialog(Order o) async {
    final List<Product> products;
    try {
      products = await _api.getProducts();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      return;
    }
    if (!mounted) return;
    final picked = await _pickAnyProduct(products, 'Добавить товар');
    if (picked == null || !mounted) return;

    final qtyController = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Добавить «${picked.name}»'),
        content: TextField(
          controller: qtyController,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Количество'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Добавить')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    final qty = double.tryParse(qtyController.text.replaceAll(',', '.'));
    if (qty == null || qty <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Введите корректное количество')));
      return;
    }
    try {
      await _api.updateOrderItems(o.id, {}, newItems: [(picked.id, qty)]);
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
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
    final canEditItems =
        role == 'WAREHOUSE_FG' || role == 'ADMIN' || role == 'ACCOUNTANT';
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
                if (o.status == 'NEW' && canEditItems) ...[
                  ReceiptCard(
                    title: 'Состав заказа',
                    icon: Icons.checklist_rounded,
                    children: [
                      if (o.items.isNotEmpty)
                        ReceiptSelectAllRow(
                          value: o.items.every((it) => _checkedItems.contains(it.id))
                              ? true
                              : (_checkedItems.isEmpty ? false : null),
                          onChanged: (selectAll) => setState(() {
                            if (selectAll == true) {
                              _checkedItems.addAll(o.items.map((it) => it.id));
                            } else {
                              _checkedItems.clear();
                            }
                          }),
                        ),
                      for (int i = 0; i < o.items.length; i++)
                        ReceiptRow(
                          index: i + 1,
                          name: o.items[i].isInsufficient
                              ? '${o.items[i].productName}\n(на складе: ${formatQty(o.items[i].availableQuantity)}'
                                  ' ${o.items[i].productUnit ?? "шт"})'
                              : o.items[i].productName,
                          qty: '${formatQty(o.items[i].quantity)} × ${formatMoney(o.items[i].price)}',
                          checkbox: _checkedItems.contains(o.items[i].id),
                          insufficient: o.items[i].isInsufficient,
                          onCheckboxChanged: (v) => setState(() {
                            if (v == true) {
                              _checkedItems.add(o.items[i].id);
                            } else {
                              _checkedItems.remove(o.items[i].id);
                            }
                          }),
                          trailingAction: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                icon: const Icon(Icons.edit_outlined, size: 20),
                                tooltip: 'Изменить количество',
                                visualDensity: VisualDensity.compact,
                                onPressed: () => _changeItemQuantity(o, o.items[i]),
                              ),
                              IconButton(
                                icon: const Icon(Icons.delete_outline, size: 20),
                                tooltip: 'Удалить позицию',
                                visualDensity: VisualDensity.compact,
                                onPressed: () => _deleteItem(o, o.items[i]),
                              ),
                            ],
                          ),
                        ),
                    ],
                    footer: Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Expanded(
                          child: InkWell(
                            borderRadius: BorderRadius.circular(4),
                            onTap: () => setState(() {
                              if (o.items.isNotEmpty && o.items.every((it) => _checkedItems.contains(it.id))) {
                                _checkedItems.clear();
                              } else {
                                _checkedItems.addAll(o.items.map((it) => it.id));
                              }
                            }),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 2),
                              child: Text(
                                o.items.any((i) => i.isInsufficient)
                                    ? 'Не хватает на складе — отмечено красным'
                                    : 'Отмечено ${_checkedItems.length} из ${o.items.length}',
                                softWrap: true,
                                style: TextStyle(
                                  color: o.items.any((i) => i.isInsufficient) ? const Color(0xFFC0392B) : Colors.grey,
                                  fontSize: 12.5,
                                  fontWeight: o.items.any((i) => i.isInsufficient) ? FontWeight.w600 : null,
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(formatMoney(o.totalPrice), style: const TextStyle(fontWeight: FontWeight.bold)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 10),
                  OutlinedButton.icon(
                    onPressed: _actionInProgress ? null : () => _addItemDialog(o),
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('Добавить позицию'),
                  ),
                ] else ...[
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
                ],
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
                        onPressed: (_actionInProgress || o.items.isEmpty || !o.items.every((it) => _checkedItems.contains(it.id)))
                            ? null
                            : () => _runAction(() => _api.confirmOrder(o.id), celebrate: true),
                        icon: const Icon(Icons.check_circle_outline),
                        label: const Text('Заказ собран'),
                      ),
                    ),
                    const SizedBox(height: 10),
                  ],
                  if (o.status == 'READY' && canEditItems) ...[
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        onPressed: _actionInProgress
                            ? null
                            : () => _runAction(() => _api.reopenOrder(o.id)),
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
