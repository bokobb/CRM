import 'package:flutter/material.dart';

import '../models/tech_card.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/tech_card_style.dart';
import 'tech_card_edit_screen.dart';
import '../theme/app_theme.dart';

/// Техкарта товара: паспорт, расчёт партии под план выпуска (сырьё и себестоимость по последним
/// закупочным ценам), рецептура, техпроцесс с ККТ, органолептика. Рецептура редактируется на
/// экране «Рецептура», остальное — здесь же через карандаш (только ADMIN).
///
/// Оформление по образцу-артефакту «Техкарты выпечки»: сверху таблетки-вкладки товаров (те же,
/// что видны в списке), под ними один лист с секциями через тонкие линии.
class TechCardScreen extends StatefulWidget {
  final List<TechCardSummary> cards;
  final int initialProductId;

  const TechCardScreen({super.key, required this.cards, required this.initialProductId});

  @override
  State<TechCardScreen> createState() => _TechCardScreenState();
}

class _TechCardScreenState extends State<TechCardScreen> {
  final _api = ApiClient();
  final _plan = TextEditingController();
  final _selectedTabKey = GlobalKey();
  late int _productId = widget.initialProductId;
  late Future<TechCard> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
    WidgetsBinding.instance.addPostFrameCallback((_) => _scrollTabIntoView());
  }

  @override
  void dispose() {
    _plan.dispose();
    super.dispose();
  }

  void _scrollTabIntoView() {
    final ctx = _selectedTabKey.currentContext;
    if (ctx != null) Scrollable.ensureVisible(ctx, alignment: 0.5, duration: const Duration(milliseconds: 250));
  }

  /// resetPlan — при переключении товара план выпуска сбрасывается на один замес нового товара.
  Future<TechCard> _load({bool resetPlan = true}) async {
    final card = await _api.getTechCard(_productId);
    if (resetPlan || _plan.text.isEmpty) _plan.text = formatQty(card.yieldQuantity);
    return card;
  }

  void _select(int productId) {
    if (productId == _productId) return;
    setState(() {
      _productId = productId;
      _future = _load();
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => _scrollTabIntoView());
  }

  Future<void> _refresh() async {
    setState(() => _future = _load(resetPlan: false));
    await _future;
  }

  Future<void> _edit(TechCard card) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => TechCardEditScreen(card: card)),
    );
    if (saved == true) _refresh();
  }

  double _parse(String s) => double.tryParse(s.replaceAll(RegExp(r'\s'), '').replaceAll(',', '.')) ?? 0;

  @override
  Widget build(BuildContext context) {
    // Править техкарту может админ и технолог (backend TechCardApiController.save).
    final isAdmin = const {'ADMIN', 'TECHNOLOGIST'}.contains(ApiClient.currentSession?.role);
    return Theme(
      data: TK.theme(Theme.of(context)),
      child: FutureBuilder<TechCard>(
        future: _future,
        builder: (context, snapshot) {
          final card = snapshot.connectionState == ConnectionState.done ? snapshot.data : null;
          return Scaffold(
            appBar: AppBar(
              title: const Text('Техкарта'),
              actions: [
                if (isAdmin && card != null)
                  IconButton(icon: const Icon(Icons.edit_outlined), tooltip: 'Изменить техкарту', onPressed: () => _edit(card)),
              ],
            ),
            body: Column(
              children: [
                if (widget.cards.length > 1) _tabs(),
                Expanded(
                  child: () {
                    if (snapshot.hasError) return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                    if (card == null) return const Center(child: CircularProgressIndicator());
                    return RefreshIndicator(onRefresh: _refresh, child: _body(card, isAdmin));
                  }(),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _tabs() => SizedBox(
        height: 50,
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
          child: Row(
            children: [
              for (final c in widget.cards) ...[
                TKPill(
                  key: c.productId == _productId ? _selectedTabKey : null,
                  text: c.name,
                  selected: c.productId == _productId,
                  onTap: () => _select(c.productId),
                ),
                const SizedBox(width: 6),
              ],
            ],
          ),
        ),
      );

  Widget _body(TechCard c, bool isAdmin) {
    final unit = c.unit ?? 'шт';
    final plan = _parse(_plan.text);
    final factor = c.yieldQuantity > 0 ? plan / c.yieldQuantity : 0.0;

    var cost = 0.0;
    var unpriced = 0;
    for (final i in c.items) {
      if (i.unitPrice == null) {
        unpriced++;
      } else {
        cost += i.quantity * factor * i.unitPrice!;
      }
    }
    final costPerUnit = plan > 0 ? cost / plan : 0.0;
    final markup = costPerUnit > 0 ? (c.salePrice - costPerUnit) / costPerUnit * 100 : null;

    double? outputKg;
    if (unit.toLowerCase() == 'кг') {
      outputKg = plan;
    } else if (c.netWeightGrams != null && c.netWeightGrams! > 0) {
      outputKg = plan * c.netWeightGrams! / 1000;
    }
    final perKg = outputKg != null && outputKg > 0 ? cost / outputKg : null;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 40),
      children: [
        Container(
          decoration: BoxDecoration(
            color: context.pal.card,
            border: Border.all(color: context.pal.line),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ---- Паспорт ----
              _Sec(first: true, children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          TKLabel('Технологическая карта · ${c.group ?? 'без группы'}'),
                          const SizedBox(height: 6),
                          Text(c.name, style: TK.h1),
                          if (c.draft) ...[
                            const SizedBox(height: 8),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                              decoration: BoxDecoration(color: context.pal.tint(context.pal.warn), borderRadius: BorderRadius.circular(6)),
                              child: Text(
                                'Черновик: этапы, режимы и сроки типовые, их нужно проверить технологу. '
                                'Пометка снимется после сохранения карты.',
                                style: TextStyle(fontFamily: TK.body, fontSize: 12, color: context.pal.warn),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    ProductAvatar(name: c.name, imageUrl: c.imageUrl, radius: 28),
                  ],
                ),
                const SizedBox(height: 4),
                _FieldGrid(fields: [
                  ('Выход по рецептуре', '${formatQty(c.yieldQuantity)} $unit'),
                  ('Масса 1 шт, г', c.netWeightGrams == null ? null : formatQty(c.netWeightGrams!)),
                  ('Срок годности', c.shelfLife),
                  ('Условия хранения', c.storageConditions),
                  ('Упаковка', c.packaging),
                ]),
              ]),

              // ---- Расчёт партии ----
              _Sec(children: [
                const _SecHead(title: 'Расчёт партии и себестоимость', note: 'Цены сырья — по последней закупке, ₸ за единицу'),
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(color: context.pal.seg, borderRadius: BorderRadius.circular(8)),
                  child: Wrap(
                    spacing: 20,
                    runSpacing: 10,
                    crossAxisAlignment: WrapCrossAlignment.end,
                    children: [
                      SizedBox(
                        width: 150,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            TKLabel('План выпуска, $unit'),
                            const SizedBox(height: 4),
                            TextField(
                              controller: _plan,
                              style: TK.num.copyWith(fontSize: 15),
                              keyboardType: const TextInputType.numberWithOptions(decimal: true),
                              decoration: InputDecoration(fillColor: context.pal.card),
                              onChanged: (_) => setState(() {}),
                            ),
                          ],
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: Text(
                          'Коэффициент пересчёта ×${formatQty(_round(factor, 2))} · ${formatQty(_round(factor, 2))} замес.',
                          style: TK.note.copyWith(color: context.pal.muted),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 14),
                _StatGrid(stats: [
                  _Stat(
                    'Выход партии',
                    outputKg != null ? '${formatQty(_round(outputKg, 1))} кг' : '${formatQty(plan)} $unit',
                    c.netWeightGrams != null && unit.toLowerCase() != 'кг'
                        ? '${formatQty(plan)} $unit × ${formatQty(c.netWeightGrams!)} г'
                        : 'по плану выпуска',
                  ),
                  _Stat(
                    'Сырьё на партию',
                    '${formatQty(_round(cost, 0))} ₸',
                    unpriced > 0 ? 'без цены: $unpriced поз. — сумма занижена' : 'без труда и энергии',
                    warn: unpriced > 0,
                  ),
                  _Stat(
                    'Себестоимость 1 $unit',
                    '${formatQty(_round(costPerUnit, 1))} ₸',
                    perKg != null ? '${formatQty(_round(perKg, 0))} ₸ за 1 кг' : 'только сырьё',
                  ),
                  _Stat(
                    'Цена продажи 1 $unit',
                    '${formatQty(c.salePrice)} ₸',
                    markup == null ? 'из карточки товара' : 'наценка ${formatQty(_round(markup, 0))} %',
                    highlight: true,
                    warn: markup != null && markup < 0,
                  ),
                ]),
              ]),

              // ---- Рецептура ----
              _Sec(children: [
                _SecHead(title: 'Рецептура', note: 'На партию — под план выпуска; на замес — на ${formatQty(c.yieldQuantity)} $unit. Таблица листается вбок'),
                if (c.items.isEmpty)
                  Text('Рецептура пуста — заполните её на экране «Рецептура»', style: TK.note.copyWith(color: context.pal.muted))
                else
                  _RecipeTable(items: c.items, factor: factor, totalCost: cost),
              ]),

              // ---- Техпроцесс ----
              _Sec(children: [
                _SecHead(
                  title: 'Технологический процесс',
                  noteWidget: Text.rich(
                    TextSpan(children: [
                      WidgetSpan(alignment: PlaceholderAlignment.middle, child: TKCcp()),
                      TextSpan(text: '  — критическая контрольная точка, фиксируется в журнале'),
                    ]),
                    style: TK.note.copyWith(color: context.pal.muted),
                  ),
                ),
                if (c.stages.isEmpty)
                  Row(
                    children: [
                      Expanded(child: Text('Этапы ещё не заполнены', style: TK.note.copyWith(color: context.pal.muted))),
                      if (isAdmin)
                        OutlinedButton(
                          style: OutlinedButton.styleFrom(
                            foregroundColor: context.pal.ink,
                            side: BorderSide(color: context.pal.line),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
                          ),
                          onPressed: () => _edit(c),
                          child: const Text('+ Добавить этап'),
                        ),
                    ],
                  ),
                for (var n = 0; n < c.stages.length; n++)
                  _StageRow(number: n + 1, stage: c.stages[n], last: n == c.stages.length - 1),
              ]),

              // ---- Качество ----
              _Sec(children: [
                const Text('Органолептические показатели', style: TK.h2),
                const SizedBox(height: 12),
                _Dl(rows: [
                  ('Внешний вид', c.qualityLook),
                  ('Цвет', c.qualityColor),
                  ('Мякиш / излом', c.qualityCrumb),
                  ('Вкус и запах', c.qualityTaste),
                ]),
                if (c.netWeightGrams != null) ...[
                  const SizedBox(height: 20),
                  const Text('Требования к выпуску', style: TK.h2),
                  const SizedBox(height: 12),
                  _Dl(rows: [
                    (
                      'Отклонение массы',
                      '± 3 % от ${formatQty(c.netWeightGrams!)} г '
                          '(${formatQty(_round(c.netWeightGrams! * 0.97, 1))}–${formatQty(_round(c.netWeightGrams! * 1.03, 1))} г)'
                    ),
                  ]),
                ],
              ]),

              _Sec(children: [
                Text('Составил: __________   Утвердил: __________   Дата: ${formatDate(DateTime.now())}', style: TK.note.copyWith(color: context.pal.muted)),
              ]),
            ],
          ),
        ),
      ],
    );
  }
}

double _round(double v, int digits) {
  final m = [1, 10, 100, 1000][digits];
  return (v * m).roundToDouble() / m;
}

/// Секция листа: отступ 20 и линия сверху (кроме первой) — `.sec` в образце.
class _Sec extends StatelessWidget {
  final bool first;
  final List<Widget> children;

  const _Sec({this.first = false, required this.children});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(border: first ? null : Border(top: BorderSide(color: context.pal.line))),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
      );
}

class _SecHead extends StatelessWidget {
  final String title;
  final String? note;
  final Widget? noteWidget;

  const _SecHead({required this.title, this.note, this.noteWidget});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: TK.h2),
            if (note != null) ...[const SizedBox(height: 4), Text(note!, style: TK.note.copyWith(color: context.pal.muted))],
            if (noteWidget != null) ...[const SizedBox(height: 6), noteWidget!],
          ],
        ),
      );
}

/// Сетка полей паспорта: подпись капсом + значение на светлой подложке (`.fields` в образце).
class _FieldGrid extends StatelessWidget {
  final List<(String, String?)> fields;

  const _FieldGrid({required this.fields});

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, constraints) {
        final cols = constraints.maxWidth > 480 ? 3 : 2;
        final w = (constraints.maxWidth - 14 * (cols - 1)) / cols;
        return Wrap(
          spacing: 14,
          runSpacing: 12,
          children: [
            for (final (label, value) in fields)
              SizedBox(
                width: label == 'Условия хранения' || label == 'Упаковка' ? constraints.maxWidth : w,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const SizedBox(height: 10),
                    TKLabel(label),
                    const SizedBox(height: 4),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
                      decoration: BoxDecoration(color: context.pal.seg, borderRadius: BorderRadius.circular(6)),
                      child: Text(value ?? '—', style: TK.text.copyWith(color: value == null ? context.pal.muted : context.pal.ink)),
                    ),
                  ],
                ),
              ),
          ],
        );
      });
}

class _Stat {
  final String label;
  final String value;
  final String hint;
  final bool highlight;
  final bool warn;

  _Stat(this.label, this.value, this.hint, {this.highlight = false, this.warn = false});
}

/// Плитки показателей с линиями толщиной 1 px между ними (`.stats` в образце).
class _StatGrid extends StatelessWidget {
  final List<_Stat> stats;

  const _StatGrid({required this.stats});

  @override
  Widget build(BuildContext context) {
    Widget tile(_Stat s) => Container(
          color: context.pal.card,
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TKLabel(s.label),
              const SizedBox(height: 4),
              Text(
                s.value,
                style: TK.num.copyWith(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: s.highlight ? context.pal.accent : context.pal.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(s.hint, style: TK.note.copyWith(color: s.warn ? context.pal.danger : context.pal.muted)),
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
            Expanded(child: i + 1 < stats.length ? tile(stats[i + 1]) : Container(color: context.pal.card)),
          ],
        ),
      ));
      if (i + 2 < stats.length) rows.add(const SizedBox(height: 1));
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Container(
        color: context.pal.line,
        foregroundDecoration: BoxDecoration(
          border: Border.all(color: context.pal.line),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(children: rows),
      ),
    );
  }
}

/// Таблица рецептуры с итогом (`table` в образце); на узком экране прокручивается по горизонтали.
class _RecipeTable extends StatelessWidget {
  final List<TechCardItem> items;
  final double factor;
  final double totalCost;

  const _RecipeTable({required this.items, required this.factor, required this.totalCost});

  static TextStyle _head(BuildContext context) => TextStyle(fontFamily: TK.body, fontSize: 11, fontWeight: FontWeight.w600, color: context.pal.muted, letterSpacing: 0.6);

  Widget _cell(String text, {bool right = false, TextStyle? style, Color? bg}) => Container(
        color: bg,
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 9),
        alignment: right ? Alignment.centerRight : Alignment.centerLeft,
        child: Text(text, style: style ?? (right ? TK.num : TK.text), textAlign: right ? TextAlign.right : TextAlign.left),
      );

  @override
  Widget build(BuildContext context) {
    final border = BorderSide(color: context.pal.line);
    final missing = TK.num.copyWith(color: context.pal.danger, fontFamily: TK.body, fontSize: 12.5);
    final bold = TK.num.copyWith(fontWeight: FontWeight.w600);

    final rows = <TableRow>[
      TableRow(
        decoration: BoxDecoration(color: context.pal.seg, border: Border(bottom: border)),
        children: [
          _cell('СЫРЬЁ', style: _head(context)),
          _cell('НА ПАРТИЮ', right: true, style: _head(context)),
          _cell('СУММА, ₸', right: true, style: _head(context)),
          _cell('ДОЛЯ, %', right: true, style: _head(context)),
          _cell('НА ЗАМЕС', right: true, style: _head(context)),
          _cell('ЦЕНА, ₸/ЕД', right: true, style: _head(context)),
        ],
      ),
    ];
    for (final i in items) {
      final perBatch = i.quantity * factor;
      final sum = i.unitPrice == null ? null : perBatch * i.unitPrice!;
      rows.add(TableRow(
        decoration: BoxDecoration(border: Border(bottom: border)),
        children: [
          _cell(i.name),
          _cell('${formatQty(_round(perBatch, 3))} ${i.unit}', right: true),
          sum == null ? _cell('нет цены', right: true, style: missing) : _cell(formatQty(_round(sum, 0)), right: true),
          _cell(sum == null || totalCost <= 0 ? '—' : formatQty(_round(sum / totalCost * 100, 1)), right: true),
          _cell('${formatQty(_round(i.quantity, 3))} ${i.unit}', right: true),
          _cell(i.unitPrice == null ? '—' : formatQty(i.unitPrice!), right: true),
        ],
      ));
    }
    rows.add(TableRow(
      decoration: BoxDecoration(color: context.pal.seg),
      children: [
        _cell('Итого', style: TK.text.copyWith(fontWeight: FontWeight.w600)),
        _cell(''),
        _cell(formatQty(_round(totalCost, 0)), right: true, style: bold),
        _cell(totalCost > 0 ? '100' : '—', right: true, style: bold),
        _cell(''),
        _cell(''),
      ],
    ));

    return Container(
      decoration: BoxDecoration(border: Border.all(color: context.pal.line), borderRadius: BorderRadius.circular(8)),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(7),
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minWidth: 560),
            child: Table(
              defaultColumnWidth: const IntrinsicColumnWidth(),
              columnWidths: const {0: FixedColumnWidth(128)},
              defaultVerticalAlignment: TableCellVerticalAlignment.middle,
              children: rows,
            ),
          ),
        ),
      ),
    );
  }
}

/// Этап техпроцесса: номер в кружке (жёлтый у ККТ), название, описание, таблетки t° и времени.
class _StageRow extends StatelessWidget {
  final int number;
  final TechCardStage stage;
  final bool last;

  const _StageRow({required this.number, required this.stage, required this.last});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12),
      decoration: BoxDecoration(border: last ? null : Border(bottom: BorderSide(color: context.pal.line))),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: stage.ccp ? context.pal.warn : context.pal.seg, shape: BoxShape.circle),
            child: Text(
              '$number',
              style: TK.num.copyWith(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: stage.ccp ? const Color(0xFF3A2800) : context.pal.muted,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(stage.title, style: TK.text.copyWith(fontWeight: FontWeight.w600, fontSize: 15)),
                if (stage.description != null) ...[
                  const SizedBox(height: 3),
                  Text(stage.description!, style: TK.text.copyWith(color: context.pal.muted, height: 1.4)),
                ],
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    _ParamChip(label: 't°', value: stage.temperature ?? '—'),
                    _ParamChip(label: 'время', value: stage.duration ?? '—'),
                  ],
                ),
              ],
            ),
          ),
          if (stage.ccp) ...[const SizedBox(width: 8), const TKCcp()],
        ],
      ),
    );
  }
}

class _ParamChip extends StatelessWidget {
  final String label;
  final String value;

  const _ParamChip({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(10, 4, 10, 4),
        decoration: BoxDecoration(color: context.pal.seg, borderRadius: BorderRadius.circular(999)),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(label, style: TK.note.copyWith(color: context.pal.muted)),
            const SizedBox(width: 6),
            Text(value, style: TK.num.copyWith(fontSize: 12)),
          ],
        ),
      );
}

/// Список «термин — описание» (`dl.q` в образце).
class _Dl extends StatelessWidget {
  final List<(String, String?)> rows;

  const _Dl({required this.rows});

  @override
  Widget build(BuildContext context) => Column(
        children: [
          for (final (term, desc) in rows)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: 120, child: Text(term, style: TK.text.copyWith(color: context.pal.muted))),
                  const SizedBox(width: 12),
                  Expanded(child: Text(desc ?? '—', style: TK.text)),
                ],
              ),
            ),
        ],
      );
}
