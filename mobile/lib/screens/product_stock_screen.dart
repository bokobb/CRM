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

/// Остатки готовой продукции на складе ГП (не сырьё — то теперь отдельным разделом "Сырьё").
class ProductStockScreen extends StatefulWidget {
  const ProductStockScreen({super.key});

  @override
  State<ProductStockScreen> createState() => _ProductStockScreenState();
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

class _ProductStockScreenState extends State<ProductStockScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;
  String _query = '';
  bool _syncing = false;
  _SortMode _sortMode = _SortMode.qtyDesc;

  List<Product> _sort(List<Product> products) {
    final sorted = [...products];
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
    _future = _api.getProducts();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getProducts();
    });
    await _future;
  }

  /// Обычно синхронизация идёт по расписанию на бэкенде — эта кнопка (только у ADMIN) нужна
  /// для проверки без ожидания часа, см. OneCStockSyncService.
  Future<void> _syncOneC() async {
    setState(() => _syncing = true);
    try {
      final result = await _api.syncOneC();
      var message = 'Обновлено товаров: ${result.productsUpdated}, сырья: ${result.ingredientsUpdated}';
      if (result.unmatched.isNotEmpty) {
        message += '. Не сопоставлено: ${result.unmatched.length}';
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
      }
      await _refresh();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    } finally {
      if (mounted) setState(() => _syncing = false);
    }
  }

  List<Product> _filter(List<Product> products) {
    if (_query.isEmpty) return products;
    final q = _query.toLowerCase();
    return products.where((p) => p.name.toLowerCase().contains(q)).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Остатки',
        hintText: 'Название товара...',
        onQueryChanged: (v) => setState(() => _query = v),
        extraActions: [
          IconButton(
            icon: Icon(_sortMode.icon),
            tooltip: 'Сортировка: ${_sortMode.label}',
            onPressed: () => setState(() => _sortMode = _sortMode.next),
          ),
          if (ApiClient.currentSession?.role == 'ADMIN')
            IconButton(
              icon: _syncing
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.sync),
              tooltip: 'Синхронизировать с 1С',
              onPressed: _syncing ? null : _syncOneC,
            ),
        ],
      ),
      drawer: const AppDrawer(currentRoute: '/warehouse'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/warehouse'),
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
            final products = _sort(_filter(snapshot.data!));
            if (products.isEmpty) {
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
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: products.length,
              itemBuilder: (context, i) {
                final p = products[i];
                return AppListTile(
                  leading: ProductAvatar(name: p.name, imageUrl: p.imageUrl),
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
