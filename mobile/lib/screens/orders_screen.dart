import 'package:flutter/material.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../widgets/app_choice_chip.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/status_badge.dart';

/// Аргументы навигации на экран заказов с предустановленным фильтром — используется,
/// например, при переходе с плиток дашборда ("Новых заказов" / "Готовы к отгрузке").
class OrdersFilterArgs {
  final String? status;
  final DateTime from;
  final DateTime to;

  const OrdersFilterArgs({this.status, required this.from, required this.to});
}

class OrdersScreen extends StatefulWidget {
  final OrdersFilterArgs? initialFilter;

  const OrdersScreen({super.key, this.initialFilter});

  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends State<OrdersScreen> {
  final _api = ApiClient();
  late Future<List<OrderSummary>> _future;
  String? _status;
  late DateTimeRange _range;

  @override
  void initState() {
    super.initState();
    final initial = widget.initialFilter;
    if (initial != null) {
      _status = initial.status;
      _range = DateTimeRange(start: initial.from, end: initial.to);
    } else {
      // Склад ГП по умолчанию видит заказы, которые нужно собрать, бухгалтер — уже отгруженные.
      // Фильтр остаётся переключаемым — это только удобный дефолт, не ограничение доступа.
      switch (ApiClient.currentSession?.role) {
        case 'WAREHOUSE_FG':
          _status = 'NEW';
        case 'ACCOUNTANT':
          _status = 'DELIVERED';
      }
      _range = todayRange();
    }
    _future = _api.getOrders(status: _status, from: _range.start, to: _range.end);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getOrders(status: _status, from: _range.start, to: _range.end);
    });
    await _future;
  }

  void _onRangeChanged(DateTimeRange range) {
    setState(() => _range = range);
    _refresh();
  }

  bool _canCreate() {
    final role = ApiClient.currentSession?.role;
    return role == 'SALES' || role == 'ADMIN' || role == 'ACCOUNTANT';
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
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          SizedBox(
            height: 48,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              children: [
                AppChoiceChip(
                  label: 'Все',
                  selected: _status == null,
                  onTap: () {
                    setState(() => _status = null);
                    _refresh();
                  },
                ),
                for (final s in orderStatuses)
                  AppChoiceChip(
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
                    return ListView(
                      children: const [
                        Padding(
                          padding: EdgeInsets.all(32),
                          child: Center(child: Text('Заказов не найдено', style: TextStyle(color: Colors.grey))),
                        ),
                      ],
                    );
                  }
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: orders.length,
                    itemBuilder: (context, i) {
                      final o = orders[i];
                      // Незавершённые заказы сервер отдаёт всегда, даже вне выбранного диапазона
                      // дат (см. OrderService.findByDateRange) — подсвечиваем такие отдельно,
                      // чтобы было видно, что это "хвост" из прошлых дней, а не заказ за период.
                      final isOutOfRange = o.createdAt.isBefore(_range.start) ||
                          o.createdAt.isAfter(_range.end.add(const Duration(days: 1)));
                      return AppListTile(
                        leadingIcon: isOutOfRange
                            ? Icons.history_outlined
                            : (o.fromWhatsapp ? Icons.chat_bubble_rounded : Icons.receipt_long_outlined),
                        leadingColor: isOutOfRange
                            ? const Color(0xFFC99A2E)
                            : (o.fromWhatsapp ? const Color(0xFF25D366) : null),
                        title: '#${o.id} · ${o.clientName}',
                        subtitle: '${formatDate(o.createdAt)} · ${formatMoney(o.totalPrice)}',
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
