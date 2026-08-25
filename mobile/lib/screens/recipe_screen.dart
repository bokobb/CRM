import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/product_avatar.dart';

class _RecipeRow {
  int? ingredientId;
  final TextEditingController quantity;

  _RecipeRow({this.ingredientId, String quantity = ''}) : quantity = TextEditingController(text: quantity);
}

class RecipeScreen extends StatefulWidget {
  final int productId;

  const RecipeScreen({super.key, required this.productId});

  @override
  State<RecipeScreen> createState() => _RecipeScreenState();
}

class _RecipeScreenState extends State<RecipeScreen> {
  final _api = ApiClient();
  late Future<(Product, List<Ingredient>)> _future;

  final _yieldQuantity = TextEditingController(text: '1');
  final List<_RecipeRow> _rows = [];
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(Product, List<Ingredient>)> _load() async {
    final product = await _api.getProduct(widget.productId);
    final ingredients = await _api.getIngredients();
    final recipe = await _api.getRecipe(widget.productId);

    _yieldQuantity.text = _fmt(recipe.yieldQuantity);
    _rows.clear();
    for (final item in recipe.items) {
      _rows.add(_RecipeRow(ingredientId: item.ingredientId, quantity: _fmt(item.quantity)));
    }
    _rows.add(_RecipeRow());

    return (product, ingredients);
  }

  String _fmt(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    final items = _rows
        .where((r) => r.ingredientId != null && r.quantity.text.trim().isNotEmpty)
        .map((r) => {
              'ingredientId': r.ingredientId,
              'quantity': double.tryParse(r.quantity.text.replaceAll(',', '.')) ?? 0,
            })
        .toList();
    final body = {
      'yieldQuantity': double.tryParse(_yieldQuantity.text.replaceAll(',', '.')) ?? 1,
      'items': items,
    };
    try {
      await _api.saveRecipe(widget.productId, body);
      if (mounted) Navigator.of(context).pop();
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
      appBar: AppBar(title: const Text('Рецептура')),
      body: FutureBuilder<(Product, List<Ingredient>)>(
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
          final (product, ingredients) = snapshot.data!;
          return StatefulBuilder(
            builder: (context, setLocalState) {
              return ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  Row(
                    children: [
                      ProductAvatar(name: product.name, imageUrl: product.imageUrl, radius: 26),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(product.name, style: Theme.of(context).textTheme.titleLarge),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    'Сколько сырья уходит на партию. При завершении цеховой задачи сырьё '
                    'списывается автоматически пропорционально количеству.',
                    style: TextStyle(color: Colors.grey, fontSize: 13),
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: _yieldQuantity,
                    decoration: const InputDecoration(labelText: 'Партия рецепта (сколько единиц товара получается)'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  ),
                  const SizedBox(height: 16),
                  Text('Ингредиенты', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  for (int i = 0; i < _rows.length; i++)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Row(
                        children: [
                          Expanded(
                            flex: 3,
                            child: DropdownButtonFormField<int>(
                              initialValue: _rows[i].ingredientId,
                              isExpanded: true,
                              decoration: const InputDecoration(labelText: 'Ингредиент'),
                              items: [
                                const DropdownMenuItem<int>(value: null, child: Text('— не используется —')),
                                for (final ing in ingredients)
                                  DropdownMenuItem<int>(value: ing.id, child: Text('${ing.name} (${ing.unit})')),
                              ],
                              onChanged: (value) => setLocalState(() => _rows[i].ingredientId = value),
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
                            onPressed: () => setLocalState(() => _rows.removeAt(i)),
                          ),
                        ],
                      ),
                    ),
                  OutlinedButton.icon(
                    onPressed: () => setLocalState(() => _rows.add(_RecipeRow())),
                    icon: const Icon(Icons.add),
                    label: const Text('Добавить ингредиент'),
                  ),
                  const SizedBox(height: 20),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: const TextStyle(color: Colors.red)),
                    ),
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    child: _saving
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Сохранить рецептуру'),
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
