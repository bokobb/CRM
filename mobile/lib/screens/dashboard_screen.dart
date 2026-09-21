import 'package:flutter/material.dart';

import '../models/dashboard.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/order_notification_sheet.dart';
import '../widgets/status_badge.dart';
import 'orders_screen.dart';

bool _isSameDay(DateTime a, DateTime b) => a.year == b.year && a.month == b.month && a.day == b.day;

String _rangeLabel(DateTime from, DateTime to) {
  if (_isSameDay(from, to)) {
    return _isSameDay(from, DateTime.now()) ? 'сегодня' : formatDate(from);
  }
  return '${formatDate(from)} — ${formatDate(to)}';
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final _api = ApiClient();
  late Future<Dashboard> _future;
  late Future<ProductionDashboard> _prodFuture;
  DateTimeRange _range = todayRange();

  bool get _isWarehouseRaw => ApiClient.currentSession?.role == 'WAREHOUSE_RAW';

  @override
  void initState() {
    super.initState();
    if (_isWarehouseRaw) {
      _prodFuture = _api.getProductionDashboard(from: _range.start, to: _range.end);
    } else {
      _future = _api.getDashboard(from: _range.start, to: _range.end);
    }
  }

  Future<void> _refresh() async {
    setState(() {
      if (_isWarehouseRaw) {
        _prodFuture = _api.getProductionDashboard(from: _range.start, to: _range.end);
      } else {
        _future = _api.getDashboard(from: _range.start, to: _range.end);
      }
    });
    await (_isWarehouseRaw ? _prodFuture : _future);
  }

  void _onRangeChanged(DateTimeRange range) {
    setState(() => _range = range);
    _refresh();
  }

  void _openOrders(BuildContext context, String status, Dashboard d) {
    Navigator.of(context).pushNamed(
      '/orders',
      arguments: OrdersFilterArgs(status: status, from: d.from, to: d.to),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Дашборд')),
      drawer: const AppDrawer(currentRoute: '/'),
      body: Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: _isWarehouseRaw ? _buildProductionBody() : _buildSalesBody(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSalesBody() {
    return FutureBuilder<Dashboard>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final d = snapshot.data!;
                  return ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      Card(
                        color: const Color(0xFFB8324A),
                        child: Padding(
                          padding: const EdgeInsets.all(18),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                formatMoney(d.todaySalesTotal),
                                style: const TextStyle(fontSize: 30, fontWeight: FontWeight.bold, color: Colors.white),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                'Продажи за ${_rangeLabel(d.from, d.to)}',
                                style: const TextStyle(color: Colors.white70, fontSize: 13),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: _StatCard(
                              value: '${d.newOrders}',
                              label: 'Новых заказов',
                              onTap: () => _openOrders(context, 'NEW', d),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: _StatCard(
                              value: '${d.readyOrders}',
                              label: 'Готовы к отгрузке',
                              onTap: () => _openOrders(context, 'READY', d),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 20),
                      Text('⚠️ Низкие остатки на складе', style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      if (d.lowStock.isEmpty)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 12),
                          child: Text('Остатков ниже нормы нет', style: TextStyle(color: Colors.grey)),
                        )
                      else
                        Card(
                          child: Column(
                            children: [
                              for (final i in d.lowStock)
                                ListTile(
                                  title: Text(i.name),
                                  trailing: Text(
                                    '${formatQty(i.quantityInStock)} ${i.unit}',
                                    style: const TextStyle(color: Color(0xFFB8433F), fontWeight: FontWeight.w600),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      const SizedBox(height: 20),
                      Text('🔔 Уведомления по заказам', style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      if (d.orderNotifications.isEmpty)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 12),
                          child: Text('Пока нет уведомлений', style: TextStyle(color: Colors.grey)),
                        )
                      else
                        for (final g in d.orderNotifications)
                          AppListTile(
                            leadingIcon: Icons.receipt_long_outlined,
                            title: 'Заказ №${g.orderId} (${g.clientName})',
                            subtitle:
                                '${g.history.length} уведомл${g.history.length == 1 ? "ение" : "ений"} · ${formatDateTime(g.lastNotifiedAt)}',
                            trailing: StatusBadge(status: g.status, label: g.statusLabel),
                            onTap: () => showOrderNotificationHistory(context, g),
                          ),
                    ],
                  );
                },
    );
  }

  Widget _buildProductionBody() {
    return FutureBuilder<ProductionDashboard>(
      future: _prodFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
        }
        final d = snapshot.data!;
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Card(
              color: const Color(0xFFB8324A),
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${d.collectedInRange}',
                      style: const TextStyle(fontSize: 30, fontWeight: FontWeight.bold, color: Colors.white),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Заявок собрано за ${_rangeLabel(d.from, d.to)}',
                      style: const TextStyle(color: Colors.white70, fontSize: 13),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _StatCard(
                    value: '${d.pending}',
                    label: 'Ждут сборки',
                    onTap: () => Navigator.of(context).pushNamed('/production-requests'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _StatCard(value: '${d.awaitingReceipt}', label: 'Собраны, ждут склад ГП'),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _StatCard(value: '${d.createdInRange}', label: 'Создано за период'),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Text('⚠️ Низкие остатки сырья', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (d.lowStock.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('Остатков ниже нормы нет', style: TextStyle(color: Colors.grey)),
              )
            else
              Card(
                child: Column(
                  children: [
                    for (final i in d.lowStock)
                      ListTile(
                        title: Text(i.name),
                        trailing: Text(
                          '${formatQty(i.quantityInStock)} ${i.unit}',
                          style: const TextStyle(color: Color(0xFFB8433F), fontWeight: FontWeight.w600),
                        ),
                      ),
                  ],
                ),
              ),
            const SizedBox(height: 20),
            Text('📋 Заявки, ожидающие сборки', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (d.pendingRequests.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('Заявок, ожидающих сборки, нет', style: TextStyle(color: Colors.grey)),
              )
            else
              for (final r in d.pendingRequests)
                AppListTile(
                  leadingIcon: Icons.science_outlined,
                  title: r.title,
                  subtitle: '${formatQty(r.totalBatches)} замес. · ${r.createdBy} · ${formatDateTime(r.createdAt)}',
                  trailing: StatusBadge(status: r.status, label: r.statusLabel),
                  onTap: () => Navigator.of(context).pushNamed('/production-requests/detail', arguments: r.id),
                ),
          ],
        );
      },
    );
  }
}

class _StatCard extends StatelessWidget {
  final String value;
  final String label;
  final VoidCallback? onTap;

  const _StatCard({required this.value, required this.label, this.onTap});

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(value, style: const TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
              const SizedBox(height: 2),
              Text(label, style: const TextStyle(color: Colors.grey, fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}
