import 'package:flutter/material.dart';

import '../models/client.dart';
import '../models/client_price.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class _OrderItemRow {
  Product? product;
  final TextEditingController quantity = TextEditingController();
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

  double get _total {
    double sum = 0;
    for (final r in _rows) {
      if (r.product == null) continue;
      final qty = double.tryParse(r.quantity.text.replaceAll(',', '.')) ?? 0;
      sum += qty * _priceFor(r.product!);
    }
    return sum;
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

  Future<void> _save() async {
    if (_client == null) {
      setState(() => _error = 'Выберите клиента');
      return;
    }
    final items = _rows
        .where((r) => r.product != null && r.quantity.text.trim().isNotEmpty)
        .map((r) => {
              'productId': r.product!.id,
              'quantity': double.tryParse(r.quantity.text.replaceAll(',', '.')) ?? 0,
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
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                      child: TextField(
                        autofocus: true,
                        decoration: InputDecoration(labelText: title, prefixIcon: const Icon(Icons.search)),
                        onChanged: (v) => setSheetState(() => query = v),
                      ),
                    ),
                    Expanded(
                      child: ListView.separated(
                        itemCount: filtered.length,
                        separatorBuilder: (_, _) => const Divider(height: 1),
                        itemBuilder: (context, i) {
                          final item = filtered[i];
                          return ListTile(
                            title: Text(label(item)),
                            subtitle: subtitle != null ? Text(subtitle(item)) : null,
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Новый заказ')),
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
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(_error!, style: const TextStyle(color: Colors.red)),
                ),
              InkWell(
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
                child: InputDecorator(
                  decoration: const InputDecoration(labelText: 'Клиент', suffixIcon: Icon(Icons.search)),
                  child: Text(_client?.name ?? 'Выбрать клиента',
                      style: _client == null ? const TextStyle(color: Colors.grey) : null),
                ),
              ),
              const SizedBox(height: 12),
              InkWell(
                onTap: () async {
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: DateTime.now(),
                    firstDate: DateTime.now().subtract(const Duration(days: 1)),
                    lastDate: DateTime.now().add(const Duration(days: 365)),
                  );
                  if (picked != null) setState(() => _dueDate = picked);
                },
                child: InputDecorator(
                  decoration: const InputDecoration(labelText: 'Срок выполнения'),
                  child: Text(_dueDate == null ? 'Не указан' : formatDate(_dueDate!)),
                ),
              ),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Состав заказа', style: Theme.of(context).textTheme.titleMedium),
                  if (_loadingPrices)
                    const SizedBox(
                        height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                ],
              ),
              const SizedBox(height: 8),
              for (int i = 0; i < _rows.length; i++)
                Card(
                  margin: const EdgeInsets.only(bottom: 10),
                  child: Padding(
                    padding: const EdgeInsets.all(10),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: InkWell(
                                onTap: () async {
                                  final picked = await _pickFromList<Product>(
                                    context: context,
                                    title: 'Поиск товара',
                                    items: products,
                                    label: (p) => p.name,
                                    subtitle: (p) => formatMoney(_priceFor(p)),
                                  );
                                  if (picked != null) setState(() => _rows[i].product = picked);
                                },
                                child: InputDecorator(
                                  decoration: const InputDecoration(labelText: 'Товар', isDense: true),
                                  child: Text(_rows[i].product?.name ?? 'Выбрать товар',
                                      overflow: TextOverflow.ellipsis,
                                      style: _rows[i].product == null ? const TextStyle(color: Colors.grey) : null),
                                ),
                              ),
                            ),
                            IconButton(
                              icon: const Icon(Icons.close, size: 18),
                              onPressed: _rows.length == 1 ? null : () => setState(() => _rows.removeAt(i)),
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        Row(
                          children: [
                            Expanded(
                              child: TextField(
                                controller: _rows[i].quantity,
                                decoration: const InputDecoration(labelText: 'Кол-во', isDense: true),
                                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                                onChanged: (_) => setState(() {}),
                              ),
                            ),
                            const SizedBox(width: 12),
                            if (_rows[i].product != null)
                              Text(
                                formatMoney(
                                    (double.tryParse(_rows[i].quantity.text.replaceAll(',', '.')) ?? 0) *
                                        _priceFor(_rows[i].product!)),
                                style: const TextStyle(fontWeight: FontWeight.bold),
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              OutlinedButton.icon(
                onPressed: () async {
                  final picked = await _pickFromList<Product>(
                    context: context,
                    title: 'Поиск товара',
                    items: products,
                    label: (p) => p.name,
                    subtitle: (p) => formatMoney(_priceFor(p)),
                  );
                  if (picked != null) {
                    setState(() {
                      final row = _OrderItemRow()..product = picked;
                      _rows.add(row);
                    });
                  }
                },
                icon: const Icon(Icons.add),
                label: const Text('Добавить позицию'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _notes,
                decoration: const InputDecoration(labelText: 'Комментарий'),
                maxLines: 3,
              ),
              const SizedBox(height: 20),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('Итого', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                  Text(formatMoney(_total), style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                ],
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: _saving ? null : _save,
                child: _saving
                    ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Создать заказ'),
              ),
            ],
          );
        },
      ),
    );
  }
}
