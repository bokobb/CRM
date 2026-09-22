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
  bool _syncing = false;
  bool _completing = false;

  String? get _role => ApiClient.currentSession?.role;
  bool get _canCollect => _role == 'ADMIN' || _role == 'WAREHOUSE_RAW';
  bool get _canReceive => _role == 'ADMIN' || _role == 'WAREHOUSE_FG';
  bool get _canEdit => _role == 'ADMIN' || _role == 'ACCOUNTANT';
  // "Завершить" (провести требование-накладную) — не ACCOUNTANT, а WAREHOUSE_RAW: именно зав.
  // склада сырья видит, что после готовки осталось лишнее сырьё, корректирует состав и завершает.
  bool get _canComplete => _role == 'ADMIN' || _role == 'ACCOUNTANT' || _role == 'WAREHOUSE_RAW';

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

  /// Правка количества уже вычисленной позиции сырья — например, рецепт даёт неточную цифру и
  /// её нужно чуть подправить руками (см. ProductionRequestService.substituteItem на бэкенде,
  /// вызывается без ingredientId — сырьё не меняется, правится только количество).
  Future<void> _changeQuantity(ProductionRequestItem item) async {
    final qtyController = TextEditingController(text: formatQty(item.requiredQuantity));
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Количество «${item.ingredientName}»'),
        content: TextField(
          controller: qtyController,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: 'Количество, ${item.ingredientUnit}'),
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
      await _api.substituteProductionRequestItem(widget.requestId, item.id, quantity: qty);
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  /// Добавление совсем новой позиции сырья, которой не было в заявке — например, во время
  /// производства понадобилось что-то, чего не было в исходном рецепте (см.
  /// ProductionRequestService.addItem на бэкенде). Если выбранное сырьё уже есть в заявке —
  /// сервер просто увеличит его количество, а не создаст задвоенную позицию.
  Future<void> _addItem(ProductionRequest r) async {
    final List<Ingredient> ingredients;
    try {
      ingredients = await _api.getIngredients();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      return;
    }
    if (!mounted) return;
    final picked = await pickFromList<Ingredient>(
      context: context,
      title: 'Добавить сырьё',
      items: ingredients,
      label: (i) => i.name,
      subtitle: (i) => '${formatQty(i.quantityInStock)} ${i.unit} на складе',
    );
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
          decoration: InputDecoration(labelText: 'Количество, ${picked.unit}'),
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
      await _api.addProductionRequestItem(r.id, picked.id, qty);
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  /// Удаление позиции сырья из заявки — например, добавили лишнюю по ошибке (см.
  /// ProductionRequestService.removeItem на бэкенде).
  Future<void> _removeItem(ProductionRequestItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Удалить «${item.ingredientName}»?'),
        content: const Text('Позиция будет удалена из заявки.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await _api.deleteProductionRequestItem(widget.requestId, item.id);
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

  /// Отмена заявки в ЛЮБОМ статусе, кроме уже отменённой — откатывает списанное сырьё/
  /// оприходованную продукцию обратно на склад и помечает связанные документы на удаление в 1С,
  /// если они уже создавались (см. ProductionRequestService.cancel на бэкенде). Раньше эта
  /// функциональность (откат остатков в любом статусе) была отдельной кнопкой «Удалить заявку» —
  /// теперь объединена с отменой, заявка при этом не стирается, а остаётся в статусе CANCELLED.
  Future<void> _cancel(ProductionRequest r) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Отменить заявку?'),
        content: Text(
          '«${r.title}» — списанное сырьё и оприходованная продукция будут возвращены обратно на склад. Действие необратимо.',
        ),
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

  /// "Завершить" — вручную проводит требование-накладную (списание сырья) в 1С, когда состав
  /// заявки точно больше не изменится (см. ProductionRequestService.completeRequisition на
  /// бэкенде). Отчёт производства эта кнопка не трогает — он проводится сам при полном
  /// оприходовании.
  Future<void> _completeOneC() async {
    setState(() {
      _completing = true;
      _error = null;
    });
    try {
      await _api.completeProductionRequestOneC(widget.requestId);
      if (mounted) await playSuccessFeedback(context);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _completing = false);
    }
  }

  /// "Отменить" (после "Завершить") — распроводит требование-накладную в 1С и снова разрешает
  /// редактировать/удалять/добавлять позиции сырья (см.
  /// ProductionRequestService.cancelCompletion на бэкенде).
  Future<void> _cancelCompletion() async {
    setState(() {
      _completing = true;
      _error = null;
    });
    try {
      await _api.cancelProductionRequestCompletion(widget.requestId);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _completing = false);
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
                          Text(
                            'Дата документа для 1С: ${formatDate(r.documentDate)}',
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
                if (r.massSummary != null) _buildMassSummaryCard(r.massSummary!),
                const SizedBox(height: 8),

                if (r.status == 'NEW' && _canCollect) ...[
                  ReceiptCard(
                    title: 'Состав заявки (сырьё)',
                    icon: Icons.checklist_rounded,
                    children: [
                      if (r.items.isNotEmpty)
                        ReceiptSelectAllRow(
                          value: r.items.every((it) => _checked.contains(it.id))
                              ? true
                              : (_checked.isEmpty ? false : null),
                          onChanged: (selectAll) => setState(() {
                            if (selectAll == true) {
                              _checked.addAll(r.items.map((it) => it.id));
                            } else {
                              _checked.clear();
                            }
                          }),
                        ),
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
                          trailingAction: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                icon: const Icon(Icons.edit_outlined, size: 20),
                                tooltip: 'Изменить количество',
                                visualDensity: VisualDensity.compact,
                                onPressed: () => _changeQuantity(r.items[i]),
                              ),
                              IconButton(
                                icon: const Icon(Icons.delete_outline, size: 20),
                                tooltip: 'Удалить',
                                visualDensity: VisualDensity.compact,
                                onPressed: () => _removeItem(r.items[i]),
                              ),
                            ],
                          ),
                        ),
                    ],
                    footer: InkWell(
                      borderRadius: BorderRadius.circular(4),
                      onTap: () => setState(() {
                        if (r.items.isNotEmpty && r.items.every((it) => _checked.contains(it.id))) {
                          _checked.clear();
                        } else {
                          _checked.addAll(r.items.map((it) => it.id));
                        }
                      }),
                      child: Row(
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
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: (_collecting || r.items.isEmpty || !r.items.every((it) => _checked.contains(it.id))) ? null : () => _collect(r),
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
                          // ADMIN и WAREHOUSE_RAW могут поправить состав сырья в любом статусе,
                          // кроме отменённой и завершённой заявки — во время производства может
                          // понадобиться больше исходного (см. ProductionRequestService.substituteItem).
                          trailingAction: _canCollect && r.status != 'CANCELLED' && !r.requisitionCompleted
                              ? Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    IconButton(
                                      icon: const Icon(Icons.edit_outlined, size: 20),
                                      tooltip: 'Изменить количество',
                                      visualDensity: VisualDensity.compact,
                                      onPressed: () => _changeQuantity(r.items[i]),
                                    ),
                                    IconButton(
                                      icon: const Icon(Icons.delete_outline, size: 20),
                                      tooltip: 'Удалить',
                                      visualDensity: VisualDensity.compact,
                                      onPressed: () => _removeItem(r.items[i]),
                                    ),
                                  ],
                                )
                              : null,
                        ),
                    ],
                    footer: Text(
                      'Позиций: ${r.items.length}',
                      style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                    ),
                  ),
                ],
                if (r.status != 'CANCELLED' && !r.requisitionCompleted && _canCollect) ...[
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: () => _addItem(r),
                      icon: const Icon(Icons.add, size: 18),
                      label: const Text('Добавить позицию'),
                    ),
                  ),
                ],
                // Позиции продукции правятся и после «Собрано» — пока ни одна строка не
                // оприходована (те же условия, что на бэкенде в ProductionRequestService.update
                // и в вебе, см. ProductionRequestController.canEditLines).
                if (_canEdit &&
                    (r.status == 'NEW' ||
                        (r.status == 'COLLECTED' && !r.lines.any((l) => l.isReceived)))) ...[
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
                ],
                if (r.status != 'CANCELLED' && !r.requisitionCompleted && _role == 'ADMIN') ...[
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: _cancelling ? null : () => _cancel(r),
                      style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFB8433F)),
                      icon: _cancelling
                          ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.cancel_outlined, size: 18),
                      label: const Text('Отменить заявку'),
                    ),
                  ),
                ],
                if (r.status == 'RECEIVED' &&
                    r.oneCRequisitionNumber != null &&
                    !r.requisitionCompleted &&
                    _canComplete) ...[
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: _completing
                          ? null
                          : () async {
                              final confirmed = await showDialog<bool>(
                                context: context,
                                builder: (context) => AlertDialog(
                                  title: const Text('Завершить?'),
                                  content: const Text(
                                    'Требование-накладная будет проведена в 1С. Дальнейшие правки состава сырья туда автоматически больше не попадут.',
                                  ),
                                  actions: [
                                    TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
                                    FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Завершить')),
                                  ],
                                ),
                              );
                              if (confirmed == true) await _completeOneC();
                            },
                      child: Text(_completing ? 'Отправка...' : 'Завершить'),
                    ),
                  ),
                ],
                if (r.status == 'RECEIVED' &&
                    r.requisitionCompleted &&
                    _canComplete) ...[
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton(
                      onPressed: _completing
                          ? null
                          : () async {
                              final confirmed = await showDialog<bool>(
                                context: context,
                                builder: (context) => AlertDialog(
                                  title: const Text('Отменить?'),
                                  content: const Text(
                                    'Проведение требования-накладной в 1С будет отменено. Состав сырья снова можно будет редактировать.',
                                  ),
                                  actions: [
                                    TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Нет')),
                                    FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Отменить')),
                                  ],
                                ),
                              );
                              if (confirmed == true) await _cancelCompletion();
                            },
                      style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFB8433F)),
                      child: Text(_completing ? 'Отправка...' : 'Отменить'),
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

  /// Часть товаров в заявке — разные фасовки одного замеса (задан вес 1 шт в карточке товара),
  /// поэтому норма сверяется по общей массе, а не по штукам построчно — не страшно, если
  /// рабочие перепутали пропорции между фасовками при нарезке (см. Product.netWeightGrams).
  Widget _buildMassSummaryCard(ProductionMassSummary s) {
    final done = s.allWeightedReceived;
    final color = !done ? Colors.grey : (s.insufficient ? const Color(0xFFB8433F) : const Color(0xFF4C8A5E));
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Сверка по общей массе', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            Text(
              'Норма: ${formatQty(s.plannedKg)} кг'
              '${!done ? ' · факт (пока не всё оприходовано): ${formatQty(s.actualKg)} кг' : ' · факт: ${formatQty(s.actualKg)} кг'}',
              style: const TextStyle(color: Colors.grey, fontSize: 12.5),
            ),
            if (done)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(
                  s.insufficient
                      ? 'Не хватает ${formatQty(s.deviationKg.abs())} кг сырья '
                          '(≈ ${formatQty(s.deviationPiecesAbs)} шт. «${s.referenceProductName}»)'
                      : 'Нормы хватает (запас ${formatQty(s.deviationKg.abs())} кг '
                          '≈ ${formatQty(s.deviationPiecesAbs)} шт. «${s.referenceProductName}»)',
                  style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 12.5),
                ),
              ),
          ],
        ),
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
              if (line.insufficient)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    'Недостаточно — ${formatQty(line.shortfall)} $unit',
                    style: const TextStyle(color: Color(0xFFB8433F), fontWeight: FontWeight.w600, fontSize: 12),
                  ),
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
                  final diff = actual - line.plannedQuantity!;
                  final enough = actual >= line.plannedQuantity!;
                  return Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      enough
                          ? 'Достаточно — на ${formatQty(diff.abs())} $unit больше нормы'
                          : 'Меньше нормы на ${formatQty(diff.abs())} $unit',
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
