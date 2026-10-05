import 'package:flutter/material.dart';

import '../models/dashboard.dart';
import '../models/ingredient.dart';
import '../services/api_client.dart';
import '../theme/app_layout.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/dashboard_views.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/status_badge.dart';
import 'orders_screen.dart';
import '../theme/app_theme.dart';

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
    LayoutController.dashboard.addListener(_onLayoutChanged);
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
  /// Свайп влево/вправо листает виды дашборда в этом порядке; выбранный запоминается (как в
  /// Настройки › Оформление › Компоновка).
  static const _pages = [DashboardView.standard, DashboardView.chart, DashboardView.feed, DashboardView.tiles];
  static const _pageNames = ['Обычный', 'График', 'Лента дня', 'Плитки'];
  late final PageController _pageController =
      PageController(initialPage: _pages.indexOf(LayoutController.dashboard.value));

  void _onLayoutChanged() {
    final i = _pages.indexOf(LayoutController.dashboard.value);
    if (_pageController.hasClients && _pageController.page?.round() != i) _pageController.jumpToPage(i);
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    LayoutController.dashboard.removeListener(_onLayoutChanged);
    _pageController.dispose();
    super.dispose();
  }

  Widget _standardOrTiles({required bool tiles}) => Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: tiles
                  ? _buildTilesBody()
                  : _isWarehouseRaw
                      ? _buildProductionBody()
                      : _isWarehouseFg
                          ? _buildWarehouseBody()
                          : _buildSalesBody(),
            ),
          ),
        ],
      );

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final current = _pages.indexOf(LayoutController.dashboard.value);
    return Scaffold(
      appBar: AppBar(
        title: Text('Дашборд · ${_pageNames[current]}'),
        actions: const [ThemeToggleButton(), SizedBox(width: 4)],
      ),
      drawer: const AppDrawer(currentRoute: '/'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/'),
      body: Column(
        children: [
          Expanded(
            child: PageView(
              controller: _pageController,
              onPageChanged: (i) => LayoutController.setDashboard(_pages[i]),
              children: [
                _standardOrTiles(tiles: false),
                const InsightsView(),
                const FeedView(),
                _standardOrTiles(tiles: true),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: 4, bottom: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                for (var i = 0; i < _pages.length; i++)
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    margin: const EdgeInsets.symmetric(horizontal: 3),
                    width: i == current ? 18 : 6,
                    height: 6,
                    decoration: BoxDecoration(
                      color: i == current ? p.accent : p.line,
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static String _todayTitle() {
    final now = DateTime.now();
    const wd = ['Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота', 'Воскресенье'];
    return '${wd[now.weekday - 1]}, ${formatDate(now)}';
  }

  /// Компоновка «Крупные плитки» — те же данные, что у обычного дашборда роли.
  Widget _buildTilesBody() {
    final p = context.pal;
    Widget wrap<T>(Future<T> future, BigTilesView Function(T) build) => FutureBuilder<T>(
          future: future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            return build(snapshot.data as T);
          },
        );
    if (_isWarehouseRaw) {
      return wrap<ProductionDashboard>(_prodFuture, (d) => BigTilesView(
            subtitle: _todayTitle(),
            title: 'Склад сырья',
            tiles: [
              BigTile(label: 'Ждут сборки', value: '${d.pending}', dot: p.warn,
                  onTap: () => Navigator.of(context).pushNamed('/production-requests')),
              BigTile(label: 'Собраны, ждут склад ГП', value: '${d.awaitingReceipt}', dot: p.info),
              BigTile(label: 'Собрано за период', value: '${d.collectedInRange}', dot: p.success),
              BigTile(label: 'Мало сырья', value: '${d.lowStock.length}', dot: p.danger,
                  onTap: () => Navigator.of(context).pushNamed('/ingredients-warehouse')),
              BigTile(label: 'Закупки', value: 'Открыть', onTap: () => Navigator.of(context).pushNamed('/purchases')),
            ],
          ));
    }
    if (_isWarehouseFg) {
      return wrap<WarehouseDashboard>(_fgFuture, (d) {
        final oldest = d.activeOrders.where((o) => o.status == 'NEW').toList();
        return BigTilesView(
          subtitle: _todayTitle(),
          title: 'Склад ГП',
          tiles: [
            BigTile(
              label: 'Собрать заказы',
              value: '${d.toAssemble}',
              dot: p.warn,
              hint: oldest.isEmpty ? null : 'старший — №${oldest.first.id}',
              onTap: () => _openOrdersStatus('NEW', d.from, d.to),
            ),
            BigTile(label: 'Отгрузить', value: '${d.readyToShip}', dot: p.info, onTap: () => _openOrdersStatus('READY', d.from, d.to)),
            BigTile(label: 'Принять из цеха', value: '${d.awaitingReceipt}', dot: p.success,
                onTap: () => Navigator.of(context).pushNamed('/production-requests')),
            BigTile(label: 'Возвраты', value: 'Оформить', onTap: () => Navigator.of(context).pushNamed('/returns')),
            BigTile(label: 'Остатки ГП', value: 'Открыть', onTap: () => Navigator.of(context).pushNamed('/warehouse')),
          ],
        );
      });
    }
    return wrap<Dashboard>(_future, (d) => BigTilesView(
          subtitle: _todayTitle(),
          title: 'Продажа ${_rangeLabel(d.from, d.to)}',
          tiles: [
            BigTile(label: 'Фактическая продажа', value: formatMoney(d.todaySalesTotal)),
            BigTile(label: 'Новых заказов', value: '${d.newOrders}', dot: p.warn, onTap: () => _openOrders(context, 'NEW', d)),
            BigTile(label: 'Готовы к отгрузке', value: '${d.readyOrders}', dot: p.info, onTap: () => _openOrders(context, 'READY', d)),
            BigTile(label: 'Возвраты', value: formatMoney(d.returnsTotal), dot: p.danger),
            BigTile(label: 'Мало сырья', value: '${d.lowStock.length}', dot: p.danger),
          ],
        ));
  }

  void _openOrdersStatus(String status, DateTime from, DateTime to) => Navigator.of(context).pushNamed(
        '/orders',
        arguments: OrdersFilterArgs(status: status, from: from, to: to),
      );

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
                      _HeroCard(
                        caption: 'Фактическая продажа · ${_rangeLabel(d.from, d.to)}',
                        value: formatMoney(d.todaySalesTotal),
                        left: ('Продано', formatMoney(d.grossSalesTotal), null),
                        right: ('Возвраты', d.returnsTotal > 0 ? '−${formatMoney(d.returnsTotal)}' : formatMoney(0), context.pal.danger),
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
                      const RecentOrdersSection(),
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
            _HeroCard(
              caption: 'Заявок собрано · ${_rangeLabel(d.from, d.to)}',
              value: '${d.collectedInRange}',
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
              Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('Заявок, ожидающих сборки, нет', style: TextStyle(color: context.pal.muted)),
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
            _HeroCard(
              caption: 'Заказов отгружено · ${_rangeLabel(d.from, d.to)}',
              value: '${d.shippedInRange}',
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
              Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('Заказов на сборку и отгрузку нет', style: TextStyle(color: context.pal.muted)),
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
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.8, color: context.pal.muted),
          ),
        ),
        if (trailing != null)
          Text(trailing!, style: TextStyle(fontSize: 12, color: context.pal.muted)),
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
    final color = critical ? context.pal.danger : const Color(0xFFC08A2E);
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
                      style: TextStyle(color: context.pal.muted),
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

class _HeroCard extends StatelessWidget {
  final String caption;
  final String value;
  final (String, String, Color?)? left;
  final (String, String, Color?)? right;

  const _HeroCard({required this.caption, required this.value, this.left, this.right});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    Widget col((String, String, Color?) c) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(c.$1, style: TextStyle(fontSize: 12, color: p.muted)),
            const SizedBox(height: 2),
            Text(
              c.$2,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: c.$3 ?? p.ink,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        );
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              caption.toUpperCase(),
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.8, color: p.muted),
            ),
            const SizedBox(height: 8),
            Text(
              value,
              style: const TextStyle(
                fontSize: 32,
                fontWeight: FontWeight.w600,
                letterSpacing: -0.6,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
            if (left != null && right != null) ...[
              const SizedBox(height: 14),
              Divider(height: 1, color: p.line),
              const SizedBox(height: 12),
              IntrinsicHeight(
                child: Row(
                  children: [
                    Expanded(child: col(left!)),
                    VerticalDivider(width: 1, color: p.line),
                    const SizedBox(width: 14),
                    Expanded(child: col(right!)),
                  ],
                ),
              ),
            ],
          ],
        ),
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
              Text(value, style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w600, fontFeatures: [FontFeature.tabularFigures()])),
              const SizedBox(height: 2),
              Text(label, style: TextStyle(color: context.pal.muted, fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}
