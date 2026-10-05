import 'package:flutter/material.dart';

import '../models/client_price.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../theme/app_theme.dart';

class ClientPricesScreen extends StatefulWidget {
  final int clientId;

  const ClientPricesScreen({super.key, required this.clientId});

  @override
  State<ClientPricesScreen> createState() => _ClientPricesScreenState();
}

class _ClientPricesScreenState extends State<ClientPricesScreen> {
  final _api = ApiClient();
  late Future<(List<Product>, List<ClientPrice>)> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(List<Product>, List<ClientPrice>)> _load() async {
    final products = await _api.getProducts();
    final prices = await _api.getClientPrices(widget.clientId);
    return (products, prices);
  }

  void _refresh() {
    setState(() {
      _future = _load();
    });
  }

  Future<void> _editPrice(Product product, ClientPrice? current) async {
    final controller = TextEditingController(
      text: current != null ? current.price.toStringAsFixed(2) : product.price.toStringAsFixed(2),
    );
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(product.name),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: 'Цена для клиента', helperText: 'Базовая: ${formatMoney(product.price)}'),
        ),
        actions: [
          if (current != null)
            TextButton(
              onPressed: () => Navigator.pop(context, '_clear_'),
              child: Text('Сбросить до базовой', style: TextStyle(color: context.pal.danger)),
            ),
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Сохранить')),
        ],
      ),
    );
    if (result == null) return;
    try {
      if (result == '_clear_') {
        await _api.deleteClientPrice(widget.clientId, product.id);
      } else {
        final price = double.tryParse(result.replaceAll(',', '.'));
        if (price == null) return;
        await _api.setClientPrice(widget.clientId, product.id, price);
      }
      _refresh();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Индивидуальные цены')),
      body: RefreshIndicator(
        onRefresh: () async {
          _refresh();
          await _future;
        },
        child: FutureBuilder<(List<Product>, List<ClientPrice>)>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final (products, prices) = snapshot.data!;
            final byProduct = {for (final p in prices) p.productId: p};
            return ListView.separated(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: products.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final product = products[i];
                final override = byProduct[product.id];
                return ListTile(
                  title: Text(product.name),
                  subtitle: Text('Базовая цена: ${formatMoney(product.price)}'),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        override != null ? formatMoney(override.price) : '—',
                        style: TextStyle(
                          fontWeight: override != null ? FontWeight.bold : FontWeight.normal,
                          color: override != null ? Theme.of(context).colorScheme.primary : context.pal.muted,
                        ),
                      ),
                      const SizedBox(width: 8),
                      const Icon(Icons.chevron_right, size: 18),
                    ],
                  ),
                  onTap: () => _editPrice(product, override),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
