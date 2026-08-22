import 'package:flutter/material.dart';

import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class ProductsScreen extends StatefulWidget {
  const ProductsScreen({super.key});

  @override
  State<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends State<ProductsScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getProducts();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getProducts());
    await _future;
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
    return Scaffold(
      appBar: AppBar(title: const Text('Товары и рецепты')),
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
            final products = snapshot.data!;
            if (products.isEmpty) {
              return ListView(children: const [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Товаров пока нет', style: TextStyle(color: Colors.grey))),
                ),
              ]);
            }
            return ListView.separated(
              itemCount: products.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final p = products[i];
                return ListTile(
                  title: Text(p.name),
                  subtitle: Text([if (p.category != null) p.category!, formatMoney(p.price)].join(' · ')),
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
                    itemBuilder: (context) => const [
                      PopupMenuItem(value: 'recipe', child: Text('Рецептура')),
                      PopupMenuItem(value: 'edit', child: Text('Изменить')),
                      PopupMenuItem(value: 'delete', child: Text('Удалить')),
                    ],
                  ),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/products/recipe', arguments: p.id);
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
