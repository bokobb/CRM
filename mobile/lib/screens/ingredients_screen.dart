import 'package:flutter/material.dart';

import '../models/ingredient.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class IngredientsScreen extends StatefulWidget {
  const IngredientsScreen({super.key});

  @override
  State<IngredientsScreen> createState() => _IngredientsScreenState();
}

class _IngredientsScreenState extends State<IngredientsScreen> {
  final _api = ApiClient();
  late Future<List<Ingredient>> _future;
  bool _lowStockOnly = false;

  @override
  void initState() {
    super.initState();
    _future = _api.getIngredients();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getIngredients());
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
      appBar: AppBar(
        title: const Text('Склад'),
        actions: [
          IconButton(
            icon: Icon(_lowStockOnly ? Icons.filter_alt : Icons.filter_alt_outlined),
            tooltip: 'Только дефицит',
            onPressed: () => setState(() => _lowStockOnly = !_lowStockOnly),
          ),
        ],
      ),
      drawer: const AppDrawer(currentRoute: '/ingredients'),
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
            if (ingredients.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(
                      _lowStockOnly ? 'Дефицита нет — всё в норме' : 'Ингредиентов пока нет',
                      style: const TextStyle(color: Colors.grey),
                    ),
                  ),
                ),
              ]);
            }
            return ListView.separated(
              itemCount: ingredients.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                final i = ingredients[index];
                return ListTile(
                  leading: i.lowStock
                      ? const Icon(Icons.warning_amber_rounded, color: Color(0xFFB8433F))
                      : const Icon(Icons.inventory_2_outlined, color: Colors.grey),
                  title: Text(i.name),
                  subtitle: Text('минимум ${formatQty(i.minThreshold)} ${i.unit}'),
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
