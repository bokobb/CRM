import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/pick_from_list.dart';
import '../widgets/product_avatar.dart';
import '../theme/app_theme.dart';

class _RecipeRow {
  int? ingredientId;
  final TextEditingController quantity;
  final TextEditingController ingredientSearch;
  final FocusNode focusNode = FocusNode();

  _RecipeRow({this.ingredientId, String quantity = '', String ingredientName = ''})
      : quantity = TextEditingController(text: quantity),
        ingredientSearch = TextEditingController(text: ingredientName);

  void dispose() {
    quantity.dispose();
    ingredientSearch.dispose();
    focusNode.dispose();
  }
}

class RecipeScreen extends StatefulWidget {
  final int productId;

  const RecipeScreen({super.key, required this.productId});

  @override
  State<RecipeScreen> createState() => _RecipeScreenState();
}

class _RecipeScreenState extends State<RecipeScreen> {
  final _api = ApiClient();
  late Future<(Product, List<Ingredient>, List<Product>)> _future;

  final _yieldQuantity = TextEditingController(text: '1');
  final List<_RecipeRow> _rows = [];
  bool _saving = false;
  bool _importingFromOneC = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  @override
  void dispose() {
    _yieldQuantity.dispose();
    for (final row in _rows) {
      row.dispose();
    }
    super.dispose();
  }

  Future<(Product, List<Ingredient>, List<Product>)> _load() async {
    final product = await _api.getProduct(widget.productId);
    final ingredients = await _api.getIngredients();
    final products = await _api.getProducts(includeSemiFinished: true);
    final recipe = await _api.getRecipe(widget.productId);

    _yieldQuantity.text = _fmt(recipe.yieldQuantity);
    _rows.clear();
    for (final item in recipe.items) {
      _rows.add(_RecipeRow(
        ingredientId: item.ingredientId,
        quantity: _fmt(item.quantity),
        ingredientName: item.ingredientName,
      ));
    }
    _rows.add(_RecipeRow());

    return (product, ingredients, products);
  }

  String _fmt(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  Ingredient? _findIngredient(List<Ingredient> ingredients, int? id) {
    if (id == null) return null;
    for (final ing in ingredients) {
      if (ing.id == id) return ing;
    }
    return null;
  }

  /// Заполняет рецепт (состав сырья) и GUID спецификации ИМЕННО ЭТОГО товара из 1С — остальные
  /// товары не трогает (см. OneCRecipeImportService.importOne на бэкенде).
  Future<void> _importFromOneC() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Импорт из 1С'),
        content: const Text('Заполнить рецепт этого товара данными из 1С? '
            'Текущий состав (сырьё/количества) будет заменён.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Продолжить')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _importingFromOneC = true);
    try {
      final recipe = await _api.importRecipeForProduct(widget.productId);
      setState(() {
        _yieldQuantity.text = _fmt(recipe.yieldQuantity);
        for (final row in _rows) {
          row.dispose();
        }
        _rows.clear();
        for (final item in recipe.items) {
          _rows.add(_RecipeRow(
            ingredientId: item.ingredientId,
            quantity: _fmt(item.quantity),
            ingredientName: item.ingredientName,
          ));
        }
        _rows.add(_RecipeRow());
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Рецепт обновлён из 1С')));
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    } finally {
      if (mounted) setState(() => _importingFromOneC = false);
    }
  }

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
      body: FutureBuilder<(Product, List<Ingredient>, List<Product>)>(
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
          final (product, ingredients, products) = snapshot.data!;
          return StatefulBuilder(
            builder: (context, setLocalState) {
              Future<void> copyFromProduct() async {
                final source = await pickFromList<Product>(
                  context: context,
                  title: 'Скопировать рецепт из товара',
                  items: products.where((p) => p.id != widget.productId).toList(),
                  label: (p) => p.name,
                  leading: (p) => ProductAvatar(name: p.name, imageUrl: p.imageUrl),
                );
                if (source == null || !context.mounted) return;
                try {
                  final recipe = await _api.getRecipe(source.id);
                  setLocalState(() {
                    _yieldQuantity.text = _fmt(recipe.yieldQuantity);
                    for (final row in _rows) {
                      row.dispose();
                    }
                    _rows.clear();
                    for (final item in recipe.items) {
                      _rows.add(_RecipeRow(
                        ingredientId: item.ingredientId,
                        quantity: _fmt(item.quantity),
                        ingredientName: item.ingredientName,
                      ));
                    }
                    _rows.add(_RecipeRow());
                  });
                } catch (e) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
                  }
                }
              }

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
                  Text(
                    'Сколько сырья уходит на партию. При завершении цеховой задачи сырьё '
                    'списывается автоматически пропорционально количеству.',
                    style: TextStyle(color: context.pal.muted, fontSize: 13),
                  ),
                  const SizedBox(height: 12),
                  if (ApiClient.currentSession?.role == 'ADMIN')
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: OutlinedButton.icon(
                        onPressed: _importingFromOneC ? null : _importFromOneC,
                        icon: _importingFromOneC
                            ? const SizedBox(
                                width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.sync, size: 18),
                        label: const Text('Импортировать рецепт из 1С'),
                      ),
                    ),
                  OutlinedButton.icon(
                    onPressed: copyFromProduct,
                    icon: const Icon(Icons.content_copy, size: 18),
                    label: const Text('Скопировать рецепт из другого товара'),
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
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            flex: 3,
                            child: RawAutocomplete<Ingredient>(
                              textEditingController: _rows[i].ingredientSearch,
                              focusNode: _rows[i].focusNode,
                              optionsBuilder: (value) {
                                final q = value.text.trim().toLowerCase();
                                if (q.isEmpty) return const Iterable<Ingredient>.empty();
                                return ingredients.where((ing) => ing.name.toLowerCase().contains(q)).take(30);
                              },
                              displayStringForOption: (ing) => ing.name,
                              onSelected: (ing) => setLocalState(() {
                                _rows[i].ingredientId = ing.id;
                                _rows[i].ingredientSearch.text = ing.name;
                              }),
                              fieldViewBuilder: (context, controller, focusNode, onFieldSubmitted) {
                                return TextField(
                                  controller: controller,
                                  focusNode: focusNode,
                                  decoration: const InputDecoration(
                                    labelText: 'Ингредиент',
                                    isDense: true,
                                    prefixIcon: Icon(Icons.search, size: 20),
                                  ),
                                  onChanged: (text) {
                                    final current = _findIngredient(ingredients, _rows[i].ingredientId);
                                    if (current != null && current.name != text) {
                                      setLocalState(() => _rows[i].ingredientId = null);
                                    }
                                  },
                                );
                              },
                              optionsViewBuilder: (context, onSelected, options) {
                                final list = options.toList();
                                return Align(
                                  alignment: Alignment.topLeft,
                                  child: Material(
                                    elevation: 4,
                                    borderRadius: BorderRadius.circular(10),
                                    child: ConstrainedBox(
                                      constraints: const BoxConstraints(maxHeight: 220, minWidth: 220),
                                      child: ListView.builder(
                                        padding: EdgeInsets.zero,
                                        shrinkWrap: true,
                                        itemCount: list.length,
                                        itemBuilder: (context, index) {
                                          final ing = list[index];
                                          return ListTile(
                                            dense: true,
                                            title: Text(ing.name),
                                            trailing: Text(ing.unit, style: TextStyle(color: context.pal.muted)),
                                            onTap: () => onSelected(ing),
                                          );
                                        },
                                      ),
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              controller: _rows[i].quantity,
                              decoration: InputDecoration(
                                labelText: 'Кол-во',
                                isDense: true,
                                suffixText: _findIngredient(ingredients, _rows[i].ingredientId)?.unit,
                              ),
                              keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.close, size: 18),
                            onPressed: () => setLocalState(() {
                              _rows[i].dispose();
                              _rows.removeAt(i);
                            }),
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
                      child: Text(_error!, style: TextStyle(color: context.pal.danger)),
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
