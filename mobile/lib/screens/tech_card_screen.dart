import 'package:flutter/material.dart';

import '../models/tech_card.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import 'tech_card_edit_screen.dart';

/// Техкарта товара: паспорт, расчёт партии под план выпуска (сырьё и себестоимость по последним
/// закупочным ценам), рецептура, техпроцесс с ККТ, органолептика. Рецептура редактируется на
/// экране «Рецептура», остальное — здесь же через карандаш (только ADMIN).
class TechCardScreen extends StatefulWidget {
  final int productId;
  final String title;

  const TechCardScreen({super.key, required this.productId, required this.title});

  @override
  State<TechCardScreen> createState() => _TechCardScreenState();
}

class _TechCardScreenState extends State<TechCardScreen> {
  final _api = ApiClient();
  late Future<TechCard> _future;
  final _plan = TextEditingController();

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  @override
  void dispose() {
    _plan.dispose();
    super.dispose();
  }

  Future<TechCard> _load() async {
    final card = await _api.getTechCard(widget.productId);
    if (_plan.text.isEmpty) _plan.text = formatQty(card.yieldQuantity);
    return card;
  }

  Future<void> _refresh() async {
    setState(() => _future = _load());
    await _future;
  }

  Future<void> _edit(TechCard card) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => TechCardEditScreen(card: card)),
    );
    if (saved == true) _refresh();
  }

  double _parse(String s) => double.tryParse(s.replaceAll(' ', '').replaceAll(',', '.')) ?? 0;

  @override
  Widget build(BuildContext context) {
    final isAdmin = ApiClient.currentSession?.role == 'ADMIN';
    return FutureBuilder<TechCard>(
      future: _future,
      builder: (context, snapshot) {
        final card = snapshot.data;
        return Scaffold(
          appBar: AppBar(
            title: Text(widget.title),
            actions: [
              if (isAdmin && card != null)
                IconButton(icon: const Icon(Icons.edit), tooltip: 'Изменить техкарту', onPressed: () => _edit(card)),
            ],
          ),
          body: () {
            if (snapshot.connectionState == ConnectionState.waiting && card == null) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            return RefreshIndicator(onRefresh: _refresh, child: _body(card!, isAdmin));
          }(),
        );
      },
    );
  }

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

    String? outputKg;
    final u = unit.toLowerCase();
    if (u == 'кг') {
      outputKg = '${formatQty(_round(plan, 2))} кг';
    } else if (c.netWeightGrams != null && c.netWeightGrams! > 0) {
      outputKg = '${formatQty(_round(plan * c.netWeightGrams! / 1000, 2))} кг';
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 32),
      children: [
        _Section(children: [
          Row(
            children: [
              ProductAvatar(name: c.name, imageUrl: c.imageUrl, radius: 32),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const _Label('Технологическая карта'),
                    const SizedBox(height: 2),
                    Text(c.name, style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
                    if (c.group != null) Text(c.group!, style: const TextStyle(color: Colors.grey)),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          _Fields(pairs: [
            ('Выход по рецептуре', '${formatQty(c.yieldQuantity)} $unit на замес'),
            if (c.netWeightGrams != null) ('Масса 1 шт', '${formatQty(c.netWeightGrams!)} г'),
            ('Срок годности', c.shelfLife),
            ('Условия хранения', c.storageConditions),
            ('Упаковка', c.packaging),
          ]),
        ]),
        _Section(
          title: 'Расчёт партии и себестоимость',
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                SizedBox(
                  width: 150,
                  child: TextField(
                    controller: _plan,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: InputDecoration(labelText: 'План выпуска, $unit', isDense: true),
                    onChanged: (_) => setState(() {}),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    '= ${formatQty(_round(factor, 2))} замес.',
                    style: const TextStyle(color: Colors.grey),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _Stats(stats: [
              _Stat('Выход партии', outputKg ?? '${formatQty(plan)} $unit',
                  outputKg != null ? '${formatQty(plan)} $unit' : null),
              _Stat('Сырьё на партию', formatMoney(_round(cost, 0)),
                  unpriced > 0 ? 'без $unpriced поз. без цены' : 'без труда и энергии',
                  warn: unpriced > 0),
              _Stat('Себестоимость 1 $unit', formatMoney(_round(costPerUnit, 1)), null),
              _Stat('Цена продажи', formatMoney(c.salePrice),
                  markup == null ? null : 'наценка ${formatQty(_round(markup, 0))} %',
                  highlight: true, warn: markup != null && markup < 0),
            ]),
            const SizedBox(height: 6),
            const Text('Цены сырья — по последней закупке', style: TextStyle(color: Colors.grey, fontSize: 12)),
          ],
        ),
        _Section(
          title: 'Рецептура',
          subtitle: 'на замес (${formatQty(c.yieldQuantity)} $unit) → на партию',
          children: [
            if (c.items.isEmpty) const Text('Рецептура пуста', style: TextStyle(color: Colors.grey)),
            for (final i in c.items) _IngredientRow(item: i, factor: factor, totalCost: cost),
          ],
        ),
        _Section(
          title: 'Технологический процесс',
          trailing: const _CcpBadge(),
          children: [
            if (c.stages.isEmpty)
              Row(
                children: [
                  const Expanded(child: Text('Этапы ещё не заполнены', style: TextStyle(color: Colors.grey))),
                  if (isAdmin) TextButton(onPressed: () => _edit(c), child: const Text('Заполнить')),
                ],
              ),
            for (var n = 0; n < c.stages.length; n++) _StageRow(number: n + 1, stage: c.stages[n], last: n == c.stages.length - 1),
            if (c.stages.any((s) => s.ccp))
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('ККТ — критическая контрольная точка, фиксируется в журнале',
                    style: TextStyle(color: Colors.grey, fontSize: 12)),
              ),
          ],
        ),
        _Section(title: 'Органолептические показатели', children: [
          _Fields(pairs: [
            ('Внешний вид', c.qualityLook),
            ('Цвет', c.qualityColor),
            ('Мякиш / излом', c.qualityCrumb),
            ('Вкус и запах', c.qualityTaste),
          ]),
        ]),
        if (c.netWeightGrams != null)
          _Section(title: 'Требования к выпуску', children: [
            _Fields(pairs: [
              (
                'Отклонение массы',
                '± 3 % от ${formatQty(c.netWeightGrams!)} г '
                    '(${formatQty(_round(c.netWeightGrams! * 0.97, 1))}–${formatQty(_round(c.netWeightGrams! * 1.03, 1))} г)'
              ),
            ]),
          ]),
      ],
    );
  }
}

double _round(double v, int digits) {
  final m = [1, 10, 100, 1000][digits];
  return (v * m).roundToDouble() / m;
}

class _Label extends StatelessWidget {
  final String text;

  const _Label(this.text);

  @override
  Widget build(BuildContext context) => Text(
        text.toUpperCase(),
        style: TextStyle(fontSize: 11, letterSpacing: 0.8, fontWeight: FontWeight.w600, color: Colors.grey.shade600),
      );
}

class _Section extends StatelessWidget {
  final String? title;
  final String? subtitle;
  final Widget? trailing;
  final List<Widget> children;

  const _Section({this.title, this.subtitle, this.trailing, required this.children});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (title != null) ...[
              Row(
                children: [
                  Expanded(
                    child: Text(title!, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                  ),
                  ?trailing,
                ],
              ),
              if (subtitle != null) Text(subtitle!, style: const TextStyle(color: Colors.grey, fontSize: 12.5)),
              const SizedBox(height: 12),
            ],
            ...children,
          ],
        ),
      ),
    );
  }
}

/// Пары «подпись — значение»; пустые значения показываются прочерком.
class _Fields extends StatelessWidget {
  final List<(String, String?)> pairs;

  const _Fields({required this.pairs});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        for (final (label, value) in pairs)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(width: 130, child: Text(label, style: const TextStyle(color: Colors.grey))),
                Expanded(child: Text(value ?? '—')),
              ],
            ),
          ),
      ],
    );
  }
}

class _Stat {
  final String label;
  final String value;
  final String? hint;
  final bool highlight;
  final bool warn;

  _Stat(this.label, this.value, this.hint, {this.highlight = false, this.warn = false});
}

class _Stats extends StatelessWidget {
  final List<_Stat> stats;

  const _Stats({required this.stats});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(builder: (context, constraints) {
      final width = (constraints.maxWidth - 8) / 2;
      return Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final s in stats)
            Container(
              width: width,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHighest.withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _Label(s.label),
                  const SizedBox(height: 2),
                  Text(
                    s.value,
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                      fontFeatures: const [FontFeature.tabularFigures()],
                      color: s.highlight ? scheme.primary : null,
                    ),
                  ),
                  if (s.hint != null)
                    Text(s.hint!,
                        style: TextStyle(fontSize: 12, color: s.warn ? Colors.red.shade700 : Colors.grey)),
                ],
              ),
            ),
        ],
      );
    });
  }
}

class _IngredientRow extends StatelessWidget {
  final TechCardItem item;
  final double factor;
  final double totalCost;

  const _IngredientRow({required this.item, required this.factor, required this.totalCost});

  @override
  Widget build(BuildContext context) {
    final perBatch = item.quantity * factor;
    final sum = item.unitPrice == null ? null : perBatch * item.unitPrice!;
    final share = sum != null && totalCost > 0 ? sum / totalCost * 100 : null;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: Colors.grey.shade200))),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.name, style: const TextStyle(fontWeight: FontWeight.w500)),
                const SizedBox(height: 2),
                Text(
                  '${formatQty(_round(item.quantity, 3))} → ${formatQty(_round(perBatch, 3))} ${item.unit}',
                  style: const TextStyle(color: Colors.grey, fontSize: 12.5, fontFeatures: [FontFeature.tabularFigures()]),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                sum == null ? 'нет цены' : formatMoney(_round(sum, 0)),
                style: TextStyle(
                  fontWeight: FontWeight.w600,
                  color: sum == null ? Colors.orange.shade800 : null,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
              if (item.unitPrice != null)
                Text(
                  '${formatMoney(item.unitPrice!)}/${item.unit}'
                  '${share != null ? ' · ${formatQty(_round(share, 1))} %' : ''}',
                  style: const TextStyle(color: Colors.grey, fontSize: 12),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _CcpBadge extends StatelessWidget {
  const _CcpBadge();

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: Colors.amber.shade100,
          border: Border.all(color: Colors.amber.shade600),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text('ККТ', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.brown.shade800)),
      );
}

class _StageRow extends StatelessWidget {
  final int number;
  final TechCardStage stage;
  final bool last;

  const _StageRow({required this.number, required this.stage, required this.last});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: BoxDecoration(
        border: last ? null : Border(bottom: BorderSide(color: Colors.grey.shade200)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(
            radius: 14,
            backgroundColor: stage.ccp ? Colors.amber.shade600 : Colors.grey.shade200,
            child: Text('$number',
                style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    color: stage.ccp ? Colors.brown.shade900 : Colors.grey.shade700)),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(child: Text(stage.title, style: const TextStyle(fontWeight: FontWeight.w600))),
                    if (stage.ccp) const _CcpBadge(),
                  ],
                ),
                if (stage.description != null) ...[
                  const SizedBox(height: 2),
                  Text(stage.description!, style: TextStyle(color: Colors.grey.shade700, fontSize: 13.5)),
                ],
                if (stage.temperature != null || stage.duration != null) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      if (stage.temperature != null) _Chip(icon: Icons.thermostat, text: stage.temperature!),
                      if (stage.duration != null) _Chip(icon: Icons.schedule, text: stage.duration!),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  final IconData icon;
  final String text;

  const _Chip({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 14, color: Colors.grey.shade700),
            const SizedBox(width: 4),
            Text(text, style: TextStyle(fontSize: 12, color: Colors.grey.shade800)),
          ],
        ),
      );
}
