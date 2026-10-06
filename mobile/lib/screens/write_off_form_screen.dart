import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/pick_from_list.dart';
import '../widgets/product_avatar.dart';
import '../widgets/success_feedback.dart';

/// Позиция номенклатуры для списания — общий вид товара и сырья.
class _Item {
  final int id;
  final String name;
  final String unit;
  final double stock;
  final String? imageUrl;

  const _Item(this.id, this.name, this.unit, this.stock, this.imageUrl);
}

class _Row {
  final _Item item;
  double quantity;

  _Row(this.item, this.quantity);
}

/// Поступление или списание товара/сырья — устроено как «Новый заказ»: сверху дата (по умолчанию
/// сегодня), «Выбрать товар/сырьё» → поиск по списку → количество, «Добавить позицию»,
/// комментарий (у списания — обязательная причина) и внизу «Оприходовать»/«Списать». Все позиции
/// уходят одним документом: если при списании чего-то не хватает, не списывается ничего; в 1С
/// документ создаётся и проводится в фоне (см. StockService.createDocument на бэкенде).
class WriteOffFormScreen extends StatefulWidget {
  final bool isProduct;
  final bool isReceipt;

  const WriteOffFormScreen({super.key, required this.isProduct, this.isReceipt = false});

  @override
  State<WriteOffFormScreen> createState() => _WriteOffFormScreenState();
}

class _WriteOffFormScreenState extends State<WriteOffFormScreen> {
  final _api = ApiClient();
  late Future<List<_Item>> _future;

  DateTime _date = DateTime.now();
  final List<_Row> _rows = [];
  final _reason = TextEditingController();
  bool _saving = false;
  String? _error;


  @override
  void initState() {
    super.initState();
    _future = _load();
    _reason.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<List<_Item>> _load() async {
    if (widget.isProduct) {
      final products = await _api.getProducts();
      return [for (final p in products) _Item(p.id, p.name, p.unit ?? 'шт', p.quantityInStock, p.imageUrl)];
    }
    final ingredients = await _api.getIngredients();
    return [for (final i in ingredients) _Item(i.id, i.name, i.unit, i.quantityInStock, null)];
  }

  Widget _avatar(_Item item) => widget.isProduct
      ? ProductAvatar(name: item.name, imageUrl: item.imageUrl)
      : CircleAvatar(
          backgroundColor: context.pal.warn.withValues(alpha: 0.15),
          child: Icon(Icons.grain_outlined, color: context.pal.warn, size: 20),
        );

  /// Без [row] — выбор из списка и новая позиция; с [row] — правка количества (0 — убрать).
  Future<void> _pick(List<_Item> items, {_Row? row}) async {
    _Item? item = row?.item;
    if (item == null) {
      item = await pickFromList<_Item>(
        context: context,
        title: widget.isProduct ? 'Поиск товара' : 'Поиск сырья',
        items: items,
        label: (i) => i.name,
        subtitle: (i) => 'остаток ${formatQty(i.stock)} ${i.unit}',
        leading: _avatar,
      );
      if (item == null || !mounted) return;
    }
    final qty = await _askQuantity(item, row?.quantity);
    if (qty == null || !mounted) return;
    setState(() {
      if (row != null) {
        if (qty <= 0) {
          _rows.remove(row);
        } else {
          row.quantity = qty;
        }
        return;
      }
      if (qty <= 0) return;
      final existing = _rows.where((r) => r.item.id == item!.id).firstOrNull;
      if (existing != null) {
        existing.quantity += qty;
      } else {
        _rows.add(_Row(item!, qty));
      }
    });
  }

  Future<double?> _askQuantity(_Item item, double? current) async {
    final controller = TextEditingController(text: current != null ? formatQty(current) : '');
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(item.name),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
            labelText: 'Количество, ${item.unit}',
            helperText: 'На складе ${formatQty(item.stock)} ${item.unit}',
          ),
          onSubmitted: (v) => Navigator.pop(context, v),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: Text(current != null ? 'Сохранить' : 'Добавить'),
          ),
        ],
      ),
    );
    if (value == null) return null;
    final qty = double.tryParse(value.trim().replaceAll(',', '.'));
    if (qty == null || qty < 0) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Введите корректное количество')));
      }
      return null;
    }
    return qty;
  }

  void _step(_Row row, int dir) {
    final step = quantityStep(row.item.unit);
    final next = row.quantity + dir * step;
    setState(() => row.quantity = next < step ? step : double.parse(next.toStringAsFixed(3)));
  }

  bool get _reasonRequired => !widget.isReceipt;

  Future<void> _save() async {
    final reason = _reason.text.trim();
    if (_rows.isEmpty) {
      setState(() => _error = 'Добавьте хотя бы одну позицию');
      return;
    }
    if (reason.isEmpty && _reasonRequired) {
      setState(() => _error = 'Напишите причину списания');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await _api.createStockDocument(
        isReceipt: widget.isReceipt,
        date: _date,
        reason: reason.isEmpty ? null : reason,
        items: [
          for (final r in _rows) {widget.isProduct ? 'productId' : 'ingredientId': r.item.id, 'quantity': r.quantity},
        ],
      );
      if (mounted) await playSuccessFeedback(context);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _saving = false;
      });
    }
  }

  Widget _pill({required Widget child, required VoidCallback onTap}) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: context.pal.card,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: context.pal.line),
        ),
        child: child,
      ),
    );
  }

  Widget _stepperButton(IconData icon, VoidCallback onTap) {
    return InkWell(
      customBorder: const CircleBorder(),
      onTap: onTap,
      child: Container(
        width: 26,
        height: 26,
        alignment: Alignment.center,
        decoration: BoxDecoration(color: context.pal.card, shape: BoxShape.circle),
        child: Icon(icon, size: 15, color: context.pal.ink),
      ),
    );
  }

  Widget _buildRow(_Row row, List<_Item> items) {
    final over = !widget.isReceipt && row.quantity > row.item.stock;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: context.pal.card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: over ? context.pal.danger : context.pal.line),
      ),
      child: Row(
        children: [
          _avatar(row.item),
          const SizedBox(width: 12),
          Expanded(
            child: InkWell(
              onTap: () => _pick(items, row: row),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(row.item.name,
                      maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(
                    over
                        ? 'на складе только ${formatQty(row.item.stock)} ${row.item.unit}'
                        : 'остаток ${formatQty(row.item.stock)} ${row.item.unit}',
                    style: TextStyle(color: over ? context.pal.danger : context.pal.muted, fontSize: 12.5),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 8),
          Container(
            decoration: BoxDecoration(
              color: context.pal.bg,
              borderRadius: BorderRadius.circular(30),
              border: Border.all(color: context.pal.line),
            ),
            padding: const EdgeInsets.all(4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _stepperButton(Icons.remove, () => _step(row, -1)),
                InkWell(
                  onTap: () => _pick(items, row: row),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    child: Text('${formatQty(row.quantity)} ${row.item.unit}',
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                  ),
                ),
                _stepperButton(Icons.add, () => _step(row, 1)),
              ],
            ),
          ),
          IconButton(
            icon: Icon(Icons.close, size: 16, color: context.pal.muted),
            onPressed: () => setState(() => _rows.remove(row)),
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 30, minHeight: 30),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.isReceipt
            ? (widget.isProduct ? 'Поступление товара' : 'Поступление сырья')
            : (widget.isProduct ? 'Списание товара' : 'Списание сырья')),
      ),
      body: FutureBuilder<List<_Item>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(error: snapshot.error.toString(), onRetry: () => setState(() => _future = _load()));
          }
          final items = snapshot.data!;
          final canSave = !_saving && _rows.isNotEmpty && (!_reasonRequired || _reason.text.trim().isNotEmpty);
          final buttonColor = widget.isReceipt ? context.pal.success : context.pal.danger;
          final verb = widget.isReceipt ? 'Оприходовать' : 'Списать';
          return Column(
            children: [
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  children: [
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Text(_error!, style: TextStyle(color: context.pal.danger)),
                      ),
                    _pill(
                      onTap: () async {
                        final now = DateTime.now();
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: _date,
                          firstDate: now.subtract(const Duration(days: 365)),
                          lastDate: now,
                        );
                        if (picked != null) setState(() => _date = picked);
                      },
                      child: Row(
                        children: [
                          Icon(Icons.event_outlined, color: context.pal.muted),
                          const SizedBox(width: 10),
                          Text(widget.isReceipt ? 'Дата поступления' : 'Дата списания',
                              style: TextStyle(color: context.pal.muted)),
                          const Spacer(),
                          Text(
                            DateUtils.isSameDay(_date, DateTime.now()) ? 'Сегодня, ${formatDate(_date)}' : formatDate(_date),
                            style: TextStyle(color: context.pal.accent, fontWeight: FontWeight.w600),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 16),
                    for (final r in _rows) _buildRow(r, items),
                    if (_rows.isEmpty)
                      _pill(
                        onTap: () => _pick(items),
                        child: Row(
                          children: [
                            Icon(Icons.add_circle_outline, color: context.pal.accent),
                            const SizedBox(width: 12),
                            Text(widget.isProduct ? 'Выбрать товар' : 'Выбрать сырьё',
                                style: TextStyle(color: context.pal.muted)),
                          ],
                        ),
                      )
                    else
                      InkWell(
                        borderRadius: BorderRadius.circular(30),
                        onTap: () => _pick(items),
                        child: Container(
                          width: double.infinity,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(30),
                            border: Border.all(color: context.pal.accent),
                          ),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.add, size: 18, color: context.pal.accent),
                              const SizedBox(width: 6),
                              Text('Добавить позицию',
                                  style: TextStyle(color: context.pal.accent, fontWeight: FontWeight.w600)),
                            ],
                          ),
                        ),
                      ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: _reason,
                      decoration: InputDecoration(
                        labelText: widget.isReceipt ? 'Комментарий' : 'Причина списания *',
                        hintText: widget.isReceipt ? 'Откуда поступило, излишки...' : 'Порча, брак, недостача...',
                      ),
                      maxLines: 3,
                      textCapitalization: TextCapitalization.sentences,
                    ),
                  ],
                ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                  child: SizedBox(
                    width: double.infinity,
                    height: 52,
                    child: FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: buttonColor,
                        disabledBackgroundColor: buttonColor.withValues(alpha: 0.35),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(30)),
                      ),
                      onPressed: canSave ? _save : null,
                      child: _saving
                          ? const SizedBox(
                              height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                          : Text(
                              _rows.isEmpty ? verb : '$verb ${_rows.length} поз.',
                              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Colors.white),
                            ),
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
