import 'package:flutter/material.dart';

import '../models/client.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class _OrderItemRow {
  int? productId;
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

  int? _clientId;
  DateTime? _dueDate;
  final _notes = TextEditingController();
  final List<_OrderItemRow> _rows = [_OrderItemRow()];
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

  Future<void> _save() async {
    if (_clientId == null) {
      setState(() => _error = 'Выберите клиента');
      return;
    }
    final items = _rows
        .where((r) => r.productId != null && r.quantity.text.trim().isNotEmpty)
        .map((r) => {
              'productId': r.productId,
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
      'clientId': _clientId,
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
            return ErrorView(error: snapshot.error.toString(), onRetry: () => setState(() => _future = _load()));
          }
          final (clients, products) = snapshot.data!;
          return StatefulBuilder(
            builder: (context, setLocalState) {
              return ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: const TextStyle(color: Colors.red)),
                    ),
                  DropdownButtonFormField<int>(
                    initialValue: _clientId,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Клиент'),
                    items: [for (final c in clients) DropdownMenuItem(value: c.id, child: Text(c.name))],
                    onChanged: (value) => setLocalState(() => _clientId = value),
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
                      if (picked != null) setLocalState(() => _dueDate = picked);
                    },
                    child: InputDecorator(
                      decoration: const InputDecoration(labelText: 'Срок выполнения'),
                      child: Text(_dueDate == null ? 'Не указан' : formatDate(_dueDate!)),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text('Состав заказа', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  for (int i = 0; i < _rows.length; i++)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Row(
                        children: [
                          Expanded(
                            flex: 3,
                            child: DropdownButtonFormField<int>(
                              initialValue: _rows[i].productId,
                              isExpanded: true,
                              decoration: const InputDecoration(labelText: 'Товар'),
                              items: [
                                for (final p in products)
                                  DropdownMenuItem(value: p.id, child: Text('${p.name} — ${formatMoney(p.price)}')),
                              ],
                              onChanged: (value) => setLocalState(() => _rows[i].productId = value),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              controller: _rows[i].quantity,
                              decoration: const InputDecoration(labelText: 'Кол-во'),
                              keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.close, size: 18),
                            onPressed: _rows.length == 1
                                ? null
                                : () => setLocalState(() => _rows.removeAt(i)),
                          ),
                        ],
                      ),
                    ),
                  OutlinedButton.icon(
                    onPressed: () => setLocalState(() => _rows.add(_OrderItemRow())),
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
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    child: _saving
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Создать заказ'),
                  ),
                ],
              );
            },
          );
        },
      ),
    );
  }
}
