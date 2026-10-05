import 'package:flutter/material.dart';

import '../models/dashboard.dart';
import '../models/ingredient.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
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
  late Future<WarehouseDashboard> _fgFuture;
  DateTimeRange _range = todayRange();

  bool get _isWarehouseRaw => ApiClient.currentSession?.role == 'WAREHOUSE_RAW';
  bool get _isWarehouseFg => ApiClient.currentSession?.role == 'WAREHOUSE_FG';

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    if (_isWarehouseRaw) {
      _prodFuture = _api.getProductionDashboard(from: _range.start, to: _range.end);
    } else if (_isWarehouseFg) {
      _fgFuture = _api.getWarehouseDashboard(from: _range.start, to: _range.end);
    } else {
      _future = _api.getDashboard(from: _range.start, to: _range.end);
    }
  }

  Future<void> _refresh() async {
    setState(_load);
    await (_isWarehouseRaw ? _prodFuture : _isWarehouseFg ? _fgFuture : _future);
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
              child: _isWarehouseRaw
                  ? _buildProductionBody()
                  : _isWarehouseFg
                      ? _buildWarehouseBody()
                      : _buildSalesBody(),
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
                                'Фактическая продажа за ${_rangeLabel(d.from, d.to)}',
                                style: const TextStyle(color: Colors.white70, fontSize: 13),
                              ),
                              if (d.returnsTotal > 0) ...[
                                const SizedBox(height: 6),
                                Text(
                                  'Продано ${formatMoney(d.grossSalesTotal)} · возвраты −${formatMoney(d.returnsTotal)}',
                                  style: const TextStyle(color: Colors.white, fontSize: 13),
                                ),
                              ],
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
                      _LowStockCard(title: 'Низкие остатки сырья', items: d.lowStock),
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
            _LowStockCard(title: 'Низкие остатки сырья', items: d.lowStock),
            const SizedBox(height: 20),
            const _SectionHeader(title: 'Заявки, ожидающие сборки'),
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

  Widget _buildWarehouseBody() {
    return FutureBuilder<WarehouseDashboard>(
      future: _fgFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
        }
        final d = snapshot.data!;
        void openOrders(String status) => Navigator.of(context).pushNamed(
              '/orders',
              arguments: OrdersFilterArgs(status: status, from: d.from, to: d.to),
            );
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
                      '${d.shippedInRange}',
                      style: const TextStyle(fontSize: 30, fontWeight: FontWeight.bold, color: Colors.white),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Заказов отгружено за ${_rangeLabel(d.from, d.to)}',
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
                  child: _StatCard(value: '${d.toAssemble}', label: 'Собрать', onTap: () => openOrders('NEW')),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _StatCard(value: '${d.readyToShip}', label: 'Отгрузить', onTap: () => openOrders('READY')),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _StatCard(
                    value: '${d.awaitingReceipt}',
                    label: 'Принять из цеха',
                    onTap: () => Navigator.of(context).pushNamed('/production-requests'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            const _SectionHeader(title: 'Заказы в работе'),
            const SizedBox(height: 8),
            if (d.activeOrders.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('Заказов на сборку и отгрузку нет', style: TextStyle(color: Colors.grey)),
              )
            else
              for (final o in d.activeOrders)
                AppListTile(
                  leadingIcon: Icons.receipt_long_outlined,
                  title: '#${o.id} · ${o.clientName}',
                  subtitle: '${formatDate(o.createdAt)} · ${formatMoney(o.totalPrice)}',
                  trailing: StatusBadge(status: o.status, label: o.statusLabel),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/orders/detail', arguments: o.id);
                    _refresh();
                  },
                ),
            const SizedBox(height: 12),
            _StockCard(items: d.lowStock),
          ],
        );
      },
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  final String? trailing;

  const _SectionHeader({required this.title, this.trailing});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            title.toUpperCase(),
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, letterSpacing: 0.8, color: Color(0xFF6B6259)),
          ),
        ),
        if (trailing != null)
          Text(trailing!, style: const TextStyle(fontSize: 12, color: Color(0xFF8A817A))),
      ],
    );
  }
}

/// Сырьё ниже минимального остатка: полоса — доля от нормы, красная при остатке меньше половины нормы.
class _LowStockCard extends StatelessWidget {
  final String title;
  final List<Ingredient> items;

  const _LowStockCard({required this.title, required this.items});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        _SectionHeader(title: title, trailing: items.isEmpty ? null : '${items.length} поз.'),
        const SizedBox(height: 8),
        Card(
          child: items.isEmpty
              ? const ListTile(
                  leading: Icon(Icons.check_circle_outline, color: Color(0xFF3E8E5E)),
                  title: Text('Все остатки в норме'),
                )
              : Column(
                  children: [
                    for (var i = 0; i < items.length; i++) ...[
                      if (i > 0) const Divider(height: 1, indent: 14, endIndent: 14),
                      _StockRow(
                        name: items[i].name,
                        quantity: items[i].quantityInStock,
                        threshold: items[i].minThreshold,
                        unit: items[i].unit,
                      ),
                    ],
                  ],
                ),
        ),
      ],
    );
  }
}

/// Готовая продукция с наименьшим остатком — порога у товаров нет, полоса не рисуется.
class _StockCard extends StatelessWidget {
  final List<StockLine> items;

  const _StockCard({required this.items});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        const _SectionHeader(title: 'Меньше всего на складе ГП'),
        const SizedBox(height: 8),
        Card(
          child: Column(
            children: [
              for (var i = 0; i < items.length; i++) ...[
                if (i > 0) const Divider(height: 1, indent: 14, endIndent: 14),
                _StockRow(name: items[i].name, quantity: items[i].quantityInStock, unit: items[i].unit),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _StockRow extends StatelessWidget {
  final String name;
  final double quantity;
  final double? threshold;
  final String unit;

  const _StockRow({required this.name, required this.quantity, this.threshold, required this.unit});

  @override
  Widget build(BuildContext context) {
    final t = threshold;
    final ratio = t != null && t > 0 ? (quantity / t).clamp(0.0, 1.0) : null;
    final critical = quantity <= 0 || (ratio != null && ratio < 0.5);
    final color = critical ? const Color(0xFFB8433F) : const Color(0xFFC08A2E);
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w500)),
              ),
              const SizedBox(width: 12),
              Text.rich(
                TextSpan(
                  children: [
                    TextSpan(
                      text: formatQty(quantity),
                      style: TextStyle(color: color, fontWeight: FontWeight.w700),
                    ),
                    TextSpan(
                      text: t != null ? ' / ${formatQty(t)} $unit' : ' $unit',
                      style: const TextStyle(color: Color(0xFF8A817A)),
                    ),
                  ],
                ),
                style: const TextStyle(fontSize: 13.5, fontFeatures: [FontFeature.tabularFigures()]),
              ),
            ],
          ),
          if (ratio != null) ...[
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LinearProgressIndicator(
                value: ratio,
                minHeight: 4,
                color: color,
                backgroundColor: color.withValues(alpha: 0.14),
              ),
            ),
          ],
        ],
      ),
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
