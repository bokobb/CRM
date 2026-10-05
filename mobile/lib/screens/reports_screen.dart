import 'package:flutter/material.dart';

import '../models/report.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import 'timesheet_screen.dart';
import '../theme/app_theme.dart';

/// Меню «Отчёты»: список отчётов, доступных роли (приходит с сервера), плюс прежний отчёт
/// «Производство: план / факт» с PDF. Любой серверный отчёт открывается одним экраном
/// [ReportScreen] — новые отчёты появляются без обновления приложения.
class ReportsScreen extends StatefulWidget {
  const ReportsScreen({super.key});

  @override
  State<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends State<ReportsScreen> {
  final _api = ApiClient();
  late Future<List<ReportInfo>> _future = _api.getReports();

  static const _icons = {
    'shift-receipt': Icons.inventory_outlined,
    'employees': Icons.badge_outlined,
    'clients': Icons.people_outline,
    'products': Icons.cake_outlined,
    'purchases': Icons.local_shipping_outlined,
    'summary': Icons.account_balance_wallet_outlined,
  };

  bool get _hasProductionPdf =>
      const {'ADMIN', 'ACCOUNTANT', 'WAREHOUSE_FG'}.contains(ApiClient.currentSession?.role);

  Future<void> _refresh() async {
    setState(() => _future = _api.getReports());
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Отчёты')),
      drawer: const AppDrawer(currentRoute: '/reports'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/reports'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<ReportInfo>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            final list = snapshot.data!;
            return ListView(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 24),
              children: [
                for (final r in list)
                  AppListTile(
                    leadingIcon: _icons[r.key] ?? Icons.summarize_outlined,
                    title: r.title,
                    subtitle: r.description,
                    trailing: Icon(Icons.chevron_right, color: context.pal.muted),
                    // Табель — своя таблица по дням с правкой, а не общий отчёт.
                    onTap: () => Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => r.key == 'employees' ? const TimesheetScreen() : ReportScreen(info: r))),
                  ),
                if (_hasProductionPdf)
                  AppListTile(
                    leadingIcon: Icons.picture_as_pdf_outlined,
                    title: 'Производство: план / факт',
                    subtitle: 'Оприходовано за день по товарам, отклонение от нормы, выгрузка PDF',
                    trailing: Icon(Icons.chevron_right, color: context.pal.muted),
                    onTap: () => Navigator.of(context).pushNamed('/production-requests/report'),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class ReportScreen extends StatefulWidget {
  final ReportInfo info;

  const ReportScreen({super.key, required this.info});

  @override
  State<ReportScreen> createState() => _ReportScreenState();
}

enum _Range { today, yesterday, week, month, lastMonth, custom }

class _ReportScreenState extends State<ReportScreen> {
  final _api = ApiClient();
  late DateTime _from;
  late DateTime _to;
  late _Range _range;
  late Future<Report> _future;

  bool get _isDay => widget.info.period == 'DAY';

  @override
  void initState() {
    super.initState();
    _setRange(widget.info.defaultRange == 'MONTH' && !_isDay ? _Range.month : _Range.today, load: false);
    _future = _api.getReport(widget.info.key, _from, _to);
  }

  static DateTime _day(DateTime d) => DateTime(d.year, d.month, d.day);

  void _setRange(_Range r, {bool load = true}) {
    final today = _day(DateTime.now());
    _range = r;
    switch (r) {
      case _Range.today:
        _from = _to = today;
      case _Range.yesterday:
        _from = _to = today.subtract(const Duration(days: 1));
      case _Range.week:
        _from = today.subtract(const Duration(days: 6));
        _to = today;
      case _Range.month:
        _from = DateTime(today.year, today.month, 1);
        _to = DateTime(today.year, today.month + 1, 0);
      case _Range.lastMonth:
        _from = DateTime(today.year, today.month - 1, 1);
        _to = DateTime(today.year, today.month, 0);
      case _Range.custom:
        break;
    }
    if (load) _reload();
  }

  void _reload() => setState(() => _future = _api.getReport(widget.info.key, _from, _to));

  Future<void> _pickCustom() async {
    final picked = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2025),
      lastDate: DateTime.now().add(const Duration(days: 366)),
      initialDateRange: DateTimeRange(start: _from, end: _to),
    );
    if (picked == null) return;
    _range = _Range.custom;
    _from = _day(picked.start);
    _to = _day(picked.end);
    _reload();
  }

  Future<void> _pickDay() async {
    final picked = await showDatePicker(
      context: context,
      firstDate: DateTime(2025),
      lastDate: DateTime.now().add(const Duration(days: 30)),
      initialDate: _from,
    );
    if (picked == null) return;
    _from = _to = _day(picked);
    _range = _Range.custom;
    _reload();
  }

  void _shiftDay(int days) {
    _from = _to = _from.add(Duration(days: days));
    _range = _Range.custom;
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.info.title)),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _isDay ? _dayBar() : _periodBar(),
          const Divider(height: 1),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () async {
                _reload();
                await _future;
              },
              child: FutureBuilder<Report>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) return ErrorView(error: snapshot.error.toString(), onRetry: _reload);
                  return _ReportBody(report: snapshot.data!);
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _dayBar() => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
        child: Row(
          children: [
            IconButton(onPressed: () => _shiftDay(-1), icon: const Icon(Icons.chevron_left)),
            Expanded(
              child: TextButton.icon(
                onPressed: _pickDay,
                icon: const Icon(Icons.calendar_today_outlined, size: 18),
                label: Text(_day(DateTime.now()) == _from ? 'Сегодня, ${formatDate(_from)}' : formatDate(_from)),
              ),
            ),
            IconButton(onPressed: () => _shiftDay(1), icon: const Icon(Icons.chevron_right)),
          ],
        ),
      );

  Widget _periodBar() {
    Widget chip(String label, _Range r) => Padding(
          padding: const EdgeInsets.only(right: 6),
          child: ChoiceChip(label: Text(label), selected: _range == r, onSelected: (_) => _setRange(r)),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
          child: Row(
            children: [
              chip('Сегодня', _Range.today),
              chip('Вчера', _Range.yesterday),
              chip('7 дней', _Range.week),
              chip('Этот месяц', _Range.month),
              chip('Прошлый месяц', _Range.lastMonth),
              ActionChip(
                avatar: const Icon(Icons.date_range, size: 18),
                label: const Text('Период…'),
                onPressed: _pickCustom,
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 8),
          child: Text(
            _from == _to ? formatDate(_from) : '${formatDate(_from)} — ${formatDate(_to)}',
            style: TextStyle(color: context.pal.muted, fontSize: 13),
          ),
        ),
      ],
    );
  }
}

Color? _toneColor(BuildContext context, String? tone) => switch (tone) {
      'good' => context.pal.success,
      'bad' => context.pal.danger,
      'muted' => context.pal.muted,
      _ => null,
    };

String _fmt(dynamic v, String type) {
  if (v == null) return '';
  if (v is num) {
    return switch (type) {
      'MONEY' => formatMoney(v.toDouble()),
      'QTY' => formatQty(v.toDouble()),
      'INT' => v.round().toString(),
      _ => v is double && v == v.roundToDouble() ? v.round().toString() : v.toString(),
    };
  }
  return v.toString();
}

class _ReportBody extends StatelessWidget {
  final Report report;

  const _ReportBody({required this.report});

  @override
  Widget build(BuildContext context) {
    final r = report;
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 32),
      children: [
        if (r.cards.isNotEmpty)
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [for (final c in r.cards) _CardTile(card: c)],
          ),
        const SizedBox(height: 12),
        if (r.rows.isEmpty)
          Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: Text('За этот период данных нет', style: TextStyle(color: context.pal.muted))),
          )
        else
          Card(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: _table(context),
            ),
          ),
        for (final n in r.notes)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(n, style: TextStyle(color: context.pal.muted, fontSize: 12)),
          ),
      ],
    );
  }

  Widget _table(BuildContext context) {
    final r = report;
    final border = BorderSide(color: context.pal.line, width: 0.6);
    Widget cell(String text, {bool numeric = false, bool bold = false, Color? color, bool first = false}) => Container(
          constraints: BoxConstraints(minWidth: first ? 140 : 64, maxWidth: first ? 240 : 320),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          alignment: numeric ? Alignment.centerRight : Alignment.centerLeft,
          child: Text(
            text,
            textAlign: numeric ? TextAlign.right : TextAlign.left,
            style: TextStyle(
              fontSize: 13,
              fontWeight: bold ? FontWeight.w600 : FontWeight.normal,
              color: color,
              fontFeatures: numeric ? const [FontFeature.tabularFigures()] : null,
            ),
          ),
        );
    return Table(
      defaultColumnWidth: const IntrinsicColumnWidth(),
      defaultVerticalAlignment: TableCellVerticalAlignment.middle,
      border: TableBorder(horizontalInside: border),
      children: [
        TableRow(
          decoration: BoxDecoration(color: context.pal.seg),
          children: [
            for (var i = 0; i < r.columns.length; i++)
              cell(r.columns[i].label, numeric: r.columns[i].numeric, bold: true, color: context.pal.muted, first: i == 0),
          ],
        ),
        for (final row in r.rows)
          TableRow(
            decoration: row.tone == 'bad'
                ? const BoxDecoration(color: Color(0x14B23A2E))
                : row.tone == 'good'
                    ? const BoxDecoration(color: Color(0x0F2F7D4F))
                    : null,
            children: [
              for (var i = 0; i < r.columns.length; i++)
                cell(
                  _fmt(i < row.cells.length ? row.cells[i] : null, r.columns[i].type),
                  numeric: r.columns[i].numeric,
                  first: i == 0,
                  color: row.tone == 'muted' ? context.pal.muted : (i == r.columns.length - 1 ? _toneColor(context, row.tone) : null),
                  bold: i == r.columns.length - 1 && (row.tone == 'bad' || row.tone == 'good'),
                ),
            ],
          ),
        if (r.totals != null)
          TableRow(
            decoration: BoxDecoration(color: context.pal.seg),
            children: [
              for (var i = 0; i < r.columns.length; i++)
                cell(_fmt(i < r.totals!.length ? r.totals![i] : null, r.columns[i].type),
                    numeric: r.columns[i].numeric, bold: true, first: i == 0),
            ],
          ),
      ],
    );
  }
}

class _CardTile extends StatelessWidget {
  final ReportCard card;

  const _CardTile({required this.card});

  @override
  Widget build(BuildContext context) {
    final width = (MediaQuery.of(context).size.width - 24 - 8) / 2;
    return SizedBox(
      width: width.clamp(150, 260),
      child: Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(card.label, style: TextStyle(color: context.pal.muted, fontSize: 12)),
              const SizedBox(height: 4),
              Text(
                _fmt(card.value, card.type),
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: _toneColor(context, card.tone)),
              ),
              if (card.hint != null) Text(card.hint!, style: TextStyle(color: context.pal.muted, fontSize: 11.5)),
            ],
          ),
        ),
      ),
    );
  }
}
