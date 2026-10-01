import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../models/production_cost.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/brigade_sheet.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/tech_card_style.dart';
import 'production_request_cost_screen.dart';

/// «Производство» — себестоимость за день: заявки на сырьё с этой датой документа × техкарты.
/// Норма — рецептура техкарты × замесы; факт — сырьё, реально выданное по заявке, разнесённое по
/// товарам пропорционально норме. Цена сырья — последняя закупка на эту дату, у полуфабриката —
/// по его рецептуре (backend IngredientPriceService). Себестоимость единицы считается на
/// оприходованное количество, а пока не оприходовано — на план.
/// Сами техкарты — на экране «Техкарта» (tech_cards_screen.dart).
class ProductionScreen extends StatefulWidget {
  const ProductionScreen({super.key});

  @override
  State<ProductionScreen> createState() => _ProductionScreenState();
}

final _dayFormat = DateFormat('d MMMM, EEEE', 'ru');
final _shortDay = DateFormat('d', 'ru');

String _money(double v) => formatMoney(v.roundToDouble());

String? _markup(double cost, double sale) => cost > 0 && sale > 0 ? '${((sale - cost) / cost * 100).round()} %' : null;

class _ProductionScreenState extends State<ProductionScreen> {
  final _api = ApiClient();
  late DateTime _date;
  late Future<ProductionCost> _future;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _date = DateTime(now.year, now.month, now.day);
    _future = _api.getProductionCost(_date);
  }

  void _setDate(DateTime d) {
    final today = DateTime.now();
    if (d.isAfter(DateTime(today.year, today.month, today.day))) return;
    setState(() {
      _date = d;
      _future = _api.getProductionCost(d);
    });
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getProductionCost(_date));
    await _future;
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2026, 9, 1),
      lastDate: DateTime.now(),
    );
    if (picked != null) _setDate(picked);
  }

  @override
  Widget build(BuildContext context) {
    final today = DateTime.now();
    final isToday = _date == DateTime(today.year, today.month, today.day);
    return Theme(
      data: TK.theme(Theme.of(context)),
      child: Scaffold(
        appBar: AppBar(title: const Text('Производство')),
        drawer: const AppDrawer(currentRoute: '/production'),
        body: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
            children: [
              const Text('Себестоимость за день', style: TK.h1),
              const SizedBox(height: 4),
              const Text(
                'Заявки на сырьё за день по техкартам: норма против выданного сырья и себестоимость единицы.',
                style: TextStyle(fontFamily: TK.body, fontSize: 13, color: TK.muted),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  IconButton(
                    onPressed: () => _setDate(_date.subtract(const Duration(days: 1))),
                    icon: const Icon(Icons.chevron_left),
                    tooltip: 'Предыдущий день',
                  ),
                  Expanded(
                    child: InkWell(
                      onTap: _pickDate,
                      borderRadius: BorderRadius.circular(8),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Column(
                          children: [
                            Text(_dayFormat.format(_date), style: TK.h2, textAlign: TextAlign.center),
                            if (isToday) ...[
                              const SizedBox(height: 2),
                              const TKLabel('сегодня'),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: isToday ? null : () => _setDate(_date.add(const Duration(days: 1))),
                    icon: const Icon(Icons.chevron_right),
                    tooltip: 'Следующий день',
                  ),
                ],
              ),
              const SizedBox(height: 8),
              FutureBuilder<ProductionCost>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Padding(padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()));
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  return _DayView(data: snapshot.data!, selected: _date, onSelectDay: _setDate, onChanged: _refresh);
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DayView extends StatelessWidget {
  final ProductionCost data;
  final DateTime selected;
  final ValueChanged<DateTime> onSelectDay;
  final Future<void> Function() onChanged;

  const _DayView({required this.data, required this.selected, required this.onSelectDay, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final d = data;
    final margin = _markup(d.finishedCost, d.finishedSaleValue);
    final deviation = d.issuedCost - d.normCost;
    final devPct = d.normCost > 0 ? deviation / d.normCost * 100 : 0.0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _History(days: d.history, selected: selected, onSelect: onSelectDay),
        const SizedBox(height: 14),
        if (d.requests.isEmpty)
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: TK.paper,
              border: Border.all(color: TK.line),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Text('В этот день заявок на сырьё нет.', style: TK.note, textAlign: TextAlign.center),
          )
        else ...[
          _Stats(stats: [
            ('Себестоимость', _money(d.finishedCost), d.laborCost == null ? 'сырьё, готовая продукция' : 'сырьё + зарплата', false),
            ('По цене продажи', _money(d.finishedSaleValue), margin == null ? '—' : 'наценка $margin', false),
            ('Сырьё выдано', _money(d.issuedCost), 'норма ${_money(d.normCost)}', false),
            (
              'Отклонение',
              '${deviation > 0 ? '+' : ''}${_money(deviation)}',
              deviation.abs() < 1 ? 'точно по норме' : '${devPct > 0 ? '+' : ''}${devPct.toStringAsFixed(1)} % к норме',
              deviation >= 1,
            ),
            if (d.laborCost != null)
              ('Зарплата', _money(d.laborCost!), '${d.requests.fold<int>(0, (n, r) => n + r.brigade.length)} чел. в бригадах', false),
            if (d.semiFinishedCost > 0)
              ('Полуфабрикаты', _money(d.semiFinishedCost), 'войдут в товар при упаковке', false),
          ]),
          if (d.unpricedIngredients.isNotEmpty) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(color: TK.yolkBg, borderRadius: BorderRadius.circular(8)),
              child: Text(
                'Нет цены (ни одной закупки с суммой): ${d.unpricedIngredients.join(', ')}. '
                'В себестоимость они вошли как 0 ₸.',
                style: const TextStyle(fontFamily: TK.body, fontSize: 12.5, color: TK.yolkInk),
              ),
            ),
          ],
          const SizedBox(height: 18),
          TKLabel('Заявки · ${d.requests.length}'),
          const SizedBox(height: 8),
          for (final r in d.requests) _RequestCard(request: r, date: d.date, onChanged: onChanged),
        ],
      ],
    );
  }
}

/// Столбики за 14 дней: себестоимость готовой продукции, тап — перейти на день.
class _History extends StatelessWidget {
  final List<ProductionCostDay> days;
  final DateTime selected;
  final ValueChanged<DateTime> onSelect;

  const _History({required this.days, required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final max = days.fold<double>(0, (m, d) => d.finishedCost > m ? d.finishedCost : m);
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
      decoration: BoxDecoration(
        color: TK.paper,
        border: Border.all(color: TK.line),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(child: TKLabel('Себестоимость по дням')),
              Text(max > 0 ? 'макс. ${_money(max)}' : '', style: TK.num.copyWith(fontSize: 11.5, color: TK.muted)),
            ],
          ),
          const SizedBox(height: 10),
          SizedBox(
            height: 92,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                for (final d in days)
                  Expanded(
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => onSelect(d.date),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 2.5),
                            child: Container(
                              height: max > 0 && d.finishedCost > 0 ? 4 + 64 * d.finishedCost / max : 2,
                              decoration: BoxDecoration(
                                color: _same(d.date, selected)
                                    ? TK.accent
                                    : (d.finishedCost > 0 ? TK.accent.withValues(alpha: 0.28) : TK.line),
                                borderRadius: const BorderRadius.vertical(top: Radius.circular(3)),
                              ),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            _shortDay.format(d.date),
                            style: TK.num.copyWith(
                              fontSize: 10.5,
                              color: _same(d.date, selected) ? TK.ink : TK.muted,
                              fontWeight: _same(d.date, selected) ? FontWeight.w600 : FontWeight.w400,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static bool _same(DateTime a, DateTime b) => a.year == b.year && a.month == b.month && a.day == b.day;
}

/// Плитки показателей 2 в ряд, разделены линиями 1 px — как в техкарте.
class _Stats extends StatelessWidget {
  final List<(String, String, String, bool)> stats;

  const _Stats({required this.stats});

  @override
  Widget build(BuildContext context) {
    Widget tile((String, String, String, bool) s) => Container(
          color: TK.paper,
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TKLabel(s.$1),
              const SizedBox(height: 4),
              Text(s.$2, style: TK.num.copyWith(fontSize: 17, fontWeight: FontWeight.w600, color: s.$4 ? TK.bad : TK.ink)),
              const SizedBox(height: 2),
              Text(s.$3, style: TK.note.copyWith(color: s.$4 ? TK.bad : TK.muted)),
            ],
          ),
        );
    final rows = <Widget>[];
    for (var i = 0; i < stats.length; i += 2) {
      rows.add(IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: tile(stats[i])),
            const SizedBox(width: 1),
            Expanded(child: i + 1 < stats.length ? tile(stats[i + 1]) : Container(color: TK.paper)),
          ],
        ),
      ));
      if (i + 2 < stats.length) rows.add(const SizedBox(height: 1));
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Container(
        color: TK.line,
        foregroundDecoration: BoxDecoration(border: Border.all(color: TK.line), borderRadius: BorderRadius.circular(8)),
        child: Column(children: rows),
      ),
    );
  }
}

class _RequestCard extends StatelessWidget {
  final ProductionCostRequest request;
  final DateTime date;
  final Future<void> Function() onChanged;

  const _RequestCard({required this.request, required this.date, required this.onChanged});

  Future<void> _editBrigade(BuildContext context) async {
    final result = await showBrigadeSheet(
      context,
      requestId: request.id,
      requestTitle: request.title,
      date: date,
      current: [for (final m in request.brigade) m.employeeId],
    );
    if (result != null) onChanged();
  }

  @override
  Widget build(BuildContext context) {
    final r = request;
    final deviation = r.issuedCost - r.normCost;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: TK.paper,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10), side: const BorderSide(color: TK.line)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () async {
            await Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => ProductionRequestCostScreen(requestId: r.id)),
            );
            onChanged();
          },
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        '№${r.id} · ${r.title}',
                        style: const TextStyle(fontFamily: TK.display, fontSize: 14, fontWeight: FontWeight.w700, color: TK.ink),
                      ),
                    ),
                    _StatusChip(status: r.status, label: r.statusLabel),
                  ],
                ),
                const SizedBox(height: 6),
                Text.rich(
                  TextSpan(children: [
                    TextSpan(text: 'сырьё ${_money(r.issuedCost)}'),
                    TextSpan(text: ' · норма ${_money(r.normCost)}', style: const TextStyle(color: TK.muted)),
                    if (deviation.abs() >= 1)
                      TextSpan(
                        text: '  ${deviation > 0 ? '+' : ''}${_money(deviation)}',
                        style: TextStyle(color: deviation > 0 ? TK.bad : TK.good, fontWeight: FontWeight.w600),
                      ),
                  ]),
                  style: TK.num.copyWith(fontSize: 12.5),
                ),
                const SizedBox(height: 8),
                _BrigadeRow(request: r, onEdit: () => _editBrigade(context)),
                const SizedBox(height: 6),
                for (final l in r.lines) _LineRow(line: l),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String status;
  final String label;

  const _StatusChip({required this.status, required this.label});

  @override
  Widget build(BuildContext context) {
    final done = status == 'RECEIVED';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(
        color: done ? TK.good.withValues(alpha: 0.12) : TK.soft,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(fontFamily: TK.body, fontSize: 11.5, fontWeight: FontWeight.w600, color: done ? TK.good : TK.muted),
      ),
    );
  }
}

class _LineRow extends StatelessWidget {
  final ProductionCostLine line;

  const _LineRow({required this.line});

  @override
  Widget build(BuildContext context) {
    final l = line;
    final unit = l.unit ?? 'шт';
    final markup = l.unitCost == null || l.salePrice == null ? null : _markup(l.unitCost!, l.salePrice!);
    final loss = l.unitCost != null && l.salePrice != null && l.salePrice! > 0 && l.unitCost! > l.salePrice!;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(border: Border(top: BorderSide(color: TK.line))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  l.name + (l.semiFinished ? ' · п/ф' : ''),
                  style: const TextStyle(fontFamily: TK.body, fontSize: 13.5, fontWeight: FontWeight.w600, color: TK.ink),
                ),
              ),
              const SizedBox(width: 8),
              Text(_money(l.cost), style: TK.num.copyWith(fontWeight: FontWeight.w600)),
            ],
          ),
          const SizedBox(height: 2),
          Row(
            children: [
              Expanded(
                child: Text(
                  '${formatQty(l.batches)} зам. → ${formatQty(l.quantity)} $unit ${l.received ? 'факт' : 'план'}',
                  style: TK.num.copyWith(fontSize: 12, color: TK.muted),
                ),
              ),
              if (l.unitCost != null)
                Text.rich(
                  TextSpan(children: [
                    TextSpan(text: '${formatMoney(l.unitCost!)}/$unit'),
                    if (markup != null)
                      TextSpan(
                        text: '  $markup',
                        style: TextStyle(color: loss ? TK.bad : TK.good, fontWeight: FontWeight.w600),
                      ),
                  ]),
                  style: TK.num.copyWith(fontSize: 12),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Бригада на карточке: имена таблетками + кнопка «Бригада» (активная — открывает выбор).
class _BrigadeRow extends StatelessWidget {
  final ProductionCostRequest request;
  final VoidCallback onEdit;

  const _BrigadeRow({required this.request, required this.onEdit});

  @override
  Widget build(BuildContext context) {
    final r = request;
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Material(
          color: r.brigade.isEmpty ? TK.yolkBg : TK.soft,
          shape: StadiumBorder(side: BorderSide(color: r.brigade.isEmpty ? TK.yolk : TK.line)),
          child: InkWell(
            customBorder: const StadiumBorder(),
            onTap: onEdit,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.groups_outlined, size: 16, color: r.brigade.isEmpty ? TK.yolkInk : TK.accent),
                  const SizedBox(width: 5),
                  Text(
                    r.brigade.isEmpty ? 'Назначить бригаду' : 'Бригада · ${r.brigade.length}',
                    style: TextStyle(
                      fontFamily: TK.body,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: r.brigade.isEmpty ? TK.yolkInk : TK.accent,
                    ),
                  ),
                  if (r.laborCost != null && r.laborCost! > 0) ...[
                    const SizedBox(width: 6),
                    Text(_money(r.laborCost!), style: TK.num.copyWith(fontSize: 11.5, color: TK.muted)),
                  ],
                ],
              ),
            ),
          ),
        ),
        if (r.brigade.isNotEmpty)
          Text(r.brigade.map((m) => m.fullName).join(', '),
              style: const TextStyle(fontFamily: TK.body, fontSize: 12.5, color: TK.ink)),
      ],
    );
  }
}
