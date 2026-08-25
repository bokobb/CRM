import 'package:flutter/material.dart';

import '../main.dart';
import '../models/client.dart';
import '../models/client_price.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/success_feedback.dart';
import 'product_quantity_screen.dart';

class _OrderItemRow {
  Product? product;
  final TextEditingController quantity = TextEditingController(text: '1');
}

class OrderFormScreen extends StatefulWidget {
  const OrderFormScreen({super.key});

  @override
  State<OrderFormScreen> createState() => _OrderFormScreenState();
}

class _OrderFormScreenState extends State<OrderFormScreen> {
  final _api = ApiClient();
  late Future<(List<Client>, List<Product>)> _future;

  Client? _client;
  DateTime? _dueDate;
  final _notes = TextEditingController();
  final List<_OrderItemRow> _rows = [_OrderItemRow()];
  Map<int, double> _clientPrices = {};
  bool _loadingPrices = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(List<Client>, List<Product>)> _load() async {
    final clients = await _api.getClients();
    final products = await _api.getProducts();
    return (clients, products);
  }

  double _priceFor(Product p) => _clientPrices[p.id] ?? p.price;

  double _step(Product p) => quantityStep(p.unit);

  double _qtyOf(_OrderItemRow row) => double.tryParse(row.quantity.text.replaceAll(',', '.')) ?? 0;

  int get _itemCount => _rows.where((r) => r.product != null).length;

  double get _total {
    double sum = 0;
    for (final r in _rows) {
      if (r.product == null) continue;
      sum += _qtyOf(r) * _priceFor(r.product!);
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
      _client = null;
      _dueDate = null;
      _clientPrices = {};
      _notes.clear();
      _error = null;
      _rows
        ..clear()
        ..add(_OrderItemRow());
    });
  }

  Future<void> _selectClient(Client c) async {
    setState(() {
      _client = c;
      _loadingPrices = true;
    });
    try {
      final prices = await _api.getClientPrices(c.id);
      setState(() {
        _clientPrices = {for (final ClientPrice p in prices) p.productId: p.price};
        _loadingPrices = false;
      });
    } catch (_) {
      setState(() => _loadingPrices = false);
    }
  }

  /// Открывает полноэкранный выбор товара с картинкой и степпером количества.
  /// Если [row] уже содержит товар — редактирует его количество на месте (или
  /// добавляет новую строку выбранным товаром, если [row] пустая/не задана).
  Future<void> _openProductScreen(List<Product> products, {_OrderItemRow? row}) async {
    Product? product = row?.product;
    if (product == null) {
      product = await _pickFromList<Product>(
        context: context,
        title: 'Поиск товара',
        items: products,
        label: (p) => p.name,
        subtitle: (p) => formatMoney(_priceFor(p)),
      );
      if (product == null || !mounted) return;
    }
    final isEditing = row?.product != null;
    final result = await Navigator.of(context).push<double>(
      MaterialPageRoute(
        builder: (context) => ProductQuantityScreen(
          product: product!,
          unitPrice: _priceFor(product),
          initialQuantity: row != null ? _qtyOf(row) : 1,
          isEditing: isEditing,
        ),
      ),
    );
    if (result == null || !mounted) return;
    setState(() {
      if (result <= 0) {
        if (row != null && _rows.length > 1) {
          _rows.remove(row);
        } else if (row != null) {
          row.product = null;
        }
        return;
      }
      final target = row ?? _OrderItemRow();
      target.product = product;
      target.quantity.text = formatQty(result);
      if (row == null) _rows.add(target);
    });
  }

  void _changeQty(_OrderItemRow row, double delta, double step) {
    final next = _qtyOf(row) + delta;
    setState(() => row.quantity.text = formatQty(next < step ? step : next));
  }

  Future<void> _editQty(_OrderItemRow row) async {
    final controller = TextEditingController(text: row.quantity.text);
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Количество'),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(isDense: true),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text),
            child: const Text('Готово'),
          ),
        ],
      ),
    );
    if (value != null) setState(() => row.quantity.text = value);
  }

  Future<void> _save() async {
    if (_client == null) {
      setState(() => _error = 'Выберите клиента');
      return;
    }
    final items = _rows
        .where((r) => r.product != null && r.quantity.text.trim().isNotEmpty)
        .map((r) => {
              'productId': r.product!.id,
              'quantity': _qtyOf(r),
            })
        .toList();
    if (items.isEmpty) {
      setState(() => _error = 'Добавьте хотя бы одну позицию');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    final body = {
      'clientId': _client!.id,
      'dueDate': _dueDate?.toIso8601String().split('T').first,
      'notes': _notes.text.trim(),
      'items': items,
    };
    try {
      final order = await _api.createOrder(body);
      if (mounted) {
        await playSuccessFeedback(context);
      }
      if (mounted) {
        Navigator.of(context).pushReplacementNamed('/orders/detail', arguments: order.id);
      }
    } catch (e) {
      setState(() {
        _error = e.toString();
        _saving = false;
      });
    }
  }

  Future<T?> _pickFromList<T>({
    required BuildContext context,
    required String title,
    required List<T> items,
    required String Function(T) label,
    String Function(T)? subtitle,
  }) {
    return showModalBottomSheet<T>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) {
        var query = '';
        return StatefulBuilder(
          builder: (context, setSheetState) {
            final filtered = query.isEmpty
                ? items
                : items.where((i) => label(i).toLowerCase().contains(query.toLowerCase())).toList();
            return Padding(
              padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
              child: SizedBox(
                height: MediaQuery.of(context).size.height * 0.75,
                child: Column(
                  children: [
                    const SizedBox(height: 10),
                    Container(
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: const Color(0xFFE7E0D8),
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
                      child: TextField(
                        autofocus: true,
                        decoration: InputDecoration(labelText: title, prefixIcon: const Icon(Icons.search)),
                        onChanged: (v) => setSheetState(() => query = v),
                      ),
                    ),
                    Expanded(
                      child: ListView.builder(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        itemCount: filtered.length,
                        itemBuilder: (context, i) {
                          final item = filtered[i];
                          return AppListTile(
                            leading: InitialAvatar(text: label(item)),
                            title: label(item),
                            subtitle: subtitle != null ? subtitle(item) : null,
                            onTap: () => Navigator.pop(context, item),
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

  Widget _pillRow({required Widget child, required VoidCallback onTap}) {
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

  Widget _quantityStepper(_OrderItemRow row, Product product) {
    final step = _step(product);
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
          onTap: () => _openProductScreen(products, row: row),
          child: Row(
            children: [
              const Icon(Icons.add_circle_outline, color: accent),
              const SizedBox(width: 12),
              const Expanded(
                child: Text('Выбрать товар', style: TextStyle(color: Colors.grey)),
              ),
              if (_rows.length > 1)
                IconButton(
                  icon: const Icon(Icons.close, size: 18),
                  onPressed: () => setState(() => _rows.removeAt(i)),
                ),
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
          InitialAvatar(text: product.name),
          const SizedBox(width: 12),
          Expanded(
            child: InkWell(
              onTap: () => _openProductScreen(products, row: row),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(
                    '${formatMoney(_priceFor(product))}${product.unit != null ? ' / ${product.unit}' : ''}',
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
        title: const Text('Новый заказ'),
        actions: [
          TextButton(
            onPressed: _clearAll,
            child: const Text('Очистить', style: TextStyle(color: accent, fontWeight: FontWeight.w600)),
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
            return ErrorView(
              error: snapshot.error.toString(),
              onRetry: () => setState(() {
                _future = _load();
              }),
            );
          }
          final (clients, products) = snapshot.data!;
          final canOrder = !_saving && _client != null && _itemCount > 0;
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
                      onTap: () async {
                        final picked = await _pickFromList<Client>(
                          context: context,
                          title: 'Поиск клиента',
                          items: clients,
                          label: (c) => c.name,
                          subtitle: (c) => c.phone ?? '',
                        );
                        if (picked != null) _selectClient(picked);
                      },
                      child: Row(
                        children: [
                          Icon(Icons.person_outline, color: _client == null ? Colors.grey : accent),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              _client?.name ?? 'Выбрать клиента',
                              style: TextStyle(
                                color: _client == null ? Colors.grey : ink,
                                fontWeight: _client == null ? FontWeight.normal : FontWeight.w600,
                              ),
                            ),
                          ),
                          if (_loadingPrices)
                            const SizedBox(
                                height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2))
                          else
                            const Icon(Icons.chevron_right, color: Colors.grey),
                        ],
                      ),
                    ),
                    const SizedBox(height: 10),
                    _pillRow(
                      onTap: () async {
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: DateTime.now(),
                          firstDate: DateTime.now().subtract(const Duration(days: 1)),
                          lastDate: DateTime.now().add(const Duration(days: 365)),
                        );
                        if (picked != null) setState(() => _dueDate = picked);
                      },
                      child: Row(
                        children: [
                          const Text('Срок выполнения', style: TextStyle(color: Colors.grey)),
                          const Spacer(),
                          Text(
                            _dueDate == null ? 'Не указан' : formatDate(_dueDate!),
                            style: const TextStyle(color: accent, fontWeight: FontWeight.w600),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),
                    Text(
                      '$_itemsLabel в заказе\nна сумму ${formatMoney(_total)}',
                      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, height: 1.25),
                    ),
                    const SizedBox(height: 16),
                    for (int i = 0; i < _rows.length; i++) _buildItemRow(i, products),
                    InkWell(
                      borderRadius: BorderRadius.circular(30),
                      onTap: () => _openProductScreen(products),
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
                            Text('Добавить позицию', style: TextStyle(color: accent, fontWeight: FontWeight.w600)),
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
                      onPressed: canOrder ? _save : null,
                      child: _saving
                          ? const SizedBox(
                              height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                          : Text(
                              _itemCount > 0 ? 'Оформить заказ на ${formatMoney(_total)}' : 'Оформить заказ',
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
