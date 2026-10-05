import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../theme/app_theme.dart';

/// Общий экран для "Поступления" и "Списание" — товара и сырья (см. [isProduct]) — отличаются
/// типом движения (RECEIPT/WRITE_OFF), видом номенклатуры, заголовком и тем, какой метод
/// ApiClient дёргается при сохранении. Раньше вид (сырьё/товар) выбирался переключателем внутри
/// одного и того же экрана — теперь это два отдельных пункта меню ("Склад"/"Склад сырья", см.
/// AppDrawer), каждый видит только свою номенклатуру, зеркально "Остаткам"
/// (ProductStockScreen/IngredientsScreen).
class StockMovementScreen extends StatefulWidget {
  final bool isReceipt;
  final bool isProduct;

  const StockMovementScreen({super.key, required this.isReceipt, required this.isProduct});

  @override
  State<StockMovementScreen> createState() => _StockMovementScreenState();
}

class _StockMovementScreenState extends State<StockMovementScreen> {
  final _api = ApiClient();
  late Future<List<StockMovement>> _future;
  final _search = TextEditingController();
  DateTimeRange _range = todayRange();
  bool _postInProgress = false;

  /// id движения, для которого сейчас показана корзинка вместо количества — появляется по
  /// долгому нажатию на строку, обычное нажатие по строке в этом состоянии её убирает обратно.
  int? _deleteModeId;

  String get _type => widget.isReceipt ? 'RECEIPT' : 'WRITE_OFF';
  String get _title {
    if (widget.isProduct) return widget.isReceipt ? 'Поступления' : 'Списание товара';
    return widget.isReceipt ? 'Поступления сырья' : 'Списание сырья';
  }

  bool get _hasFilter => _search.text.isNotEmpty;

  Future<List<StockMovement>> _load({String? q}) async {
    final all = await _api.getStockMovements(type: _type, from: _range.start, to: _range.end, q: q);
    return all.where((m) => m.isProduct == widget.isProduct).toList();
  }

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _load(q: _search.text.trim());
    });
    await _future;
  }

  void _onRangeChanged(DateTimeRange range) {
    setState(() => _range = range);
    _refresh();
  }

  /// Долгое нажатие показывает корзинку вместо суммы — нажатие на неё удаляет движение и
  /// откатывает его влияние на остаток (см. StockService.deleteMovement на бэкенде).
  Future<void> _deleteMovement(StockMovement m) async {
    setState(() => _deleteModeId = null);
    try {
      await _api.deleteStockMovement(m.id);
      _refresh();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  Future<void> _openForm() async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _StockMovementForm(isReceipt: widget.isReceipt, isProduct: widget.isProduct),
    );
    if (saved == true) _refresh();
  }

  /// "Общее оприходование" — собирает все ещё не выгруженные в 1С ручные поступления товара
  /// за сегодня в один документ и сразу проводит его на 06:00 (приход от заявок на
  /// производство сюда не относится — тот выгружается сам). Если забыть — то же самое
  /// произойдёт автоматически в 20:00.
  Future<void> _postOneC() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Общее оприходование'),
        content: const Text(
          'Собрать все сегодняшние ручные поступления товара в один документ и провести в 1С на 06:00?',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Провести')),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _postInProgress = true);
    try {
      final result = await _api.postDailyReceiptsOneC();
      if (!mounted) return;
      final message = result.documentNumber == null
          ? 'Оприходовывать нечего — всё уже выгружено в 1С'
          : 'Оприходовано в 1С: позиций ${result.movementsCount}, документ №${result.documentNumber}'
              '${result.unresolved.isEmpty ? '' : '. Не сопоставлено: ${result.unresolved.join(', ')}'}';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      }
    } finally {
      if (mounted) setState(() => _postInProgress = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final role = ApiClient.currentSession?.role;
    final canPostOneC = widget.isReceipt && widget.isProduct && (role == 'ADMIN' || role == 'WAREHOUSE_FG');
    return Scaffold(
      appBar: AppBar(
        title: Text(_title),
        actions: [
          if (canPostOneC)
            IconButton(
              onPressed: _postInProgress ? null : _postOneC,
              icon: _postInProgress
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.cloud_upload_outlined),
              tooltip: 'Общее оприходование в 1С',
            ),
        ],
      ),
      drawer: AppDrawer(currentRoute: widget.isProduct ? '/warehouse' : '/ingredients-warehouse'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: Column(
          children: [
            DateFilterBar(range: _range, onChanged: _onRangeChanged),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 0),
              child: TextField(
                controller: _search,
                decoration: InputDecoration(
                  labelText: widget.isProduct ? 'Название товара' : 'Название сырья',
                  prefixIcon: const Icon(Icons.search),
                  isDense: true,
                ),
                onSubmitted: (_) => _refresh(),
              ),
            ),
            const SizedBox(height: 6),
            Expanded(
              child: FutureBuilder<List<StockMovement>>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final movements = snapshot.data!;
                  if (movements.isEmpty) {
                    return ListView(children: [
                      Padding(
                        padding: const EdgeInsets.all(32),
                        child: Center(
                          child: Text(_hasFilter ? 'Ничего не найдено' : 'Записей пока нет',
                              style: TextStyle(color: context.pal.muted)),
                        ),
                      ),
                    ]);
                  }
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: movements.length,
                    itemBuilder: (context, index) {
                      final m = movements[index];
                      final deleteMode = _deleteModeId == m.id;
                      return AppListTile(
                        leadingIcon: m.isProduct ? Icons.cake_outlined : Icons.grain_outlined,
                        leadingColor: m.isProduct ? context.pal.accent : context.pal.warn,
                        title: m.itemName,
                        subtitle:
                            '${m.reason?.isNotEmpty == true ? '${m.reason} · ' : ''}${m.createdBy} · ${formatDateTime(m.createdAt)}',
                        trailing: deleteMode
                            ? IconButton(
                                icon: Icon(Icons.delete_outline, color: context.pal.danger),
                                tooltip: 'Удалить',
                                onPressed: () => _deleteMovement(m),
                              )
                            : Text(
                                '${widget.isReceipt ? '+' : '−'}${formatQty(m.quantity)} ${m.unit}',
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: widget.isReceipt ? context.pal.success : context.pal.danger,
                                ),
                              ),
                        onLongPress: () => setState(() => _deleteModeId = m.id),
                        onTap: deleteMode ? () => setState(() => _deleteModeId = null) : null,
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StockMovementForm extends StatefulWidget {
  final bool isReceipt;
  final bool isProduct;

  const _StockMovementForm({required this.isReceipt, required this.isProduct});

  @override
  State<_StockMovementForm> createState() => _StockMovementFormState();
}

class _StockMovementFormState extends State<_StockMovementForm> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _quantity = TextEditingController();
  final _reason = TextEditingController();
  int? _itemId;
  List<Ingredient> _ingredients = [];
  List<Product> _products = [];
  bool _loading = false;
  bool _loadingItems = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadItems();
  }

  Future<void> _loadItems() async {
    setState(() => _loadingItems = true);
    if (widget.isProduct) {
      _products = await _api.getProducts();
    } else {
      _ingredients = await _api.getIngredients();
    }
    if (mounted) setState(() => _loadingItems = false);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate() || _itemId == null) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final qty = double.parse(_quantity.text.replaceAll(',', '.'));
      final reason = _reason.text.trim();
      if (widget.isProduct) {
        if (widget.isReceipt) {
          await _api.receiveProductStock(_itemId!, qty, reason);
        } else {
          await _api.writeOffProductStock(_itemId!, qty, reason);
        }
      } else {
        if (widget.isReceipt) {
          await _api.receiveStock(_itemId!, qty, reason);
        } else {
          await _api.writeOffStock(_itemId!, qty, reason);
        }
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: 16 + MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.isReceipt ? 'Новое поступление' : 'Новое списание',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 16),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(_error!, style: TextStyle(color: context.pal.danger)),
              ),
            if (_loadingItems)
              const Center(child: Padding(padding: EdgeInsets.all(12), child: CircularProgressIndicator()))
            else if (widget.isProduct)
              DropdownButtonFormField<int>(
                initialValue: _itemId,
                decoration: const InputDecoration(labelText: 'Товар'),
                items: [
                  for (final p in _products)
                    DropdownMenuItem(value: p.id, child: Text('${p.name} (${formatQty(p.quantityInStock)} ${p.unit ?? "шт"})')),
                ],
                onChanged: (v) => setState(() => _itemId = v),
                validator: (v) => v == null ? 'Выберите товар' : null,
              )
            else
              DropdownButtonFormField<int>(
                initialValue: _itemId,
                decoration: const InputDecoration(labelText: 'Сырьё'),
                items: [
                  for (final i in _ingredients)
                    DropdownMenuItem(value: i.id, child: Text('${i.name} (${formatQty(i.quantityInStock)} ${i.unit})')),
                ],
                onChanged: (v) => setState(() => _itemId = v),
                validator: (v) => v == null ? 'Выберите сырьё' : null,
              ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _quantity,
              decoration: const InputDecoration(labelText: 'Количество'),
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              validator: (v) =>
                  (v == null || double.tryParse(v.replaceAll(',', '.')) == null) ? 'Введите число' : null,
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _reason,
              decoration: InputDecoration(
                labelText: widget.isReceipt ? 'Причина (поставщик и т.п.)' : 'Причина (порча, недостача...)',
              ),
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: _loading ? null : _save,
              child: _loading
                  ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Сохранить'),
            ),
          ],
        ),
      ),
    );
  }
}
