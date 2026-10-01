import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../models/production_cost.dart';
import '../services/api_client.dart';
import '../widgets/brigade_sheet.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/tech_card_style.dart';

/// Заявка на сырьё глазами себестоимости (открывается с карточки на «Производстве»):
/// сверху — сколько оприходовано и отклонение сырья от нормы, ниже — рецептура норма/выдано с
/// ценами, внизу — себестоимость единицы каждого товара: факт против плана по техкарте.
/// Саму заявку (сборка, оприходование) по-прежнему ведут на экране «Заявки на сырьё».
class ProductionRequestCostScreen extends StatefulWidget {
  final int requestId;

  const ProductionRequestCostScreen({super.key, required this.requestId});

  @override
  State<ProductionRequestCostScreen> createState() => _ProductionRequestCostScreenState();
}

final _dateFormat = DateFormat('d MMMM yyyy', 'ru');

String _money(double v) => formatMoney(v.roundToDouble());

String _signed(double v, String Function(double) f) =>
    '${v > 0
        ? '+'
        : v < 0
        ? '−'
        : ''}${f(v.abs())}';

class _ProductionRequestCostScreenState extends State<ProductionRequestCostScreen> {
  final _api = ApiClient();
  late Future<ProductionRequestCost> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getProductionRequestCost(widget.requestId);
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getProductionRequestCost(widget.requestId));
    await _future;
  }

  Future<void> _editBrigade(ProductionRequestCost d) async {
    final result = await showBrigadeSheet(
      context,
      requestId: d.request.id,
      requestTitle: d.request.title,
      date: d.date,
      current: [for (final m in d.request.brigade) m.employeeId],
    );
    if (result != null) setState(() => _future = Future.value(result));
  }

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: TK.theme(Theme.of(context)),
      child: Scaffold(
        appBar: AppBar(title: Text('Заявка №${widget.requestId}')),
        body: RefreshIndicator(
          onRefresh: _refresh,
          child: FutureBuilder<ProductionRequestCost>(
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
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
                children: [
                  _Sheet(
                    children: [
                      _Header(data: d, onEditBrigade: () => _editBrigade(d)),
                      _Received(data: d),
                      _Recipe(data: d),
                      _UnitCosts(data: d),
                    ],
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

/// Белый лист с секциями через линии — как техкарта.
class _Sheet extends StatelessWidget {
  final List<Widget> children;

  const _Sheet({required this.children});

  @override
  Widget build(BuildContext context) => Container(
    decoration: BoxDecoration(
      color: TK.paper,
      border: Border.all(color: TK.line),
      borderRadius: BorderRadius.circular(10),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < children.length; i++)
          Container(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
            decoration: i == 0
                ? null
                : const BoxDecoration(
                    border: Border(top: BorderSide(color: TK.line)),
                  ),
            child: children[i],
          ),
      ],
    ),
  );
}

class _SecTitle extends StatelessWidget {
  final String title;
  final String? note;

  const _SecTitle(this.title, {this.note});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: TK.h2),
        if (note != null) ...[const SizedBox(height: 3), Text(note!, style: TK.note)],
      ],
    ),
  );
}

class _Header extends StatelessWidget {
  final ProductionRequestCost data;
  final VoidCallback onEditBrigade;

  const _Header({required this.data, required this.onEditBrigade});

  @override
  Widget build(BuildContext context) {
    final r = data.request;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TKLabel('Заявка на сырьё · ${_dateFormat.format(data.date)} · ${r.statusLabel}'),
        const SizedBox(height: 6),
        Text(r.title, style: TK.h1),
        const SizedBox(height: 12),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: r.brigade.isEmpty
                  ? const Text(
                      'Бригада не назначена',
                      style: TextStyle(fontFamily: TK.body, fontSize: 13.5, color: TK.yolkInk),
                    )
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        for (final m in r.brigade)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 3),
                            child: Row(
                              children: [
                                Expanded(child: Text(m.fullName, style: TK.text.copyWith(fontSize: 13.5))),
                                if (m.dailyRate != null)
                                  Text(_money(m.dailyRate!), style: TK.num.copyWith(fontSize: 12.5, color: TK.muted)),
                              ],
                            ),
                          ),
                        if (r.laborCost != null)
                          Text(
                            'Зарплата за смену: ${_money(r.laborCost!)}',
                            style: TK.num.copyWith(fontSize: 12.5, fontWeight: FontWeight.w600),
                          ),
                        if (r.overheadCost != null && r.overheadCost! > 0)
                          Text(
                            'Накладные цеха: ${_money(r.overheadCost!)}',
                            style: TK.num.copyWith(fontSize: 12.5, color: TK.muted),
                          ),
                      ],
                    ),
            ),
            const SizedBox(width: 12),
            OutlinedButton.icon(
              onPressed: onEditBrigade,
              icon: const Icon(Icons.groups_outlined, size: 18),
              label: const Text('Бригада'),
              style: OutlinedButton.styleFrom(
                foregroundColor: TK.accent,
                side: const BorderSide(color: TK.accent),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// Сверху: оприходовано против плана по каждому товару + отклонение сырья от нормы.
class _Received extends StatelessWidget {
  final ProductionRequestCost data;

  const _Received({required this.data});

  @override
  Widget build(BuildContext context) {
    final r = data.request;
    final dev = r.issuedCost - r.normCost;
    final devPct = r.normCost > 0 ? dev / r.normCost * 100 : 0.0;
    final over = dev >= 1;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _SecTitle('Оприходовано'),
        for (final l in r.lines) _ReceivedRow(line: l),
        const SizedBox(height: 12),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: over ? TK.bad.withValues(alpha: 0.07) : TK.soft, borderRadius: BorderRadius.circular(8)),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const TKLabel('Сырьё: выдано / норма'),
                    const SizedBox(height: 4),
                    Text(
                      '${_money(r.issuedCost)} / ${_money(r.normCost)}',
                      style: TK.num.copyWith(fontSize: 14, fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  const TKLabel('Отклонение'),
                  const SizedBox(height: 4),
                  Text(
                    dev.abs() < 1 ? 'по норме' : '${_signed(dev, _money)}  ${_signed(devPct, (v) => '${v.toStringAsFixed(1)} %')}',
                    style: TK.num.copyWith(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: dev.abs() < 1 ? TK.good : (over ? TK.bad : TK.good),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _ReceivedRow extends StatelessWidget {
  final ProductionCostLine line;

  const _ReceivedRow({required this.line});

  @override
  Widget build(BuildContext context) {
    final l = line;
    final unit = l.unit ?? 'шт';
    final diff = l.quantity - l.plannedQuantity;
    final pct = l.plannedQuantity > 0 ? diff / l.plannedQuantity * 100 : 0.0;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.name + (l.semiFinished ? ' · п/ф' : ''),
                  style: const TextStyle(fontFamily: TK.body, fontSize: 13.5, fontWeight: FontWeight.w600, color: TK.ink),
                ),
                const SizedBox(height: 2),
                Text(
                  '${formatQty(l.batches)} зам. · план ${formatQty(l.plannedQuantity)} $unit',
                  style: TK.num.copyWith(fontSize: 12, color: TK.muted),
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                l.received ? '${formatQty(l.quantity)} $unit' : 'не оприходовано',
                style: TK.num.copyWith(fontWeight: FontWeight.w600, color: l.received ? TK.ink : TK.muted),
              ),
              if (l.received && diff.abs() > 0.0005)
                Text(
                  '${_signed(diff, formatQty)} $unit  ${_signed(pct, (v) => '${v.toStringAsFixed(1)} %')}',
                  style: TK.num.copyWith(fontSize: 12, color: diff < 0 ? TK.bad : TK.good, fontWeight: FontWeight.w600),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Рецептура заявки: по каждому сырью норма и выдано, цена, сумма. На узком экране — горизонтальная прокрутка.
class _Recipe extends StatelessWidget {
  final ProductionRequestCost data;

  const _Recipe({required this.data});

  @override
  Widget build(BuildContext context) {
    const head = TextStyle(fontFamily: TK.body, fontSize: 10.5, fontWeight: FontWeight.w600, color: TK.muted, letterSpacing: 0.4);
    final num = TK.num.copyWith(fontSize: 12);
    Widget cell(Widget child, {bool right = true}) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 7, horizontal: 4),
      child: Align(alignment: right ? Alignment.centerRight : Alignment.centerLeft, child: child),
    );
    final normTotal = data.ingredients.fold<double>(0, (s, i) => s + (i.normCost ?? 0));
    final issuedTotal = data.ingredients.fold<double>(0, (s, i) => s + (i.issuedCost ?? 0));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _SecTitle('Рецептура', note: 'Норма по техкарте × замесы и что выдано по заявке. Цена — последняя закупка на дату заявки.'),
        // Влезает в ширину телефона: единица — под названием, в колонках только числа.
        Table(
          defaultVerticalAlignment: TableCellVerticalAlignment.middle,
          columnWidths: const {
            0: FlexColumnWidth(),
            1: IntrinsicColumnWidth(),
            2: IntrinsicColumnWidth(),
            3: IntrinsicColumnWidth(),
            4: IntrinsicColumnWidth(),
          },
          border: const TableBorder(horizontalInside: BorderSide(color: TK.line)),
          children: [
            TableRow(
              children: [
                cell(const Text('СЫРЬЁ', style: head), right: false),
                cell(const Text('НОРМА', style: head)),
                cell(const Text('ВЫДАНО', style: head)),
                cell(const Text('ЦЕНА', style: head)),
                cell(const Text('СУММА ₸', style: head)),
              ],
            ),
            for (final i in data.ingredients)
              TableRow(
                children: [
                  cell(
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(i.name, style: TK.text.copyWith(fontSize: 12.5)),
                        Text(i.unit, style: TK.note.copyWith(fontSize: 11)),
                        if (i.originalName != null) Text('вместо: ${i.originalName}', style: TK.note.copyWith(fontSize: 11)),
                      ],
                    ),
                    right: false,
                  ),
                  cell(Text(formatQty(i.normQuantity), style: num.copyWith(color: TK.muted))),
                  cell(
                    Text(
                      formatQty(i.issuedQuantity),
                      style: num.copyWith(
                        fontWeight: FontWeight.w600,
                        color: (i.issuedQuantity - i.normQuantity).abs() < 0.0005
                            ? TK.ink
                            : (i.issuedQuantity > i.normQuantity ? TK.bad : TK.good),
                      ),
                    ),
                  ),
                  cell(
                    i.unitPrice == null
                        ? Text('нет', style: num.copyWith(color: TK.bad))
                        : Text(
                            '${formatQty(i.unitPrice!)}${i.priceSource == 'SEMI_FINISHED' ? ' п/ф' : ''}',
                            style: num.copyWith(color: TK.muted),
                          ),
                  ),
                  cell(Text(i.issuedCost == null ? '—' : formatQty(i.issuedCost!.roundToDouble()), style: num)),
                ],
              ),
            TableRow(
              decoration: const BoxDecoration(color: TK.soft),
              children: [
                cell(
                  const Text(
                    'Итого',
                    style: TextStyle(fontFamily: TK.body, fontWeight: FontWeight.w700, fontSize: 13),
                  ),
                  right: false,
                ),
                cell(Text(formatQty(normTotal.roundToDouble()), style: num.copyWith(color: TK.muted))),
                cell(const SizedBox()),
                cell(const SizedBox()),
                cell(Text(formatQty(issuedTotal.roundToDouble()), style: num.copyWith(fontWeight: FontWeight.w700))),
              ],
            ),
          ],
        ),
        if (data.unpricedIngredients.isNotEmpty) ...[
          const SizedBox(height: 10),
          Text(
            'Без цены (вошли как 0 ₸): ${data.unpricedIngredients.join(', ')}',
            style: const TextStyle(fontFamily: TK.body, fontSize: 12, color: TK.yolkInk),
          ),
        ],
      ],
    );
  }
}

/// Внизу: себестоимость единицы каждого товара — факт против плана по техкарте.
class _UnitCosts extends StatelessWidget {
  final ProductionRequestCost data;

  const _UnitCosts({required this.data});

  @override
  Widget build(BuildContext context) {
    final withLabor = data.request.laborCost != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SecTitle(
          'Себестоимость за единицу',
          note: withLabor
              ? 'Сырьё + зарплата бригады + накладные цеха (оклады слесаря, технологов, склада сырья, повара — '
                  'по заявкам дня пропорционально зарплате бригад). План — норма техкарты на плановый выпуск, факт — выданное сырьё на оприходованное.'
              : 'Только сырьё. План — норма техкарты на плановый выпуск, факт — выданное сырьё на оприходованное.',
        ),
        for (final l in data.request.lines) _UnitCostCard(line: l),
      ],
    );
  }
}

class _UnitCostCard extends StatelessWidget {
  final ProductionCostLine line;

  const _UnitCostCard({required this.line});

  @override
  Widget build(BuildContext context) {
    final l = line;
    final unit = l.unit ?? 'шт';
    final plan = l.planUnitCost;
    final fact = l.unitCost;
    final diff = plan != null && fact != null ? fact - plan : null;
    final markup = fact != null && fact > 0 && l.salePrice != null && l.salePrice! > 0 ? (l.salePrice! - fact) / fact * 100 : null;
    Widget col(String label, String value, {Color? color, String? hint}) => Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TKLabel(label),
          const SizedBox(height: 3),
          Text(
            value,
            style: TK.num.copyWith(fontSize: 15, fontWeight: FontWeight.w600, color: color ?? TK.ink),
          ),
          if (hint != null) Text(hint, style: TK.note.copyWith(fontSize: 11.5, color: color ?? TK.muted)),
        ],
      ),
    );
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: TK.line),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.name + (l.semiFinished ? ' · п/ф' : ''),
            style: const TextStyle(fontFamily: TK.body, fontSize: 14, fontWeight: FontWeight.w600, color: TK.ink),
          ),
          const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              col('План', plan == null ? '—' : '${formatMoney(plan)}/$unit'),
              col(
                l.received ? 'Факт' : 'Факт (на план)',
                fact == null ? '—' : '${formatMoney(fact)}/$unit',
                color: diff == null || diff.abs() < 0.01 ? null : (diff > 0 ? TK.bad : TK.good),
                hint: diff == null || diff.abs() < 0.01
                    ? null
                    : '${_signed(diff, formatMoney)}  ${_signed(diff / plan! * 100, (v) => '${v.toStringAsFixed(1)} %')}',
              ),
              col(
                'Цена продажи',
                l.salePrice == null ? '—' : formatMoney(l.salePrice!),
                hint: markup == null ? null : 'наценка ${markup.round()} %',
                color: markup != null && markup < 0 ? TK.bad : null,
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Всего ${_money(l.cost)}: сырьё ${_money(l.rawCost)}'
            '${l.laborCost != null ? ' + зарплата ${_money(l.laborCost!)}' : ''}'
            '${l.overheadCost != null && l.overheadCost! > 0 ? ' + накладные ${_money(l.overheadCost!)}' : ''}'
            ' · выпуск ${formatQty(l.quantity)} $unit',
            style: TK.num.copyWith(fontSize: 11.5, color: TK.muted),
          ),
        ],
      ),
    );
  }
}
