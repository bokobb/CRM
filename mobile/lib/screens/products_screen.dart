import 'package:flutter/material.dart';

import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/searchable_app_bar.dart';

class ProductsScreen extends StatefulWidget {
  const ProductsScreen({super.key});

  @override
  State<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends State<ProductsScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _future = _api.getProducts();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getProducts();
    });
    await _future;
  }

  List<Product> _filter(List<Product> products) {
    if (_query.isEmpty) return products;
    final q = _query.toLowerCase();
    return products.where((p) => p.name.toLowerCase().contains(q)).toList();
  }

  Future<void> _delete(Product p) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить товар?'),
        content: Text(p.name),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed == true) {
      await _api.deleteProduct(p.id);
      _refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final isAdmin = ApiClient.currentSession?.role == 'ADMIN';
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Товары',
        hintText: 'Название товара...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/products'),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/products/new');
          _refresh();
        },
        child: const Icon(Icons.add),
      ),
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
            final products = _filter(snapshot.data!);
            if (products.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(_query.isEmpty ? 'Товаров пока нет' : 'Ничего не найдено',
                        style: const TextStyle(color: Colors.grey)),
                  ),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: products.length,
              itemBuilder: (context, i) {
                final p = products[i];
                return AppListTile(
                  leading: ProductAvatar(name: p.name, imageUrl: p.imageUrl),
                  title: p.name,
                  subtitle: [
                    if (p.category != null) p.category!,
                    formatMoney(p.price),
                    if (p.unit != null && p.unit!.isNotEmpty) p.unit!,
                  ].join(' · '),
                  trailing: PopupMenuButton<String>(
                    onSelected: (value) async {
                      if (value == 'recipe') {
                        await Navigator.of(context).pushNamed('/products/recipe', arguments: p.id);
                        _refresh();
                      } else if (value == 'edit') {
                        await Navigator.of(context).pushNamed('/products/edit', arguments: p.id);
                        _refresh();
                      } else if (value == 'delete') {
                        _delete(p);
                      }
                    },
                    itemBuilder: (context) => [
                      const PopupMenuItem(value: 'recipe', child: Text('Рецептура')),
                      const PopupMenuItem(value: 'edit', child: Text('Изменить')),
                      if (isAdmin) const PopupMenuItem(value: 'delete', child: Text('Удалить')),
                    ],
                  ),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/products/edit', arguments: p.id);
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
