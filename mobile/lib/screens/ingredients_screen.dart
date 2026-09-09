import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/searchable_app_bar.dart';

class IngredientsScreen extends StatefulWidget {
  const IngredientsScreen({super.key});

  @override
  State<IngredientsScreen> createState() => _IngredientsScreenState();
}

/// Один переключатель сортировки вместо отдельных полей/направлений — по умолчанию
/// количество по убыванию (самые большие остатки сверху), тап -> по возрастанию, тап -> по
/// названию, тап -> снова по умолчанию.
enum _SortMode {
  qtyDesc(Icons.arrow_downward, 'Количество ▼'),
  qtyAsc(Icons.arrow_upward, 'Количество ▲'),
  nameAsc(Icons.sort_by_alpha, 'Название ▲');

  final IconData icon;
  final String label;

  const _SortMode(this.icon, this.label);

  _SortMode get next => switch (this) {
        _SortMode.qtyDesc => _SortMode.qtyAsc,
        _SortMode.qtyAsc => _SortMode.nameAsc,
        _SortMode.nameAsc => _SortMode.qtyDesc,
      };
}

class _IngredientsScreenState extends State<IngredientsScreen> {
  final _api = ApiClient();
  late Future<List<Ingredient>> _future;
  bool _lowStockOnly = false;
  String _query = '';
  _SortMode _sortMode = _SortMode.qtyDesc;

  List<Ingredient> _sortIngredients(List<Ingredient> ingredients) {
    final sorted = [...ingredients];
    switch (_sortMode) {
      case _SortMode.qtyDesc:
        sorted.sort((a, b) => b.quantityInStock.compareTo(a.quantityInStock));
      case _SortMode.qtyAsc:
        sorted.sort((a, b) => a.quantityInStock.compareTo(b.quantityInStock));
      case _SortMode.nameAsc:
        sorted.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    }
    return sorted;
  }

  @override
  void initState() {
    super.initState();
    _future = _api.getIngredients();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getIngredients();
    });
    await _future;
  }

  Future<void> _delete(Ingredient i) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить ингредиент?'),
        content: Text(i.name),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed == true) {
      try {
        await _api.deleteIngredient(i.id);
        _refresh();
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Сырьё',
        hintText: 'Название сырья...',
        onQueryChanged: (v) => setState(() => _query = v),
        extraActions: [
          IconButton(
            icon: Icon(_lowStockOnly ? Icons.filter_alt : Icons.filter_alt_outlined),
            tooltip: 'Только дефицит',
            onPressed: () => setState(() => _lowStockOnly = !_lowStockOnly),
          ),
          IconButton(
            icon: Icon(_sortMode.icon),
            tooltip: 'Сортировка: ${_sortMode.label}',
            onPressed: () => setState(() => _sortMode = _sortMode.next),
          ),
        ],
      ),
      drawer: const AppDrawer(currentRoute: '/warehouse'),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/ingredients/new');
          _refresh();
        },
        child: const Icon(Icons.add),
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Ingredient>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            var ingredients = snapshot.data!;
            if (_lowStockOnly) {
              ingredients = ingredients.where((i) => i.lowStock).toList();
            }
            if (_query.isNotEmpty) {
              final q = _query.toLowerCase();
              ingredients = ingredients.where((i) => i.name.toLowerCase().contains(q)).toList();
            }
            ingredients = _sortIngredients(ingredients);
            if (ingredients.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(
                      _query.isNotEmpty
                          ? 'Ничего не найдено'
                          : (_lowStockOnly ? 'Дефицита нет — всё в норме' : 'Ингредиентов пока нет'),
                      style: const TextStyle(color: Colors.grey),
                    ),
                  ),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: ingredients.length,
              itemBuilder: (context, index) {
                final i = ingredients[index];
                return AppListTile(
                  leadingIcon: i.lowStock ? Icons.warning_amber_rounded : Icons.inventory_2_outlined,
                  leadingColor: i.lowStock ? const Color(0xFFB8433F) : Colors.grey,
                  title: i.name,
                  subtitle: 'минимум ${formatQty(i.minThreshold)} ${i.unit}',
                  trailing: Text(
                    '${formatQty(i.quantityInStock)} ${i.unit}',
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: i.lowStock ? const Color(0xFFB8433F) : null,
                    ),
                  ),
                  onLongPress: () => _delete(i),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/ingredients/edit', arguments: i.id);
                    _refresh();
                  },
                );
              },
            );
          },
        ),
      ),
    );
  }
}
