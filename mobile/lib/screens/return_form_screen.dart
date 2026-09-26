import 'package:flutter/material.dart';

import '../main.dart';
import '../models/client.dart';
import '../models/product.dart';
import '../models/product_return.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/pick_from_list.dart';
import '../widgets/product_avatar.dart';
import '../widgets/success_feedback.dart';

class _ReturnItemRow {
  Product? product;
  final TextEditingController quantity = TextEditingController(text: '1');

  /// Цена из уже сохранённого возврата (правка) — новые позиции берут цену клиента.
  double? price;
}

/// Форма возврата — повторяет форму заказа (см. order_form_screen.dart), но товар выбирается
/// без экрана с картинкой: сразу вводится количество. С карточки заказа клиент уже выбран
/// ([clientId]/[clientName]) и возврат закрепляется за заказом ([orderId]); из раздела
/// "Возвраты" клиент выбирается как в заказе; [initial] — правка существующего возврата.
class ReturnFormScreen extends StatefulWidget {
  final int? clientId;
  final String? clientName;
  final int? orderId;
  final ProductReturn? initial;

  const ReturnFormScreen({super.key, this.clientId, this.clientName, this.orderId, this.initial});

  @override
  State<ReturnFormScreen> createState() => _ReturnFormScreenState();
}

class _ReturnFormScreenState extends State<ReturnFormScreen> {
  final _api = ApiClient();
  late Future<(List<Client>, List<Product>)> _future;

  int? _clientId;
  String? _clientName;
  DateTime _date = DateTime.now();
  final _notes = TextEditingController();
  final List<_ReturnItemRow> _rows = [_ReturnItemRow()];
  Map<int, double> _clientPrices = {};
  bool _loadingPrices = false;
  bool _saving = false;
  String? _error;

  bool get _isEdit => widget.initial != null;

  /// С карточки заказа клиент зафиксирован — возврат закрепляется именно за этим заказом.
  bool get _clientLocked => widget.orderId != null;

  @override
  void initState() {
    super.initState();
    final r = widget.initial;
    _clientId = r?.clientId ?? widget.clientId;
    _clientName = r?.clientName ?? widget.clientName;
    if (r != null) {
      _date = r.returnDate;
      _notes.text = r.reason ?? '';
    }
    _future = _load();
    if (_clientId != null) _loadPrices(_clientId!);
  }

  Future<(List<Client>, List<Product>)> _load() async {
    final clients = _clientLocked ? <Client>[] : await _api.getClients();
    final products = await _api.getProducts();
    final r = widget.initial;
    if (r != null && _rows.length == 1 && _rows.first.product == null) {
      _rows
        ..clear()
        ..addAll(
          r.items.map((i) {
            final row = _ReturnItemRow()
              ..product = products.where((p) => p.id == i.productId).firstOrNull
              ..price = i.price;
            row.quantity.text = formatQty(i.quantity);
            return row;
          }),
        );
    }
    return (clients, products);
  }

  Future<void> _loadPrices(int clientId) async {
    setState(() => _loadingPrices = true);
    try {
      final prices = await _api.getClientPrices(clientId);
      if (mounted && _clientId == clientId) {
        setState(() => _clientPrices = {for (final p in prices) p.productId: p.price});
      }
    } catch (_) {
      // Без индивидуальных цен — базовые цены товаров (так же решит и сервер).
    } finally {
      if (mounted) setState(() => _loadingPrices = false);
    }
  }

  double _priceOf(_ReturnItemRow row) => row.price ?? _clientPrices[row.product!.id] ?? row.product!.price;

  double _qtyOf(_ReturnItemRow row) =>
      double.tryParse(row.quantity.text.replaceAll(',', '.').replaceAll(RegExp(r'\s'), '')) ?? 0;

  int get _itemCount => _rows.where((r) => r.product != null).length;

  double get _total {
    double sum = 0;
    for (final r in _rows) {
      if (r.product == null) continue;
      sum += _qtyOf(r) * _priceOf(r);
    }
    return sum;
  }

  String get _itemsLabel {
    final n = _itemCount;
    final mod10 = n % 10, mod100 = n % 100;
    final word = (mod10 == 1 && mod100 != 11)
        ? 'товар'
        : (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20))
        ? 'товара'
        : 'товаров';
    return '$n $word';
  }

  void _clearAll() {
    setState(() {
      if (!_clientLocked) {
        _clientId = null;
        _clientName = null;
        _clientPrices = {};
      }
      _date = DateTime.now();
      _notes.clear();
      _error = null;
      _rows
        ..clear()
        ..add(_ReturnItemRow());
    });
  }

  /// Выбор товара из списка — и сразу ввод количества, без экрана товара с картинкой.
  Future<void> _pickProduct(List<Product> products, {_ReturnItemRow? row}) async {
    final product = await pickFromList<Product>(
      context: context,
      title: 'Поиск товара',
      items: products,
      label: (p) => p.name,
      subtitle: (p) => formatMoney(_clientPrices[p.id] ?? p.price),
      leading: (p) => ProductAvatar(name: p.name, imageUrl: p.imageUrl),
    );
    if (product == null || !mounted) return;
    final qty = await _askQuantity(product, '1');
    if (qty == null || !mounted) return;
    setState(() {
      // Товар уже есть в списке (по обычной цене) — прибавляем количество к той строке,
      // а не заводим повтор; сервер всё равно склеил бы их при сохранении.
      final same = row == null
          ? _rows.where((r) => r.product?.id == product.id && r.price == null).firstOrNull
          : null;
      if (same != null) {
        final added = double.tryParse(qty.replaceAll(',', '.').replaceAll(RegExp(r'\s'), '')) ?? 0;
        same.quantity.text = formatQty(_qtyOf(same) + added);
        return;
      }
      final target = row ?? _ReturnItemRow();
      target.product = product;
      target.price = null;
      target.quantity.text = qty;
      if (row == null) {
        // Пустую строку "Выбрать товар" заменяем, а не оставляем висеть над новой позицией.
        final empty = _rows.where((r) => r.product == null).firstOrNull;
        if (empty != null) {
          _rows[_rows.indexOf(empty)] = target;
        } else {
          _rows.add(target);
        }
      }
    });
  }

  Future<String?> _askQuantity(Product product, String initial) async {
    final controller = TextEditingController(text: initial)
      ..selection = TextSelection(baseOffset: 0, extentOffset: initial.length);
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(product.name),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: 'Количество${product.unit != null ? ', ${product.unit}' : ''}'),
          onSubmitted: (v) => Navigator.pop(context, v),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Готово')),
        ],
      ),
    );
    if (value == null) return null;
    final q = double.tryParse(value.replaceAll(',', '.').replaceAll(RegExp(r'\s'), ''));
    return q == null || q <= 0 ? null : formatQty(q);
  }

  void _changeQty(_ReturnItemRow row, double delta, double step) {
    final next = _qtyOf(row) + delta;
    setState(() => row.quantity.text = formatQty(next < step ? step : next));
  }

  Future<void> _editQty(_ReturnItemRow row) async {
    final value = await _askQuantity(row.product!, row.quantity.text);
    if (value != null) setState(() => row.quantity.text = value);
  }

  Future<void> _save() async {
    if (_clientId == null) {
      setState(() => _error = 'Выберите клиента');
      return;
    }
    final items = _rows
        .where((r) => r.product != null && _qtyOf(r) > 0)
        .map((r) => (r.product!.id, _qtyOf(r), r.price))
        .toList();
    if (items.isEmpty) {
      setState(() => _error = 'Добавьте хотя бы одну позицию');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    final notes = _notes.text.trim().isEmpty ? null : _notes.text.trim();
    try {
      final saved = _isEdit
          ? await _api.updateReturn(
              widget.initial!.id,
              clientId: _clientId!,
              items: items,
              returnDate: _date,
              reason: notes,
            )
          : await _api.createReturn(
              clientId: _clientId!,
              items: items,
              returnDate: _date,
              reason: notes,
              orderId: widget.orderId,
            );
      if (mounted) await playSuccessFeedback(context);
      if (mounted) Navigator.of(context).pop(saved);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _saving = false;
      });
    }
  }


  Widget _pillRow({required Widget child, VoidCallback? onTap}) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: const Color(0xFFE7E0D8)),
        ),
        child: child,
      ),
    );
  }

  Widget _quantityStepper(_ReturnItemRow row, Product product) {
    final step = quantityStep(product.unit);
    return Container(
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(30),
        border: Border.all(color: const Color(0xFFE7E0D8)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _stepperButton(Icons.remove, () => _changeQty(row, -step, step)),
          InkWell(
            onTap: () => _editQty(row),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              child: Text(
                row.quantity.text.isEmpty ? '0' : row.quantity.text,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
          ),
          _stepperButton(Icons.add, () => _changeQty(row, step, step)),
        ],
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
        decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
        child: Icon(icon, size: 15, color: ink),
      ),
    );
  }

  Widget _buildItemRow(int i, List<Product> products) {
    final row = _rows[i];
    final product = row.product;
    if (product == null) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: _pillRow(
          onTap: () => _pickProduct(products, row: row),
          child: Row(
            children: [
              const Icon(Icons.add_circle_outline, color: accent),
              const SizedBox(width: 12),
              const Expanded(
                child: Text('Выбрать товар', style: TextStyle(color: Colors.grey)),
              ),
              if (_rows.length > 1)
                IconButton(icon: const Icon(Icons.close, size: 18), onPressed: () => setState(() => _rows.removeAt(i))),
            ],
          ),
        ),
      );
    }
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFE7E0D8)),
      ),
      child: Row(
        children: [
          ProductAvatar(name: product.name, imageUrl: product.imageUrl),
          const SizedBox(width: 12),
          Expanded(
            child: InkWell(
              onTap: () => _pickProduct(products, row: row),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    product.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '${formatMoney(_priceOf(row))}${product.unit != null ? ' / ${product.unit}' : ''}',
                    style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 8),
          _quantityStepper(row, product),
          if (_rows.length > 1) ...[
            const SizedBox(width: 2),
            IconButton(
              icon: const Icon(Icons.close, size: 16, color: Colors.grey),
              onPressed: () => setState(() => _rows.removeAt(i)),
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(minWidth: 30, minHeight: 30),
            ),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          _isEdit
              ? 'Возврат №${widget.initial!.id}'
              : widget.orderId != null
              ? 'Возврат по заказу #${widget.orderId}'
              : 'Новый возврат',
        ),
        actions: [
          TextButton(
            onPressed: _clearAll,
            child: const Text(
              'Очистить',
              style: TextStyle(color: accent, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
      body: FutureBuilder<(List<Client>, List<Product>)>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(error: snapshot.error.toString(), onRetry: () => setState(() => _future = _load()));
          }
          final (clients, products) = snapshot.data!;
          final canSave = !_saving && _clientId != null && _itemCount > 0;
          return Column(
            children: [
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  children: [
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Text(_error!, style: const TextStyle(color: Colors.red)),
                      ),
                    _pillRow(
                      onTap: _clientLocked
                          ? null
                          : () async {
                              final picked = await pickFromList<Client>(
                                context: context,
                                title: 'Поиск клиента',
                                items: clients,
                                label: (c) => c.name,
                                subtitle: (c) => c.phone ?? '',
                              );
                              if (picked == null) return;
                              setState(() {
                                _clientId = picked.id;
                                _clientName = picked.name;
                                _clientPrices = {};
                              });
                              _loadPrices(picked.id);
                            },
                      child: Row(
                        children: [
                          Icon(Icons.person_outline, color: _clientId == null ? Colors.grey : accent),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              _clientName ?? 'Выбрать клиента',
                              style: TextStyle(
                                color: _clientId == null ? Colors.grey : ink,
                                fontWeight: _clientId == null ? FontWeight.normal : FontWeight.w600,
                              ),
                            ),
                          ),
                          if (_loadingPrices)
                            const SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                          else if (!_clientLocked)
                            const Icon(Icons.chevron_right, color: Colors.grey),
                        ],
                      ),
                    ),
                    const SizedBox(height: 10),
                    _pillRow(
                      onTap: () async {
                        final now = DateTime.now();
                        final yearAgo = now.subtract(const Duration(days: 365));
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: _date,
                          firstDate: _date.isBefore(yearAgo) ? _date : yearAgo,
                          lastDate: now,
                        );
                        if (picked != null) setState(() => _date = picked);
                      },
                      child: Row(
                        children: [
                          const Text('Дата возврата', style: TextStyle(color: Colors.grey)),
                          const Spacer(),
                          Text(
                            formatDate(_date),
                            style: const TextStyle(color: accent, fontWeight: FontWeight.w600),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),
                    Text(
                      '$_itemsLabel в возврате\nна сумму ${formatMoney(_total)}',
                      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, height: 1.25),
                    ),
                    const SizedBox(height: 16),
                    for (int i = 0; i < _rows.length; i++) _buildItemRow(i, products),
                    InkWell(
                      borderRadius: BorderRadius.circular(30),
                      onTap: () => _pickProduct(products),
                      child: Container(
                        width: double.infinity,
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(30),
                          border: Border.all(color: accent),
                        ),
                        child: const Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(Icons.add, size: 18, color: accent),
                            SizedBox(width: 6),
                            Text(
                              'Добавить позицию',
                              style: TextStyle(color: accent, fontWeight: FontWeight.w600),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: _notes,
                      decoration: const InputDecoration(labelText: 'Комментарий'),
                      maxLines: 3,
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
                        backgroundColor: accent,
                        disabledBackgroundColor: accent.withValues(alpha: 0.35),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(30)),
                      ),
                      onPressed: canSave ? _save : null,
                      child: _saving
                          ? const SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                            )
                          : Text(
                              _isEdit
                                  ? 'Сохранить и перепровести'
                                  : _itemCount > 0
                                  ? 'Оформить возврат на ${formatMoney(_total)}'
                                  : 'Оформить возврат',
                              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
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
