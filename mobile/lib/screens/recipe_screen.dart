
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

  bool get isBlank =>
      ingredientId == null && ingredientSearch.text.trim().isEmpty && quantity.text.trim().isEmpty;

  void dispose() {
    quantity.dispose();
    ingredientSearch.dispose();
    focusNode.dispose();
  }
}

/// Рецептура товара (макет «Рецептура — экран»): сколько штук с одного замеса, закладка сырья,
/// вес на 1 шт и состав. Импорт из 1С и копирование из другого товара — иконки в шапке.
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
  bool _dirty = false;
  bool _tried = false;
  String? _error;

  static const _numStyle = TextStyle(fontFeatures: [FontFeature.tabularFigures()]);

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
    _applyRecipe(recipe);
    _dirty = false;
    return (product, ingredients, products);
  }

  void _applyRecipe(Recipe recipe) {
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
    _tried = false;
  }

  String _fmt(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  double _num(String s) => double.tryParse(s.trim().replaceAll(' ', '').replaceAll(',', '.')) ?? 0;

  String _fmtFixed(double v, int digits) => v.toStringAsFixed(digits).replaceAll('.', ',');

  Ingredient? _findIngredient(List<Ingredient> ingredients, int? id) {
    if (id == null) return null;
    for (final ing in ingredients) {
      if (ing.id == id) return ing;
    }
    return null;
  }

  /// Масса позиции в кг — для «Закладки» и веса на 1 шт; штучное/литровое сырьё не считается.
  double _weightKg(Ingredient? ing, double q) {
    final unit = ing?.unit.trim().toLowerCase() ?? '';
    if (unit.startsWith('кг')) return q;
    if (unit == 'г' || unit == 'гр' || unit.startsWith('г.')) return q / 1000;
    return 0;
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
        _applyRecipe(recipe);
        _dirty = false;
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

  Future<void> _copyFromProduct(List<Product> products) async {
    final source = await pickFromList<Product>(
      context: context,
      title: 'Скопировать рецепт из товара',
      items: products.where((p) => p.id != widget.productId).toList(),
      label: (p) => p.name,
      leading: (p) => ProductAvatar(name: p.name, imageUrl: p.imageUrl),
    );
    if (source == null || !mounted) return;
    try {
      final recipe = await _api.getRecipe(source.id);
      setState(() {
        _applyRecipe(recipe);
        _dirty = true;
      });
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Состав скопирован из «${source.name}» — не забудьте сохранить')));
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }

  bool _rowInvalidName(_RecipeRow r) => !r.isBlank && r.ingredientId == null;
  bool _rowInvalidQty(_RecipeRow r) => !r.isBlank && _num(r.quantity.text) <= 0;

  void _addRow() {
    final row = _RecipeRow();
    setState(() {
      _rows.add(row);
      _dirty = true;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) row.focusNode.requestFocus();
    });
  }

  void _removeRow(_RecipeRow row) {
    setState(() {
      _rows.remove(row);
      _dirty = true;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => row.dispose());
  }

  Future<void> _save() async {
    final badRow = _rows.any((r) => _rowInvalidName(r) || _rowInvalidQty(r));
    final badYield = _num(_yieldQuantity.text) <= 0;
    if (badRow || badYield) {
      setState(() {
        _tried = true;
        _error = null;
      });
      return;
    }
    setState(() {
      _saving = true;
      _tried = false;
      _error = null;
    });
    final items = _rows
        .where((r) => !r.isBlank)
        .map((r) => {
              'ingredientId': r.ingredientId,
              'quantity': _num(r.quantity.text),
            })
        .toList();
    final body = {
      'yieldQuantity': _num(_yieldQuantity.text),
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
    final pal = context.pal;
    final isAdmin = ApiClient.currentSession?.role == 'ADMIN';
    return FutureBuilder<(Product, List<Ingredient>, List<Product>)>(
      future: _future,
      builder: (context, snapshot) {
        final loaded = snapshot.connectionState == ConnectionState.done && snapshot.hasData;
        return Scaffold(
          backgroundColor: pal.bg,
          appBar: AppBar(
            title: const Text('Рецептура'),
            actions: [
              if (loaded) _saveStatus(pal),
              if (loaded && isAdmin)
                IconButton(
                  tooltip: 'Импортировать из 1С',
                  onPressed: _importingFromOneC ? null : _importFromOneC,
                  icon: _importingFromOneC
                      ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.cloud_download_outlined),
                ),
              if (loaded)
                IconButton(
                  tooltip: 'Скопировать рецептуру из другого товара',
                  onPressed: () => _copyFromProduct(snapshot.data!.$3),
                  icon: const Icon(Icons.content_copy_outlined),
                ),
              const SizedBox(width: 4),
            ],
          ),
          body: Builder(builder: (context) {
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
            final (product, ingredients, _) = snapshot.data!;
            return Column(
              children: [
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                    children: [
                      _productCard(context, product, ingredients),
                      const SizedBox(height: 12),
                      _compositionCard(context, ingredients),
                    ],
                  ),
                ),
                _footer(context),
              ],
            );
          }),
        );
      },
    );
  }

  Widget _saveStatus(AppPalette pal) {
    return Padding(
      padding: const EdgeInsets.only(right: 4),
      child: Center(
        child: _dirty
            ? Row(mainAxisSize: MainAxisSize.min, children: [
                Container(
                    width: 8, height: 8, decoration: BoxDecoration(color: pal.accent, shape: BoxShape.circle)),
                const SizedBox(width: 6),
                const Text('Не сохранено', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500)),
              ])
            : Text('Сохранено', style: TextStyle(fontSize: 13, color: pal.muted)),
      ),
    );
  }

  BoxDecoration _cardDecoration(AppPalette pal) => BoxDecoration(
        color: pal.card,
        borderRadius: BorderRadius.circular(16),
        border: pal.bordered ? Border.all(color: pal.line) : null,
      );

  Widget _productCard(BuildContext context, Product product, List<Ingredient> ingredients) {
    final pal = context.pal;
    var totalKg = 0.0;
    for (final r in _rows) {
      if (r.ingredientId == null) continue;
      totalKg += _weightKg(_findIngredient(ingredients, r.ingredientId), _num(r.quantity.text));
    }
    final pieces = _num(_yieldQuantity.text);
    final canDivide = pieces > 0 && totalKg > 0;
    final yieldUnit = (product.unit == null || product.unit!.trim().isEmpty) ? 'шт' : product.unit!.trim();
    final yieldBad = _tried && pieces <= 0;

    Widget stat(String label, String value, String unit) => Expanded(
          child: Container(
            padding: const EdgeInsets.only(left: 10),
            decoration: BoxDecoration(border: Border(left: BorderSide(color: pal.line))),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: TextStyle(fontSize: 12, color: pal.muted)),
                const SizedBox(height: 4),
                SizedBox(
                  height: 44,
                  child: Row(children: [
                    Flexible(
                      child: Text(value,
                          overflow: TextOverflow.ellipsis,
                          style: _numStyle.copyWith(fontSize: 15, fontWeight: FontWeight.w600)),
                    ),
                    const SizedBox(width: 4),
                    Text(unit, style: TextStyle(fontSize: 13, color: pal.muted)),
                  ]),
                ),
              ],
            ),
          ),
        );

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: _cardDecoration(pal),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ProductAvatar(name: product.name, imageUrl: product.imageUrl, radius: 28),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(product.name, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                    if ((product.group ?? product.category) != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text('Группа: ${product.group ?? product.category}',
                            style: TextStyle(fontSize: 13, color: pal.muted)),
                      ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                flex: 13,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Штук с 1 замеса', style: TextStyle(fontSize: 12, color: pal.muted)),
                    const SizedBox(height: 4),
                    _boxedField(
                      pal: pal,
                      invalid: yieldBad,
                      child: TextField(
                        controller: _yieldQuantity,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        style: _numStyle.copyWith(fontSize: 18, fontWeight: FontWeight.w600),
                        decoration: _bareDecoration,
                        onChanged: (_) => setState(() => _dirty = true),
                      ),
                      suffix: yieldUnit,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              Expanded(flex: 20, child: Row(children: [
                stat('Закладка', _fmtFixed(totalKg, 3), 'кг'),
                stat('На 1 $yieldUnit', canDivide ? _fmtFixed(totalKg / pieces * 1000, 1) : '—',
                    canDivide ? 'г' : ''),
              ])),
            ],
          ),
        ],
      ),
    );
  }

  static const _bareDecoration = InputDecoration(
    isDense: true,
    filled: false,
    border: InputBorder.none,
    enabledBorder: InputBorder.none,
    focusedBorder: InputBorder.none,
    contentPadding: EdgeInsets.zero,
  );

  /// Поле в рамке-подложке, как в макете: заливка фоном экрана, красная рамка при ошибке.
  Widget _boxedField({
    required AppPalette pal,
    required bool invalid,
    required Widget child,
    String? suffix,
    double? width,
  }) {
    return Container(
      width: width,
      height: 44,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: BoxDecoration(
        color: pal.bg,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: invalid ? pal.danger : Colors.transparent, width: 1.5),
      ),
      child: Row(children: [
        Expanded(child: child),
        if (suffix != null && suffix.isNotEmpty) ...[
          const SizedBox(width: 4),
          Text(suffix, style: TextStyle(fontSize: 13, color: pal.muted)),
        ],
      ]),
    );
  }

  String _plural(int n) {
    final a = n % 10, b = n % 100;
    if (a == 1 && b != 11) return 'позиция';
    if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return 'позиции';
    return 'позиций';
  }

  Widget _compositionCard(BuildContext context, List<Ingredient> ingredients) {
    final pal = context.pal;
    final count = _rows.length;
    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: _cardDecoration(pal),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Row(children: [
              const Expanded(
                  child: Text('Состав', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600))),
              Text('$count ${_plural(count)} · на 1 замес', style: TextStyle(fontSize: 13, color: pal.muted)),
            ]),
          ),
          for (final row in _rows) _rowTile(context, row, ingredients),
          if (_rows.isEmpty)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
              decoration: BoxDecoration(border: Border(top: BorderSide(color: pal.line))),
              child: Text('В составе пока нет позиций.', style: TextStyle(fontSize: 14, color: pal.muted)),
            ),
          Container(
            decoration: BoxDecoration(border: Border(top: BorderSide(color: pal.line))),
            child: TextButton.icon(
              style: TextButton.styleFrom(
                minimumSize: const Size.fromHeight(52),
                foregroundColor: pal.accent,
                shape: const RoundedRectangleBorder(),
                textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
              ),
              onPressed: _addRow,
              icon: const Icon(Icons.add, size: 20),
              label: const Text('Добавить позицию'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _rowTile(BuildContext context, _RecipeRow row, List<Ingredient> ingredients) {
    final pal = context.pal;
    final ing = _findIngredient(ingredients, row.ingredientId);
    final nameBad = _tried && _rowInvalidName(row);
    final qtyBad = _tried && _rowInvalidQty(row);
    final label = ing?.name ?? 'новая позиция';
    return Container(
      key: ObjectKey(row),
      height: 52,
      padding: const EdgeInsets.only(left: 8),
      decoration: BoxDecoration(border: Border(top: BorderSide(color: pal.line))),
      child: Row(
        children: [
          Expanded(
            child: RawAutocomplete<Ingredient>(
              textEditingController: row.ingredientSearch,
              focusNode: row.focusNode,
              optionsBuilder: (value) {
                final q = value.text.trim().toLowerCase().replaceAll('ё', 'е');
                if (q.isEmpty || (ing != null && ing.name == value.text)) {
                  return const Iterable<Ingredient>.empty();
                }
                final found = ingredients
                    .where((i) => i.name.toLowerCase().replaceAll('ё', 'е').contains(q))
                    .toList()
                  ..sort((a, b) {
                    final ra = a.name.toLowerCase().replaceAll('ё', 'е').startsWith(q) ? 0 : 1;
                    final rb = b.name.toLowerCase().replaceAll('ё', 'е').startsWith(q) ? 0 : 1;
                    return ra - rb;
                  });
                return found.take(30);
              },
              displayStringForOption: (i) => i.name,
              onSelected: (i) => setState(() {
                row.ingredientId = i.id;
                row.ingredientSearch.text = i.name;
                _dirty = true;
              }),
              fieldViewBuilder: (context, controller, focusNode, onFieldSubmitted) {
                return Container(
                  height: 44,
                  padding: const EdgeInsets.symmetric(horizontal: 6),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: nameBad ? pal.danger : Colors.transparent, width: 1.5),
                  ),
                  alignment: Alignment.centerLeft,
                  child: TextField(
                    controller: controller,
                    focusNode: focusNode,
                    style: const TextStyle(fontSize: 15),
                    decoration: _bareDecoration.copyWith(
                      hintText: 'Введите сырьё',
                      hintStyle: TextStyle(color: pal.muted),
                    ),
                    onTap: () {
                      if (row.ingredientId != null) {
                        controller.selection =
                            TextSelection(baseOffset: 0, extentOffset: controller.text.length);
                      }
                    },
                    onChanged: (text) {
                      final current = _findIngredient(ingredients, row.ingredientId);
                      setState(() {
                        if (current != null && current.name != text) row.ingredientId = null;
                        _dirty = true;
                      });
                    },
                  ),
                );
              },
              optionsViewBuilder: (context, onSelected, options) {
                final list = options.toList();
                return Align(
                  alignment: Alignment.topLeft,
                  child: Material(
                    elevation: 4,
                    color: pal.card,
                    borderRadius: BorderRadius.circular(12),
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 240, maxWidth: 300, minWidth: 220),
                      child: ListView.builder(
                        padding: const EdgeInsets.all(4),
                        shrinkWrap: true,
                        itemCount: list.length,
                        itemBuilder: (context, index) {
                          final i = list[index];
                          return InkWell(
                            borderRadius: BorderRadius.circular(10),
                            onTap: () => onSelected(i),
                            child: Container(
                              height: 44,
                              padding: const EdgeInsets.symmetric(horizontal: 8),
                              child: Row(children: [
                                Expanded(
                                  child: Text(i.name,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(fontSize: 15)),
                                ),
                                Text(i.unit, style: TextStyle(fontSize: 13, color: pal.muted)),
                              ]),
                            ),
                          );
                        },
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          const SizedBox(width: 6),
          _boxedField(
            pal: pal,
            invalid: qtyBad,
            width: 104,
            suffix: ing?.unit ?? '',
            child: Semantics(
              label: 'Количество: $label',
              child: TextField(
                controller: row.quantity,
                textAlign: TextAlign.right,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                style: _numStyle.copyWith(fontSize: 14, fontWeight: FontWeight.w500),
                decoration: _bareDecoration.copyWith(
                  hintText: '0,000',
                  hintStyle: TextStyle(color: pal.muted),
                ),
                onChanged: (_) => setState(() => _dirty = true),
              ),
            ),
          ),
          IconButton(
            tooltip: 'Удалить позицию: $label',
            color: pal.muted,
            icon: const Icon(Icons.delete_outline, size: 20),
            onPressed: () => _removeRow(row),
          ),
        ],
      ),
    );
  }

  Widget _footer(BuildContext context) {
    final pal = context.pal;
    final showError = _tried &&
        (_rows.any((r) => _rowInvalidName(r) || _rowInvalidQty(r)) || _num(_yieldQuantity.text) <= 0);
    return Container(
      padding: EdgeInsets.fromLTRB(16, 12, 16, 16 + MediaQuery.of(context).padding.bottom),
      decoration: BoxDecoration(
        color: pal.card,
        border: Border(top: BorderSide(color: pal.line)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (showError)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text('Заполните выделенные поля: сырьё из справочника и количество.',
                  style: TextStyle(fontSize: 13, color: pal.danger)),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(_error!, style: TextStyle(fontSize: 13, color: pal.danger)),
            ),
          FilledButton(
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
              backgroundColor: pal.accent,
              foregroundColor: pal.onAccent,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
            ),
            onPressed: _saving ? null : _save,
            child: _saving
                ? SizedBox(
                    height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: pal.onAccent))
                : const Text('Сохранить'),
          ),
        ],
      ),
    );
  }
}
