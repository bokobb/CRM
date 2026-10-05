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

  String? get _role => ApiClient.currentSession?.role;

  /// Страницы сырья — складу сырья и тем, кто создаёт заявки на сырьё (админ, бухгалтер).
  bool get _hasRawPages => _isWarehouseRaw || _role == 'ADMIN' || _role == 'ACCOUNTANT';

  bool get _canCreateOrder => _role == 'SALES' || _role == 'ADMIN' || _role == 'ACCOUNTANT';
  bool get _canCreateRequest => _role == 'ADMIN' || _role == 'ACCOUNTANT';

  void _load() {
    if (_hasRawPages) {
      _prodFuture = _api.getProductionDashboard(from: _range.start, to: _range.end);
    }
    if (_isWarehouseFg) {
      _fgFuture = _api.getWarehouseDashboard(from: _range.start, to: _range.end);
    }
    // Продажи — всем, кроме склада сырья (у склада ГП это отдельная страница «Продажи»).
    if (!_isWarehouseRaw) {
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
  /// Страницы дашборда роли, листаются свайпом влево/вправо; открытая запоминается (то же,
  /// что Настройки › Оформление › Компоновка).
  List<DashboardView> get _pages {
    if (_isWarehouseRaw) return const [DashboardView.raw, DashboardView.rawChart, DashboardView.feed];
    if (_isWarehouseFg) {
      return const [DashboardView.standard, DashboardView.sales, DashboardView.chart, DashboardView.feed];
    }
    return [
      DashboardView.standard,
      DashboardView.chart,
      DashboardView.feed,
      // Админу вместо страниц сырья — себестоимость (по дням и по продуктам за сегодня).
      if (_role == 'ADMIN') ...[DashboardView.costDays, DashboardView.costProducts]
      else if (_hasRawPages) ...[DashboardView.raw, DashboardView.rawChart],
    ];
  }

  static const _pageNames = {
    DashboardView.standard: 'Обычный',
    DashboardView.chart: 'График',
    DashboardView.feed: 'Лента дня',
    DashboardView.raw: 'Сырьё',
    DashboardView.rawChart: 'Сырьё · график',
    DashboardView.sales: 'Продажи',
    DashboardView.costDays: 'Себестоимость',
    DashboardView.costProducts: 'Себестоимость продуктов',
  };

  int get _current {
    final i = _pages.indexOf(LayoutController.dashboard.value);
    return i < 0 ? 0 : i;
  }

  late final PageController _pageController = PageController(initialPage: _current);

  void _onLayoutChanged() {
    final i = _current;
    if (_pageController.hasClients && _pageController.page?.round() != i) _pageController.jumpToPage(i);
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    LayoutController.dashboard.removeListener(_onLayoutChanged);
    _pageController.dispose();
    super.dispose();
  }

  Widget _withDateFilter(Widget child) => Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(child: RefreshIndicator(onRefresh: _refresh, child: child)),
        ],
      );

  Widget _page(DashboardView v) => switch (v) {
        DashboardView.standard => _withDateFilter(_isWarehouseFg ? _buildWarehouseBody() : _buildSalesBody()),
        DashboardView.chart => const InsightsView(),
        DashboardView.feed => const FeedView(),
        DashboardView.raw => _withDateFilter(_buildProductionBody()),
        DashboardView.rawChart => const RawInsightsView(),
        DashboardView.sales => _withDateFilter(_buildSalesBody()),
        DashboardView.costDays => const CostByDayView(),
        DashboardView.costProducts => const ProductCostTodayView(),
      };

  /// Кнопка внизу: на страницах продаж — новый заказ, на страницах сырья — новая заявка на сырьё.
  Widget? _bottomAction(DashboardView v) {
    final raw = v == DashboardView.raw || v == DashboardView.rawChart ||
        v == DashboardView.costDays || v == DashboardView.costProducts;
    if (raw && _canCreateRequest) {
      return FilledButton.icon(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/production-requests/new');
          _refresh();
        },
        icon: const Icon(Icons.science_outlined),
        label: const Text('Новая заявка на сырьё'),
      );
    }
    if (!raw && v != DashboardView.feed && _canCreateOrder) {
      return FilledButton.icon(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/orders/new');
          _refresh();
        },
        icon: const Icon(Icons.add),
        label: const Text('Новый заказ'),
      );
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final pages = _pages;
    final current = _current;
    final action = _bottomAction(pages[current]);
    return Scaffold(
      appBar: AppBar(
        title: Text('Дашборд · ${_pageNames[pages[current]]}'),
        actions: const [ThemeToggleButton(), SizedBox(width: 4)],
      ),
      drawer: const AppDrawer(currentRoute: '/'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/'),
      body: Column(
        children: [
          Expanded(
            child: PageView(
              controller: _pageController,
              onPageChanged: (i) => LayoutController.setDashboard(pages[i]),
              children: [for (final v in pages) _page(v)],
            ),
          ),
          if (action != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 2),
              child: SizedBox(width: double.infinity, child: action),
            ),
          if (pages.length > 1)
            Padding(
              padding: const EdgeInsets.only(top: 6, bottom: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  for (var i = 0; i < pages.length; i++)
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
    // Всё в норме — блок не показываем вовсе; только если что-то ниже нормы или ушло в минус.
    if (items.isEmpty) return const SizedBox.shrink();
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
