import 'package:flutter/material.dart';

import '../models/client.dart';
import '../models/order.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/status_badge.dart';

class ClientDetailScreen extends StatefulWidget {
  final int clientId;

  const ClientDetailScreen({super.key, required this.clientId});

  @override
  State<ClientDetailScreen> createState() => _ClientDetailScreenState();
}

class _ClientDetailScreenState extends State<ClientDetailScreen> {
  final _api = ApiClient();
  late Future<(Client, List<OrderSummary>, num)> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(Client, List<OrderSummary>, num)> _load() async {
    final client = await _api.getClient(widget.clientId);
    final orders = await _api.getOrders(clientId: widget.clientId);
    final monthlySales = await _api.getClientMonthlySales(widget.clientId);
    return (client, orders, monthlySales);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _load();
    });
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Клиент'),
        actions: [
          FutureBuilder<(Client, List<OrderSummary>, num)>(
            future: _future,
            builder: (context, snapshot) {
              if (!snapshot.hasData) return const SizedBox.shrink();
              return IconButton(
                icon: const Icon(Icons.edit_outlined),
                onPressed: () async {
                  await Navigator.of(context).pushNamed('/clients/edit', arguments: widget.clientId);
                  _refresh();
                },
              );
            },
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<(Client, List<OrderSummary>, num)>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final (client, orders, monthlySales) = snapshot.data!;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text(client.name, style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 4),
                Text(
                  [if (client.phone != null) client.phone!, if (client.email != null) client.email!].join(' · '),
                  style: const TextStyle(color: Colors.grey),
                ),
                if (client.address != null && client.address!.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(client.address!),
                ],
                if (client.notes != null && client.notes!.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(client.notes!, style: const TextStyle(fontStyle: FontStyle.italic)),
                ],
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: () => Navigator.of(context).pushNamed('/clients/prices', arguments: widget.clientId),
                  icon: const Icon(Icons.sell_outlined),
                  label: const Text('Индивидуальные цены'),
                ),
                const SizedBox(height: 20),
                Card(
                  color: const Color(0xFFB8324A),
                  child: Padding(
                    padding: const EdgeInsets.all(18),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(formatMoney(monthlySales.toDouble()),
                            style: const TextStyle(fontSize: 28, fontWeight: FontWeight.bold, color: Colors.white)),
                        const SizedBox(height: 2),
                        const Text('Покупки за этот месяц', style: TextStyle(color: Colors.white70, fontSize: 13)),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                Text('История заказов', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                if (orders.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Text('Заказов пока не было', style: TextStyle(color: Colors.grey)),
                  )
                else
                  Card(
                    child: Column(
                      children: [
                        for (final o in orders)
                          ListTile(
                            title: Text('Заказ #${o.id}'),
                            subtitle: Text('${formatDate(o.createdAt)} · ${formatMoney(o.totalPrice)}'),
                            trailing: StatusBadge(status: o.status, label: o.statusLabel),
                            onTap: () => Navigator.of(context).pushNamed('/orders/detail', arguments: o.id),
                          ),
                      ],
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}
