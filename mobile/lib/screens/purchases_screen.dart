import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../models/purchase.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// Закупки сырья у поставщиков — см. PurchaseService на бэкенде. Отдельный, более
/// подробный учёт (поставщик, сумма, дата) поверх обычного прихода на склад.
class PurchasesScreen extends StatefulWidget {
  const PurchasesScreen({super.key});

  @override
  State<PurchasesScreen> createState() => _PurchasesScreenState();
}

class _PurchasesScreenState extends State<PurchasesScreen> {
  final _api = ApiClient();
  late Future<List<Purchase>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getPurchases();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getPurchases());
    await _future;
  }

  Future<void> _openForm() async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) => const _PurchaseForm(),
    );
    if (saved == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Покупки')),
      drawer: const AppDrawer(currentRoute: '/purchases'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Purchase>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final purchases = snapshot.data!;
            if (purchases.isEmpty) {
              return ListView(children: const [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Закупок пока нет', style: TextStyle(color: Colors.grey))),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: purchases.length,
              itemBuilder: (context, index) {
                final p = purchases[index];
                return AppListTile(
                  leadingIcon: Icons.local_shipping_outlined,
                  leadingColor: const Color(0xFFD9A73F),
                  title: p.ingredientName,
                  subtitle: '${p.supplier} · ${formatQty(p.quantity)} ${p.ingredientUnit} · ${formatDate(p.purchaseDate)}',
                  trailing: p.totalPrice != null ? Text(formatMoney(p.totalPrice!)) : null,
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _PurchaseForm extends StatefulWidget {
  const _PurchaseForm();

  @override
  State<_PurchaseForm> createState() => _PurchaseFormState();
}

class _PurchaseFormState extends State<_PurchaseForm> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _quantity = TextEditingController();
  final _supplier = TextEditingController();
  final _totalPrice = TextEditingController();
  late Future<List<Ingredient>> _future;
  Ingredient? _ingredient;
  DateTime _date = DateTime.now();
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _api.getIngredients();
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now(),
    );
    if (picked != null) setState(() => _date = picked);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate() || _ingredient == null) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final qty = double.parse(_quantity.text.replaceAll(',', '.'));
      final priceText = _totalPrice.text.trim();
      final price = priceText.isEmpty ? null : double.tryParse(priceText.replaceAll(',', '.'));
      await _api.createPurchase(
        ingredientId: _ingredient!.id,
        quantity: qty,
        supplier: _supplier.text.trim(),
        totalPrice: price,
        purchaseDate: _date,
      );
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
    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: 16 + MediaQuery.of(context).viewInsets.bottom,
      ),
      child: FutureBuilder<List<Ingredient>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const SizedBox(
              height: 120,
              child: Center(child: CircularProgressIndicator()),
            );
          }
          if (snapshot.hasError) {
            return SizedBox(
              height: 120,
              child: ErrorView(
                error: snapshot.error.toString(),
                onRetry: () => setState(() => _future = _api.getIngredients()),
              ),
            );
          }
          final ingredients = snapshot.data!;
          return Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Новая закупка', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 16),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),
                DropdownButtonFormField<Ingredient>(
                  initialValue: _ingredient,
                  decoration: const InputDecoration(labelText: 'Сырьё'),
                  items: [
                    for (final i in ingredients)
                      DropdownMenuItem(value: i, child: Text('${i.name} (${formatQty(i.quantityInStock)} ${i.unit})')),
                  ],
                  onChanged: (v) => setState(() => _ingredient = v),
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
                  controller: _supplier,
                  decoration: const InputDecoration(labelText: 'Поставщик'),
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите поставщика' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _totalPrice,
                  decoration: const InputDecoration(labelText: 'Сумма закупки, ₸ (необязательно)'),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                ),
                const SizedBox(height: 12),
                InkWell(
                  onTap: _pickDate,
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: 'Дата'),
                    child: Text(formatDate(_date)),
                  ),
                ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Сохранить'),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
