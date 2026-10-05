import 'package:flutter/material.dart';

import '../models/dashboard.dart';
import '../models/order.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';
import 'error_view.dart';
import 'format.dart';
import 'status_badge.dart';

/// Плитка для компоновки «Крупные плитки».
class BigTile {
  final String label;
  final String value;
  final Color? dot;
  final String? hint;
  final VoidCallback? onTap;

  const BigTile({required this.label, required this.value, this.dot, this.hint, this.onTap});
}

/// Компоновка «Крупные плитки»: первая плитка на всю ширину, остальные по две в ряд; большие цифры
/// и кнопки — видно издалека и легко попасть пальцем (склад).
class BigTilesView extends StatelessWidget {
  final String title;
  final String subtitle;
  final List<BigTile> tiles;

  const BigTilesView({super.key, required this.title, required this.subtitle, required this.tiles});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    Widget tile(BigTile t, {bool big = false}) => Material(
          color: p.card,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(p.radius + 6),
            side: BorderSide(color: p.cardBorder),
          ),
          child: InkWell(
            borderRadius: BorderRadius.circular(p.radius + 6),
            onTap: t.onTap,
            child: Padding(
              padding: EdgeInsets.all(big ? 22 : 18),
              child: SizedBox(
                height: big ? 132 : 104,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Row(
                      children: [
                        if (t.dot != null) ...[
                          Container(width: 11, height: 11, decoration: BoxDecoration(color: t.dot, shape: BoxShape.circle)),
                          const SizedBox(width: 8),
                        ],
                        Expanded(
                          child: Text(t.label, style: TextStyle(fontSize: big ? 17 : 15.5, color: p.muted)),
                        ),
                      ],
                    ),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: Text(
                            t.value,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: big ? 58 : (t.value.length > 6 ? 24 : 44),
                              fontWeight: FontWeight.w500,
                              height: 1,
                              fontFeatures: const [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                        if (t.hint != null)
                          Text(t.hint!, style: TextStyle(fontSize: 13.5, color: p.muted)),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        );

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 4, 4, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(subtitle, style: TextStyle(fontSize: 14, color: p.muted)),
              const SizedBox(height: 2),
              Text(title, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w500)),
            ],
          ),
        ),
        if (tiles.isNotEmpty) tile(tiles.first, big: true),
        for (var i = 1; i < tiles.length; i += 2) ...[
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: tile(tiles[i])),
              const SizedBox(width: 12),
              Expanded(child: i + 1 < tiles.length ? tile(tiles[i + 1]) : const SizedBox()),
            ],
          ),
        ],
      ],
    );
  }
}

String _weekdayShort(DateTime d) => const ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'][d.weekday - 1];

/// Компоновка «График и Требует внимания»: продажи по дням за неделю, сравнение с прошлой
/// неделей и список предупреждений (мало сырья, просроченные долги, застрявшие заказы).
class InsightsView extends StatefulWidget {
  const InsightsView({super.key});

  @override
  State<InsightsView> createState() => _InsightsViewState();
}

class _InsightsViewState extends State<InsightsView> {
  final _api = ApiClient();
  late Future<DashboardInsights> _future = _api.getDashboardInsights();

  Future<void> _refresh() async {
    setState(() => _future = _api.getDashboardInsights());
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    return RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<DashboardInsights>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
          }
          final d = snapshot.data!;
          final maxDay = d.days.fold<double>(0, (m, e) => e.amount > m ? e.amount : m);
          final today = d.days.isEmpty ? 0.0 : d.days.last.amount;
          final change = d.prevWeekTotal > 0 ? ((d.weekTotal - d.prevWeekTotal) / d.prevWeekTotal * 100).round() : null;
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
            children: [
              Card(
                margin: EdgeInsets.zero,
                child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(child: Text('Продажи за 7 дней', style: TextStyle(fontSize: 13, color: p.muted))),
                          if (change != null)
                            Text(
                              '${change >= 0 ? '+' : ''}$change% к прошлой неделе',
                              style: TextStyle(fontSize: 13, color: change >= 0 ? p.success : p.danger),
                            ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        formatMoney(d.weekTotal),
                        style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w500, fontFeatures: [FontFeature.tabularFigures()]),
                      ),
                      const SizedBox(height: 16),
                      SizedBox(
                        height: 118,
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            for (var i = 0; i < d.days.length; i++) ...[
                              if (i > 0) const SizedBox(width: 8),
                              Expanded(
                                child: Tooltip(
                                  message: '${formatDate(d.days[i].date)}: ${formatMoney(d.days[i].amount)}',
                                  child: Column(
                                    mainAxisAlignment: MainAxisAlignment.end,
                                    children: [
                                      Container(
                                        height: maxDay <= 0 ? 4 : 4 + 86 * (d.days[i].amount.clamp(0, maxDay) / maxDay),
                                        decoration: BoxDecoration(
                                          color: i == d.days.length - 1 ? p.accent : p.track,
                                          borderRadius: const BorderRadius.vertical(top: Radius.circular(6), bottom: Radius.circular(3)),
                                        ),
                                      ),
                                      const SizedBox(height: 6),
                                      Text(
                                        _weekdayShort(d.days[i].date),
                                        style: TextStyle(
                                          fontSize: 11.5,
                                          color: i == d.days.length - 1 ? p.ink : p.muted,
                                          fontWeight: i == d.days.length - 1 ? FontWeight.w600 : FontWeight.w400,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                margin: EdgeInsets.zero,
                child: ListTile(
                  title: Text('Сегодня', style: TextStyle(fontSize: 13, color: p.muted)),
                  subtitle: Text(
                    formatMoney(today),
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w500, color: p.ink),
                  ),
                ),
              ),
              const SizedBox(height: 20),
              Padding(
                padding: const EdgeInsets.only(left: 4, bottom: 8),
                child: Text(
                  'ТРЕБУЕТ ВНИМАНИЯ',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.8, color: p.muted),
                ),
              ),
              if (d.alerts.isEmpty)
                Card(
                  margin: EdgeInsets.zero,
                  child: ListTile(
                    leading: Icon(Icons.check_circle_outline, color: p.success),
                    title: const Text('Всё в порядке'),
                  ),
                )
              else
                for (final a in d.alerts)
                  Card(
                    margin: const EdgeInsets.only(bottom: 8),
                    child: ListTile(
                      leading: Container(
                        width: 38,
                        height: 38,
                        decoration: BoxDecoration(color: p.tint(_alertColor(p, a.kind)), borderRadius: BorderRadius.circular(11)),
                        child: Icon(_alertIcon(a.kind), color: _alertColor(p, a.kind), size: 20),
                      ),
                      title: Text(a.title, style: const TextStyle(fontSize: 14.5)),
                      subtitle: Text(a.meta, style: TextStyle(fontSize: 12.5, color: p.muted)),
                      trailing: a.orderId != null ? Icon(Icons.chevron_right, color: p.muted) : null,
                      onTap: a.orderId == null
                          ? null
                          : () async {
                              await Navigator.of(context).pushNamed('/orders/detail', arguments: a.orderId);
                              _refresh();
                            },
                    ),
                  ),
              const RecentOrdersSection(),
            ],
          );
        },
      ),
    );
  }

  static Color _alertColor(AppPalette p, String kind) => switch (kind) {
        'LOW_STOCK' => p.danger,
        'OVERDUE_DEBT' => p.warn,
        _ => p.info,
      };

  static IconData _alertIcon(String kind) => switch (kind) {
        'LOW_STOCK' => Icons.inventory_2_outlined,
        'OVERDUE_DEBT' => Icons.hourglass_bottom_outlined,
        _ => Icons.schedule_outlined,
      };
}

/// Компоновка «Лента дня»: полоска последних 7 дней и все события выбранного дня по времени.
class FeedView extends StatefulWidget {
  const FeedView({super.key});

  @override
  State<FeedView> createState() => _FeedViewState();
}

class _FeedViewState extends State<FeedView> {
  final _api = ApiClient();
  late DateTime _day = DateUtils.dateOnly(DateTime.now());
  late Future<DayFeed> _future = _api.getDayFeed(date: _day);

  Future<void> _load() async {
    setState(() => _future = _api.getDayFeed(date: _day));
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final today = DateUtils.dateOnly(DateTime.now());
    final days = [for (var i = 6; i >= 0; i--) today.subtract(Duration(days: i))];
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 8),
          child: Row(
            children: [
              for (var i = 0; i < days.length; i++) ...[
                if (i > 0) const SizedBox(width: 6),
                Expanded(
                  child: Material(
                    color: DateUtils.isSameDay(days[i], _day) ? p.accent : p.seg,
                    borderRadius: BorderRadius.circular(14),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(14),
                      onTap: () {
                        _day = days[i];
                        _load();
                      },
                      child: SizedBox(
                        height: 54,
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text(
                              _weekdayShort(days[i]),
                              style: TextStyle(
                                fontSize: 11,
                                color: DateUtils.isSameDay(days[i], _day) ? p.onAccent : p.muted,
                              ),
                            ),
                            Text(
                              '${days[i].day}',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w500,
                                color: DateUtils.isSameDay(days[i], _day) ? p.onAccent : p.ink,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: _load,
            child: FutureBuilder<DayFeed>(
              future: _future,
              builder: (context, snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError) {
                  return ErrorView(error: snapshot.error.toString(), onRetry: _load);
                }
                final f = snapshot.data!;
                return ListView(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(4, 4, 4, 10),
                      child: Wrap(
                        spacing: 18,
                        children: [
                          Text.rich(TextSpan(children: [
                            TextSpan(text: 'Продано ', style: TextStyle(color: p.muted)),
                            TextSpan(text: formatMoney(f.salesTotal), style: const TextStyle(fontWeight: FontWeight.w500)),
                          ])),
                          Text.rich(TextSpan(children: [
                            TextSpan(text: 'Заказов ', style: TextStyle(color: p.muted)),
                            TextSpan(text: '${f.ordersCreated}', style: const TextStyle(fontWeight: FontWeight.w500)),
                          ])),
                        ],
                      ),
                    ),
                    if (f.events.isEmpty)
                      Padding(
                        padding: const EdgeInsets.all(24),
                        child: Center(child: Text('За этот день событий нет', style: TextStyle(color: p.muted))),
                      ),
                    for (var i = 0; i < f.events.length; i++)
                      _FeedRow(event: f.events[i], last: i == f.events.length - 1, onChanged: _load),
                  ],
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}

class _FeedRow extends StatelessWidget {
  final FeedEvent event;
  final bool last;
  final VoidCallback onChanged;

  const _FeedRow({required this.event, required this.last, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final color = switch (event.kind) {
      'ORDER_NEW' => p.warn,
      'ORDER_SHIPPED' => p.success,
      'RETURN' => p.danger,
      'REQUEST_COLLECTED' => p.info,
      _ => p.muted,
    };
    final t = event.time;
    final time = '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
    VoidCallback? onTap;
    if (event.orderId != null) {
      onTap = () async {
        await Navigator.of(context).pushNamed('/orders/detail', arguments: event.orderId);
        onChanged();
      };
    } else if (event.requestId != null) {
      onTap = () async {
        await Navigator.of(context).pushNamed('/production-requests/detail', arguments: event.requestId);
        onChanged();
      };
    }
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 44,
            child: Padding(
              padding: const EdgeInsets.only(top: 14),
              child: Text(
                time,
                textAlign: TextAlign.right,
                style: TextStyle(fontSize: 13, color: p.muted, fontFeatures: const [FontFeature.tabularFigures()]),
              ),
            ),
          ),
          SizedBox(
            width: 30,
            child: Stack(
              alignment: Alignment.topCenter,
              children: [
                Positioned(top: 0, bottom: 0, child: Container(width: 2, color: last ? Colors.transparent : p.line)),
                Positioned(
                  top: 16,
                  child: Container(
                    width: 12,
                    height: 12,
                    decoration: BoxDecoration(color: color, shape: BoxShape.circle, border: Border.all(color: p.bg, width: 3)),
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Material(
                color: p.card,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(p.radius),
                  side: BorderSide(color: p.cardBorder),
                ),
                child: InkWell(
                  borderRadius: BorderRadius.circular(p.radius),
                  onTap: onTap,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(event.title, style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w500)),
                        if (event.meta.isNotEmpty) ...[
                          const SizedBox(height: 2),
                          Text(event.meta, style: TextStyle(fontSize: 12.5, color: p.muted)),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// «Последние заказы» внизу дашборда: 5 самых свежих за неделю, «Все» ведёт в список заказов.
class RecentOrdersSection extends StatefulWidget {
  const RecentOrdersSection({super.key});

  @override
  State<RecentOrdersSection> createState() => _RecentOrdersSectionState();
}

class _RecentOrdersSectionState extends State<RecentOrdersSection> {
  late final Future<List<OrderSummary>> _future = () {
    final today = DateUtils.dateOnly(DateTime.now());
    return ApiClient().getOrders(from: today.subtract(const Duration(days: 6)), to: today);
  }();

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    return FutureBuilder<List<OrderSummary>>(
      future: _future,
      builder: (context, snapshot) {
        if (!snapshot.hasData) return const SizedBox.shrink();
        final orders = [...snapshot.data!]..sort((a, b) => b.createdAt.compareTo(a.createdAt));
        final recent = orders.take(5).toList();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(left: 4),
                    child: Text(
                      'ПОСЛЕДНИЕ ЗАКАЗЫ',
                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.8, color: p.muted),
                    ),
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.of(context).pushNamed('/orders'),
                  child: const Text('Все'),
                ),
              ],
            ),
            if (recent.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 4),
                child: Text('За неделю заказов нет', style: TextStyle(color: p.muted)),
              )
            else
              Card(
                margin: EdgeInsets.zero,
                child: Column(
                  children: [
                    for (final (i, o) in recent.indexed) ...[
                      if (i > 0) Divider(height: 1, indent: 14, endIndent: 14, color: p.line),
                      ListTile(
                        title: Text(o.clientName, maxLines: 1, overflow: TextOverflow.ellipsis),
                        subtitle: Text('№${o.id} · ${formatDate(o.createdAt)}', style: TextStyle(fontSize: 12.5, color: p.muted)),
                        trailing: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(
                              formatMoney(o.totalPrice),
                              style: const TextStyle(fontWeight: FontWeight.w500, fontFeatures: [FontFeature.tabularFigures()]),
                            ),
                            const SizedBox(height: 3),
                            StatusBadge(status: o.status, label: o.statusLabel),
                          ],
                        ),
                        onTap: () => Navigator.of(context).pushNamed('/orders/detail', arguments: o.id),
                      ),
                    ],
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}
