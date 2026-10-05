import 'package:flutter/material.dart';
import 'package:printing/printing.dart';
import 'package:share_plus/share_plus.dart';

import '../models/order.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../services/invoice_pdf.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/hidden_search.dart';
import '../widgets/order_returns_sheets.dart';
import '../widgets/pick_from_list.dart';
import '../widgets/product_avatar.dart';
import '../widgets/receipt_card.dart';
import '../widgets/status_badge.dart';
import '../widgets/success_feedback.dart';
import 'returns_screen.dart' show returnColor;
import '../theme/app_theme.dart';

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
  // Админ правит состав отгруженного/завершённого заказа прямо в списке (см. OrderService.updateItems).
  bool _adminEditing = false;
  // Поиск по составу: лупа, ввод не виден, совпадения красным (см. HiddenSearch).
  final _search = HiddenSearch();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

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

  /// Накладную строит backend — по макету печатной формы 1С, с данными из документов 1С и
  /// закреплёнными за заказом возвратами. Локальный PDF — только если сервер недоступен.
  Future<InvoiceFile> _invoice(Order o) async {
    InvoiceFile? remote;
    try {
      remote = await _api.getInvoiceFile(o.id);
    } catch (_) {
      remote = null;
    }
    return remote ??
        InvoiceFile(
          bytes: await buildInvoicePdf(o),
          filename: _invoiceFileName(o),
          mimeType: 'application/pdf',
        );
  }

  Future<void> _share(Order o) async {
    final invoice = await _invoice(o);
    await Share.shareXFiles(
      [XFile.fromData(invoice.bytes, mimeType: invoice.mimeType, name: invoice.filename)],
      fileNameOverrides: [invoice.filename],
    );
  }

  /// Системный диалог печати: принтер выбирается там же (Wi-Fi-принтер через службу печати
  /// Android — Mopria или Canon Print Service).
  Future<void> _print(Order o) async {
    final invoice = await _invoice(o);
    await Printing.layoutPdf(
      name: invoice.filename.replaceAll(RegExp(r'\.pdf$'), ''),
      onLayout: (_) async => invoice.bytes,
    );
  }

  /// Как на backend (OrderService.invoiceFileName): Заказ[_Возврат]_Покупатель_дата.pdf.
  static String _invoiceFileName(Order o) {
    final d = (o.shippedAt ?? o.createdAt).toLocal();
    String two(int v) => v.toString().padLeft(2, '0');
    final client = o.clientName.replaceAll(RegExp(r'[\\/:*?"<>|\s]+'), ' ').trim();
    final parts = [
      'Заказ',
      if (o.returnsCount > 0) 'Возврат',
      if (client.isNotEmpty) client,
      '${two(d.day)}.${two(d.month)}.${d.year}',
    ];
    return '${parts.join('_')}.pdf';
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

  static const _previousStatusLabel = {
    'COMPLETED': 'Отгружен',
    'DELIVERED': 'Готов к отгрузке',
    'READY': 'В процессе сборки',
    'CONFIRMED': 'В процессе сборки',
    'IN_PRODUCTION': 'В процессе сборки',
    'CANCELLED': 'В процессе сборки',
  };

  Future<void> _rollbackDialog(Order o) async {
    final target = _previousStatusLabel[o.status] ?? '';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Откатить статус?'),
        content: Text(
          'Заказ вернётся в статус «$target».'
          '${o.status == 'DELIVERED' ? '\nОтгрузка отменится, документ в 1С будет распроведён.' : ''}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Нет'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Откатить'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      _runAction(() => _api.rollbackOrder(o.id));
    }
  }

  /// Общий выбор товара из списка (поиск по названию) — используется и для замены товара
  /// в существующей позиции, и для выбора товара новой позиции.
  Future<Product?> _pickAnyProduct(List<Product> products, String title) {
    return pickFromList<Product>(
      context: context,
      title: title,
      items: products,
      label: (p) => p.name,
      subtitle: (p) => formatMoney(p.price),
      leading: (p) => ProductAvatar(name: p.name, imageUrl: p.imageUrl),
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
    await _itemAction(() => _api.updateOrderItems(o.id, {item.id: qty}));
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

    await _itemAction(() => _api.updateOrderItems(o.id, {item.id: 0}));
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
    await _itemAction(() => _api.updateOrderItems(o.id, {}, newItems: [(picked.id, qty)]));
  }

  /// Правка состава: пока запрос идёт (1С бывает долгой), кнопки правки неактивны — второе
  /// нажатие не уйдёт вторым запросом.
  Future<void> _itemAction(Future<Object?> Function() action) async {
    if (_actionInProgress) return;
    setState(() => _actionInProgress = true);
    try {
      await action();
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    } finally {
      if (mounted) setState(() => _actionInProgress = false);
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
    final isAdmin = role == 'ADMIN';
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
              return Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: const Icon(Icons.print_outlined),
                    tooltip: 'Печать',
                    onPressed: () => _print(snapshot.data!),
                  ),
                  IconButton(
                    icon: const Icon(Icons.share_outlined),
                    tooltip: 'Поделиться',
                    onPressed: () => _share(snapshot.data!),
                  ),
                ],
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
                            style: TextStyle(
                              color: context.pal.muted,
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
                      style: TextStyle(color: context.pal.danger),
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
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        'Состав заказа',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    if (!(o.status == 'NEW' && canEditItems)) _search.button(setState),
                  ],
                ),
                _search.field(setState),
                const SizedBox(height: 8),
                if (o.status == 'NEW' && canEditItems) ...[
                  ReceiptCard(
                    title: 'Состав заказа',
                    icon: Icons.checklist_rounded,
                    action: _search.button(setState),
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
                        if (_search.matches(o.items[i].productName)) ReceiptRow(
                          highlight: _search.query,
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
                                onPressed: _actionInProgress ? null : () => _changeItemQuantity(o, o.items[i]),
                              ),
                              IconButton(
                                icon: const Icon(Icons.delete_outline, size: 20),
                                tooltip: 'Удалить позицию',
                                visualDensity: VisualDensity.compact,
                                onPressed: _actionInProgress ? null : () => _deleteItem(o, o.items[i]),
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
                                  color: o.items.any((i) => i.isInsufficient) ? context.pal.danger : context.pal.muted,
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
                        for (final item in o.items.where((it) => _search.matches(it.productName)))
                          ListTile(
                            leading: ProductAvatar(
                              name: item.productName,
                              imageUrl: item.productImageUrl,
                            ),
                            title: Text.rich(TextSpan(children: highlightSpans(item.productName, _search.query))),
                            subtitle: RichText(
                              text: TextSpan(
                                style: DefaultTextStyle.of(context).style.copyWith(color: context.pal.muted),
                                children: [
                                  TextSpan(
                                    text: formatQty(item.quantity),
                                    style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      color: context.pal.accent,
                                    ),
                                  ),
                                  TextSpan(text: ' × ${formatMoney(item.price)}'),
                                ],
                              ),
                            ),
                            trailing: _adminEditing && isAdmin
                                ? Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      IconButton(
                                        icon: const Icon(Icons.edit_outlined, size: 20),
                                        tooltip: 'Изменить количество',
                                        visualDensity: VisualDensity.compact,
                                        onPressed: _actionInProgress ? null : () => _changeItemQuantity(o, item),
                                      ),
                                      IconButton(
                                        icon: const Icon(Icons.delete_outline, size: 20),
                                        tooltip: 'Удалить позицию',
                                        visualDensity: VisualDensity.compact,
                                        onPressed: _actionInProgress ? null : () => _deleteItem(o, item),
                                      ),
                                    ],
                                  )
                                : Text(
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
                        if (o.returnsCount > 0) ...[
                          ListTile(
                            dense: true,
                            leading: Icon(Icons.assignment_return, color: returnColor(context)),
                            title: Text('Возврат (${o.returnsCount})'),
                            trailing: Text('−${formatMoney(o.returnTotal)}', style: TextStyle(color: returnColor(context))),
                            onTap: () async {
                              await showOrderReturnsCalculation(context,
                                  orderId: o.id, clientId: o.clientId, clientName: o.clientName, orderTotal: o.totalPrice);
                              _refresh();
                            },
                          ),
                          ListTile(
                            title: const Text('Итого с возвратом', style: TextStyle(fontWeight: FontWeight.bold)),
                            trailing: Text(formatMoney(o.netTotal), style: const TextStyle(fontWeight: FontWeight.bold)),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
                if (isAdmin && (o.status == 'DELIVERED' || o.status == 'COMPLETED')) ...[
                  const SizedBox(height: 10),
                  if (_adminEditing) ...[
                    Text(
                      'Документ в 1С будет распроведён, изменён и проведён заново; остаток склада поправится на разницу.',
                      style: TextStyle(color: context.pal.muted, fontSize: 12.5),
                    ),
                    const SizedBox(height: 8),
                    OutlinedButton.icon(
                      onPressed: _actionInProgress ? null : () => _addItemDialog(o),
                      icon: const Icon(Icons.add, size: 18),
                      label: const Text('Добавить позицию'),
                    ),
                    const SizedBox(height: 8),
                  ],
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _actionInProgress ? null : () => setState(() => _adminEditing = !_adminEditing),
                      icon: _actionInProgress && _adminEditing
                          ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : Icon(_adminEditing ? Icons.check : Icons.edit_outlined),
                      label: Text(_adminEditing ? 'Готово' : 'Редактировать состав'),
                    ),
                  ),
                ],
                if (o.notes != null && o.notes!.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(o.notes!, style: TextStyle(color: context.pal.muted)),
                ],
                if (role != 'ACCOUNTANT' && role != 'WAREHOUSE_FG') ...[
                  const SizedBox(height: 20),
                  Text(
                    'Задачи цеха',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  if (o.productionTasks.isEmpty)
                    Padding(
                      padding: EdgeInsets.symmetric(vertical: 12),
                      child: Text(
                        'Для этого заказа цеховых задач нет',
                        style: TextStyle(color: context.pal.muted),
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
                if (isAdmin && _previousStatusLabel.containsKey(o.status)) ...[
                  const SizedBox(height: 20),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _actionInProgress ? null : () => _rollbackDialog(o),
                      icon: const Icon(Icons.undo),
                      label: Text('Откатить в «${_previousStatusLabel[o.status]}»'),
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
                            foregroundColor: context.pal.warn,
                            side: BorderSide(color: context.pal.warn),
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
                      color: context.pal.warn.withValues(alpha: 0.12),
                      child: ListTile(
                        leading: Icon(
                          Icons.hourglass_bottom_outlined,
                          color: context.pal.warn,
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
                          foregroundColor: context.pal.danger,
                          side: BorderSide(color: context.pal.danger),
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
