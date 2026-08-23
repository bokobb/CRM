import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// Общий экран для "Поступления" и "Списание товара" — отличаются только типом движения
/// (RECEIPT/WRITE_OFF), заголовком и тем, какой метод ApiClient дёргается при сохранении.
class StockMovementScreen extends StatefulWidget {
  final bool isReceipt;

  const StockMovementScreen({super.key, required this.isReceipt});

  @override
  State<StockMovementScreen> createState() => _StockMovementScreenState();
}

class _StockMovementScreenState extends State<StockMovementScreen> {
  final _api = ApiClient();
  late Future<List<StockMovement>> _future;

  String get _type => widget.isReceipt ? 'RECEIPT' : 'WRITE_OFF';
  String get _title => widget.isReceipt ? 'Поступления' : 'Списание товара';

  @override
  void initState() {
    super.initState();
    _future = _api.getStockMovements(type: _type);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getStockMovements(type: _type);
    });
    await _future;
  }

  Future<void> _openForm() async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _StockMovementForm(isReceipt: widget.isReceipt),
    );
    if (saved == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_title)),
      drawer: const AppDrawer(currentRoute: '/warehouse'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: RefreshIndicator(
        onRefresh: _refresh,
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
                  child: Center(child: Text('Записей пока нет', style: TextStyle(color: Colors.grey))),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: movements.length,
              itemBuilder: (context, index) {
                final m = movements[index];
                return AppListTile(
                  leadingIcon: m.isProduct ? Icons.cake_outlined : Icons.grain_outlined,
                  leadingColor: m.isProduct ? const Color(0xFFB8324A) : const Color(0xFFD9A73F),
                  title: m.itemName,
                  subtitle:
                      '${m.reason?.isNotEmpty == true ? '${m.reason} · ' : ''}${m.createdBy} · ${formatDateTime(m.createdAt)}',
                  trailing: Text(
                    '${widget.isReceipt ? '+' : '−'}${formatQty(m.quantity)} ${m.unit}',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: widget.isReceipt ? const Color(0xFF4C8A5E) : const Color(0xFFB8433F),
                    ),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _StockMovementForm extends StatefulWidget {
  final bool isReceipt;

  const _StockMovementForm({required this.isReceipt});

  @override
  State<_StockMovementForm> createState() => _StockMovementFormState();
}

class _StockMovementFormState extends State<_StockMovementForm> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _quantity = TextEditingController();
  final _reason = TextEditingController();
  bool _isProductKind = false;
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
    if (_isProductKind) {
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
      if (_isProductKind) {
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
                child: Text(_error!, style: const TextStyle(color: Colors.red)),
              ),
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(value: false, label: Text('Сырьё'), icon: Icon(Icons.grain_outlined)),
                ButtonSegment(value: true, label: Text('Готовая продукция'), icon: Icon(Icons.cake_outlined)),
              ],
              selected: {_isProductKind},
              onSelectionChanged: (s) {
                setState(() {
                  _isProductKind = s.first;
                  _itemId = null;
                });
                _loadItems();
              },
            ),
            const SizedBox(height: 12),
            if (_loadingItems)
              const Center(child: Padding(padding: EdgeInsets.all(12), child: CircularProgressIndicator()))
            else if (_isProductKind)
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
