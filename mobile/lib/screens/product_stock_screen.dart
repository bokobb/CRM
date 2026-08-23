import 'package:flutter/material.dart';

import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// Остатки готовой продукции на складе ГП (не сырьё — то теперь отдельным разделом "Сырьё").
class ProductStockScreen extends StatefulWidget {
  const ProductStockScreen({super.key});

  @override
  State<ProductStockScreen> createState() => _ProductStockScreenState();
}

class _ProductStockScreenState extends State<ProductStockScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;

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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Остатки')),
      drawer: const AppDrawer(currentRoute: '/warehouse'),
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
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: products.length,
              itemBuilder: (context, i) {
                final p = products[i];
                return AppListTile(
                  leadingIcon: Icons.cake_outlined,
                  title: p.name,
                  subtitle: p.category,
                  trailing: Text(
                    '${formatQty(p.quantityInStock)} ${p.unit ?? "шт"}',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
