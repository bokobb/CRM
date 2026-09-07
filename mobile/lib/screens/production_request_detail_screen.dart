import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/pick_from_list.dart';
import '../widgets/receipt_card.dart';
import '../widgets/status_badge.dart';
import '../widgets/success_feedback.dart';

class ProductionRequestDetailScreen extends StatefulWidget {
  final int requestId;

  const ProductionRequestDetailScreen({super.key, required this.requestId});

  @override
  State<ProductionRequestDetailScreen> createState() => _ProductionRequestDetailScreenState();
}

class _ProductionRequestDetailScreenState extends State<ProductionRequestDetailScreen> {
  final _api = ApiClient();
  late Future<ProductionRequest> _future;
  final Set<int> _checked = {};
  final Map<int, TextEditingController> _receiveControllers = {};
  int? _actionInProgressLine;
  bool _collecting = false;
  String? _error;

  bool _cancelling = false;
  bool _deleting = false;
  bool _syncing = false;

  String? get _role => ApiClient.currentSession?.role;
  bool get _canCollect => _role == 'ADMIN' || _role == 'WAREHOUSE_RAW';
  bool get _canReceive => _role == 'ADMIN' || _role == 'WAREHOUSE_FG';
  bool get _canEdit => _role == 'ADMIN' || _role == 'ACCOUNTANT';

  @override
  void initState() {
    super.initState();
    _future = _api.getProductionRequest(widget.requestId);
  }

  TextEditingController _controllerFor(int lineId) =>
      _receiveControllers.putIfAbsent(lineId, () => TextEditingController());

  Future<void> _refresh() async {
    setState(() {
      _checked.clear();
      _future = _api.getProductionRequest(widget.requestId);
    });
    await _future;
  }

  /// Замена сырья в позиции — например, нужного не хватило на складе (см.
  /// ProductionRequestService.substituteItem на бэкенде). Настоящее название сохраняется
  /// сервером и показывается в позиции пометкой "было: ...".
  Future<void> _substitute(ProductionRequestItem item) async {
    final List<Ingredient> ingredients;
    try {
      ingredients = await _api.getIngredients();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      return;
    }
    final options = ingredients.where((i) => i.id != item.ingredientId).toList();
    if (!mounted) return;
    final picked = await pickFromList<Ingredient>(
      context: context,
      title: 'Чем заменить «${item.ingredientName}»',
      items: options,
      label: (i) => i.name,
      subtitle: (i) => '${formatQty(i.quantityInStock)} ${i.unit} на складе',
    );
    if (picked == null || !mounted) return;

    final qtyController = TextEditingController(text: formatQty(item.requiredQuantity));
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Заменить на «${picked.name}»'),
        content: TextField(
          controller: qtyController,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: 'Количество, ${picked.unit}'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Заменить')),
        ],
      ),
    );
    if (confirmed != true) return;

    final qty = double.tryParse(qtyController.text.replaceAll(',', '.'));
    try {
      await _api.substituteProductionRequestItem(widget.requestId, item.id, picked.id, quantity: qty);
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Future<void> _collect(ProductionRequest request) async {
    setState(() {
      _collecting = true;
      _error = null;
    });
    try {
      await _api.collectProductionRequest(widget.requestId, _checked.toList());
      if (mounted) await playSuccessFeedback(context);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _collecting = false);
    }
  }

  Future<void> _cancel(ProductionRequest r) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Отменить заявку?'),
        content: Text('«${r.title}» — действие нельзя отменить обратно.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Нет')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Отменить заявку')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() {
      _cancelling = true;
      _error = null;
    });
    try {
      await _api.cancelProductionRequest(widget.requestId);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _cancelling = false);
    }
  }

  /// Полное удаление в любом статусе (в отличие от _cancel — только для «Новой»): откатывает
  /// списанное сырьё/оприходованную продукцию обратно на склад (см.
  /// ProductionRequestService.delete на бэкенде). После удаления заявки больше не существует —
  /// возвращаемся на список, а не обновляем этот экран.
  Future<void> _delete(ProductionRequest r) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить заявку?'),
        content: Text(
          '«${r.title}» — списанное сырьё и оприходованная продукция будут возвращены обратно на склад. Действие необратимо.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() {
      _deleting = true;
      _error = null;
    });
    try {
      await _api.deleteProductionRequest(widget.requestId);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = e.toString());
      if (mounted) setState(() => _deleting = false);
    }
  }

  /// Повторная ручная синхронизация с 1С — на случай, если автоматическая (при создании,
  /// редактировании, замене сырья или оприходовании) не сработала (см.
  /// ProductionRequestService.resyncOneC).
  Future<void> _syncOneC() async {
    setState(() {
      _syncing = true;
      _error = null;
    });
    try {
      await _api.syncProductionRequestOneC(widget.requestId);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _syncing = false);
    }
  }

  Future<void> _receiveLine(ProductionRequestLine line) async {
    final qty = double.tryParse(_controllerFor(line.id).text.trim().replaceAll(',', '.'));
    if (qty == null || qty <= 0) {
      setState(() => _error = 'Введите количество');
      return;
    }
    setState(() {
      _actionInProgressLine = line.id;
      _error = null;
    });
    try {
      await _api.receiveProductionRequestLine(widget.requestId, line.id, qty);
      if (mounted) await playSuccessFeedback(context);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _actionInProgressLine = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Заявка №${widget.requestId}')),
      body: FutureBuilder<ProductionRequest>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
          }
          final r = snapshot.data!;
          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(r.title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          const SizedBox(height: 4),
                          Text(
                            'Итого ${formatQty(r.totalBatches)} замес. · ${r.createdBy} · ${formatDateTime(r.createdAt)}',
                            style: const TextStyle(color: Colors.grey, fontSize: 13),
                          ),
                        ],
                      ),
                    ),
                    StatusBadge(status: r.status, label: r.statusLabel),
                  ],
                ),
                if (r.status == 'COLLECTED' || r.status == 'RECEIVED') ...[
                  const SizedBox(height: 10),
                  Text('Собрал: ${r.collectedBy} · ${formatDateTime(r.collectedAt!)}',
                      style: const TextStyle(color: Colors.grey, fontSize: 13)),
                ],
                if (r.oneCReportNumber != null) ...[
                  const SizedBox(height: 10),
                  Text(
                    'В 1С: отчёт производства №${r.oneCReportNumber}, требование-накладная №${r.oneCRequisitionNumber}',
                    style: const TextStyle(color: Color(0xFF4C8A5E), fontSize: 13),
                  ),
                ],
                if (r.oneCSyncError != null) ...[
                  const SizedBox(height: 10),
                  Text('Не синхронизировано с 1С: ${r.oneCSyncError}',
                      style: const TextStyle(color: Colors.orange, fontSize: 13)),
                ],
                if (r.status != 'CANCELLED' &&
                    (r.oneCReportNumber == null || r.oneCSyncError != null) &&
                    _canEdit) ...[
                  const SizedBox(height: 10),
                  OutlinedButton(
                    onPressed: _syncing ? null : _syncOneC,
                    child: Text(_syncing ? 'Отправка...' : 'Синхронизировать с 1С'),
                  ),
                ],
                const SizedBox(height: 18),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),

                const Text('Позиции заявки', style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 8),
                for (final line in r.lines) _buildLineCard(r, line),
                const SizedBox(height: 8),

                if (r.status == 'NEW' && _canCollect) ...[
                  ReceiptCard(
                    title: 'Состав заявки (сырьё)',
                    icon: Icons.checklist_rounded,
                    children: [
                      for (int i = 0; i < r.items.length; i++)
                        ReceiptRow(
                          index: i + 1,
                          name: r.items[i].originalIngredientName == null
                              ? r.items[i].ingredientName
                              : '${r.items[i].ingredientName}\n(было: ${r.items[i].originalIngredientName})',
                          qty: '${formatQty(r.items[i].requiredQuantity)} ${r.items[i].ingredientUnit}',
                          checkbox: _checked.contains(r.items[i].id),
                          insufficient: r.items[i].isInsufficient,
                          onCheckboxChanged: (v) => setState(() {
                            if (v == true) {
                              _checked.add(r.items[i].id);
                            } else {
                              _checked.remove(r.items[i].id);
                            }
                          }),
                          trailingAction: IconButton(
                            icon: const Icon(Icons.swap_horiz, size: 20),
                            tooltip: 'Заменить сырьё',
                            visualDensity: VisualDensity.compact,
                            onPressed: () => _substitute(r.items[i]),
                          ),
                        ),
                    ],
                    footer: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          r.items.any((i) => i.isInsufficient)
                              ? 'Не хватает на складе — отмечено красным'
                              : 'Отмечено ${_checked.length} из ${r.items.length}',
                          style: TextStyle(
                            color: r.items.any((i) => i.isInsufficient) ? const Color(0xFFC0392B) : Colors.grey,
                            fontSize: 12.5,
                            fontWeight: r.items.any((i) => i.isInsufficient) ? FontWeight.w600 : null,
                          ),
                        ),
                        if (_checked.length == r.items.length)
                          const Icon(Icons.check_circle, size: 16, color: Color(0xFF4C8A5E)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: (_collecting || _checked.length != r.items.length) ? null : () => _collect(r),
                    child: _collecting
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Собрано'),
                  ),
                ] else ...[
                  ReceiptCard(
                    title: 'Состав заявки (сырьё)',
                    children: [
                      for (int i = 0; i < r.items.length; i++)
                        ReceiptRow(
                          index: i + 1,
                          name: r.items[i].originalIngredientName == null
                              ? r.items[i].ingredientName
                              : '${r.items[i].ingredientName}\n(было: ${r.items[i].originalIngredientName})',
                          qty: '${formatQty(r.items[i].requiredQuantity)} ${r.items[i].ingredientUnit}',
                        ),
                    ],
                    footer: Text(
                      'Позиций: ${r.items.length}',
                      style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                    ),
                  ),
                ],
                if (r.status == 'NEW' && _canEdit) ...[
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: () async {
                        final changed = await Navigator.of(context)
                            .pushNamed('/production-requests/edit', arguments: widget.requestId);
                        if (changed == true) _refresh();
                      },
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      label: const Text('Редактировать'),
                    ),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _cancelling ? null : () => _cancel(r),
                      style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFB8433F)),
                      icon: _cancelling
                          ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.cancel_outlined, size: 18),
                      label: const Text('Отменить'),
                    ),
                  ),
                ],
                if (_role == 'ADMIN') ...[
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _deleting ? null : () => _delete(r),
                      style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                      icon: _deleting
                          ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.delete_outline, size: 18),
                      label: const Text('Удалить заявку'),
                    ),
                  ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildLineCard(ProductionRequest r, ProductionRequestLine line) {
    final unit = line.productUnit ?? 'шт';
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(line.productName, style: const TextStyle(fontWeight: FontWeight.w600)),
                ),
                Text(
                  '${formatQty(line.batches)} замес.'
                  '${line.plannedQuantity != null ? " (≈${formatQty(line.plannedQuantity!)} $unit)" : ""}',
                  style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                ),
              ],
            ),
            if (line.isReceived) ...[
              const SizedBox(height: 6),
              Text(
                'Оприходовано ${formatQty(line.receivedQuantity!)} $unit — ${line.receivedBy} · '
                '${formatDateTime(line.receivedAt!)}',
                style: const TextStyle(color: Color(0xFF4C8A5E), fontWeight: FontWeight.w600, fontSize: 12.5),
              ),
            ] else if (r.status == 'COLLECTED' && _canReceive) ...[
              const SizedBox(height: 8),
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    child: TextField(
                      controller: _controllerFor(line.id),
                      decoration: InputDecoration(labelText: 'Факт, $unit', isDense: true),
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: _actionInProgressLine != null ? null : () => _receiveLine(line),
                    child: _actionInProgressLine == line.id
                        ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('OK'),
                  ),
                ],
              ),
              if (line.plannedQuantity != null && line.plannedQuantity! > 0)
                Builder(builder: (context) {
                  final actual = double.tryParse(_controllerFor(line.id).text.replaceAll(',', '.'));
                  if (actual == null) return const SizedBox.shrink();
                  final diffPct = ((actual - line.plannedQuantity!) / line.plannedQuantity!) * 100;
                  final enough = actual >= line.plannedQuantity!;
                  return Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      enough
                          ? 'Достаточно — на ${formatQty(diffPct.abs())}% больше нормы'
                          : 'Меньше нормы на ${formatQty(diffPct.abs())}%',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: enough ? const Color(0xFF4C8A5E) : const Color(0xFFB8433F),
                      ),
                    ),
                  );
                }),
            ],
          ],
        ),
      ),
    );
  }
}
