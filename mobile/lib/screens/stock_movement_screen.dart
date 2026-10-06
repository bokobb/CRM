import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import 'write_off_form_screen.dart';
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

  /// id движения, для которого сейчас показана корзинка вместо количества — появляется по
  /// долгому нажатию на строку, обычное нажатие по строке в этом состоянии её убирает обратно.
  int? _deleteModeId;

  /// «00000000014» → «14».
  static String _shortNumber(String n) => n.replaceFirst(RegExp(r'^0+(?=.)'), '');

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

  /// Форма документа (как «Новый заказ») — и для поступлений, и для списаний; в 1С документ
  /// создаётся и проводится сам, отдельная кнопка «Общее оприходование» больше не нужна.
  Future<void> _openForm() async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
          builder: (_) => WriteOffFormScreen(isProduct: widget.isProduct, isReceipt: widget.isReceipt)),
    );
    if (saved == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_title)),
      drawer: AppDrawer(currentRoute: widget.isProduct ? '/warehouse' : '/ingredients-warehouse'),
      bottomNavigationBar: AppBottomNav(currentRoute: widget.isProduct ? '/warehouse' : '/ingredients-warehouse'),
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
                        subtitle: '${m.reason?.isNotEmpty == true ? '${m.reason} · ' : ''}${m.createdBy} · '
                            '${formatDateTime(m.createdAt)}'
                            '${m.oneCSyncError != null ? '\n1С: ошибка — ${m.oneCSyncError}' : m.oneCDocumentNumber != null ? ' · 1С №${_shortNumber(m.oneCDocumentNumber!)}' : ''}',
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
