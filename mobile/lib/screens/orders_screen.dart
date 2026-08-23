import 'package:flutter/material.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/status_badge.dart';

class OrdersScreen extends StatefulWidget {
  const OrdersScreen({super.key});

  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends State<OrdersScreen> {
  final _api = ApiClient();
  late Future<List<OrderSummary>> _future;
  String? _status;

  @override
  void initState() {
    super.initState();
    // Склад ГП по умолчанию видит только готовые к отгрузке заказы, бухгалтер — уже отгруженные.
    // Фильтр остаётся переключаемым — это только удобный дефолт, не ограничение доступа.
    switch (ApiClient.currentSession?.role) {
      case 'WAREHOUSE_FG':
        _status = 'READY';
      case 'ACCOUNTANT':
        _status = 'DELIVERED';
    }
    _future = _api.getOrders(status: _status);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getOrders(status: _status);
    });
    await _future;
  }

  bool _canCreate() {
    final role = ApiClient.currentSession?.role;
    return role == 'SALES' || role == 'ADMIN';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Заказы')),
      drawer: const AppDrawer(currentRoute: '/orders'),
      floatingActionButton: _canCreate()
          ? FloatingActionButton(
              onPressed: () async {
                await Navigator.of(context).pushNamed('/orders/new');
                _refresh();
              },
              child: const Icon(Icons.add),
            )
          : null,
      body: Column(
        children: [
          SizedBox(
            height: 48,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              children: [
                _FilterChip(label: 'Все', selected: _status == null, onTap: () {
                  setState(() => _status = null);
                  _refresh();
                }),
                for (final s in orderStatuses)
                  _FilterChip(
                    label: orderStatusLabels[s]!,
                    selected: _status == s,
                    onTap: () {
                      setState(() => _status = s);
                      _refresh();
                    },
                  ),
              ],
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: FutureBuilder<List<OrderSummary>>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final orders = snapshot.data!;
                  if (orders.isEmpty) {
                    return ListView(children: const [
                      Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(child: Text('Заказов не найдено', style: TextStyle(color: Colors.grey))),
                      ),
                    ]);
                  }
                  return ListView.separated(
                    itemCount: orders.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final o = orders[i];
                      return ListTile(
                        title: Text('#${o.id} · ${o.clientName}'),
                        subtitle: Text('${formatDate(o.createdAt)} · ${formatMoney(o.totalPrice)}'),
                        trailing: StatusBadge(status: o.status, label: o.statusLabel),
                        onTap: () async {
                          await Navigator.of(context).pushNamed('/orders/detail', arguments: o.id);
                          _refresh();
                        },
                      );
                    },
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _FilterChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _FilterChip({required this.label, required this.selected, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(label: Text(label), selected: selected, onSelected: (_) => onTap()),
    );
  }
}
