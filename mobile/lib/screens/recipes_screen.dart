import 'package:flutter/material.dart';

import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/searchable_app_bar.dart';
import '../theme/app_theme.dart';

const _noGroup = 'Без группы';

/// Товары, сгруппированные по группе (Product.group) — тап открывает рецепт товара. Группировка
/// зеркалит ProductService.groupByGroup на бэкенде: алфавит, "Без группы" всегда последней.
class RecipesScreen extends StatefulWidget {
  const RecipesScreen({super.key});

  @override
  State<RecipesScreen> createState() => _RecipesScreenState();
}

class _RecipesScreenState extends State<RecipesScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _future = _api.getProducts(includeSemiFinished: true);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getProducts(includeSemiFinished: true);
    });
    await _future;
  }

  Map<String, List<Product>> _groupAndFilter(List<Product> products) {
    final q = _query.toLowerCase();
    final filtered = q.isEmpty ? products : products.where((p) => p.name.toLowerCase().contains(q)).toList();
    final byGroup = <String, List<Product>>{};
    for (final p in filtered) {
      final group = (p.group == null || p.group!.trim().isEmpty) ? _noGroup : p.group!;
      byGroup.putIfAbsent(group, () => []).add(p);
    }
    final keys = byGroup.keys.where((k) => k != _noGroup).toList()..sort();
    if (byGroup.containsKey(_noGroup)) keys.add(_noGroup);
    return {for (final k in keys) k: byGroup[k]!};
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Рецепты',
        hintText: 'Название товара...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/recipes'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/recipes'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Product>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final groups = _groupAndFilter(snapshot.data!);
            if (groups.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(_query.isEmpty ? 'Товаров пока нет' : 'Ничего не найдено',
                        style: TextStyle(color: context.pal.muted)),
                  ),
                ),
              ]);
            }
            return ListView(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              children: [
                for (final entry in groups.entries) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(4, 12, 4, 4),
                    child: Text(
                      '${entry.key} · ${entry.value.length}',
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                    ),
                  ),
                  for (final p in entry.value)
                    AppListTile(
                      leading: ProductAvatar(name: p.name, imageUrl: p.imageUrl),
                      title: p.name,
                      subtitle: formatMoney(p.price),
                      onTap: () async {
                        await Navigator.of(context).pushNamed('/products/recipe', arguments: p.id);
                        _refresh();
                      },
                    ),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}
