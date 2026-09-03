import 'package:flutter/material.dart';

import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';

class ProductionRequestFormScreen extends StatefulWidget {
  const ProductionRequestFormScreen({super.key});

  @override
  State<ProductionRequestFormScreen> createState() => _ProductionRequestFormScreenState();
}

class _ProductionRequestFormScreenState extends State<ProductionRequestFormScreen> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _batches = TextEditingController();
  late Future<List<Product>> _future;
  Product? _product;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _api.getEligibleProductsForRequest();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate() || _product == null) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final batches = double.parse(_batches.text.replaceAll(',', '.'));
      await _api.createProductionRequest(_product!.id, batches);
      if (mounted) Navigator.pop(context, true);
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
      appBar: AppBar(title: const Text('Новая заявка на сырьё')),
      body: FutureBuilder<List<Product>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(
              error: snapshot.error.toString(),
              onRetry: () => setState(() => _future = _api.getEligibleProductsForRequest()),
            );
          }
          final products = snapshot.data!;
          if (products.isEmpty) {
            return const Padding(
              padding: EdgeInsets.all(24),
              child: Center(
                child: Text(
                  'Нет ни одного товара с заполненной рецептурой — сначала добавьте рецептуру на странице товара.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
              ),
            );
          }
          return Padding(
            padding: const EdgeInsets.all(16),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: const TextStyle(color: Colors.red)),
                    ),
                  DropdownButtonFormField<Product>(
                    initialValue: _product,
                    decoration: const InputDecoration(labelText: 'Товар'),
                    items: [
                      for (final p in products) DropdownMenuItem(value: p, child: Text(p.name)),
                    ],
                    onChanged: (v) => setState(() => _product = v),
                    validator: (v) => v == null ? 'Выберите товар' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _batches,
                    decoration: const InputDecoration(labelText: 'Количество замесов'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    validator: (v) =>
                        (v == null || double.tryParse(v.replaceAll(',', '.')) == null) ? 'Введите число' : null,
                  ),
                  const SizedBox(height: 20),
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    child: _saving
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Создать заявку'),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
