import 'package:flutter/material.dart';

import '../models/tech_card.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/tech_card_style.dart';
import 'tech_card_screen.dart';

/// «Производство» — техкарты товаров: все товары с рецептурой, фильтр по группе товара
/// (Product.group) таблетками. Карточка ведёт на полную техкарту (рецептура, расчёт партии,
/// себестоимость, этапы и ККТ, органолептика). Раньше здесь была доска заявок на сырьё по статусам
/// Ожидает/В работе/Готово — она дублировала экран «Заявки на сырьё» и убрана.
/// Оформление — по образцу-артефакту «Техкарты выпечки», см. tech_card_style.dart.
class ProductionScreen extends StatefulWidget {
  const ProductionScreen({super.key});

  @override
  State<ProductionScreen> createState() => _ProductionScreenState();
}

const _noGroup = 'Без группы';

class _ProductionScreenState extends State<ProductionScreen> {
  final _api = ApiClient();
  late Future<List<TechCardSummary>> _future;
  String _query = '';
  String? _group;

  @override
  void initState() {
    super.initState();
    _future = _api.getTechCards();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getTechCards());
    await _future;
  }

  Future<void> _open(List<TechCardSummary> visible, TechCardSummary c) async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => TechCardScreen(cards: visible, initialProductId: c.productId)),
    );
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: TK.theme(Theme.of(context)),
      child: Scaffold(
        appBar: AppBar(title: const Text('Производство')),
        drawer: const AppDrawer(currentRoute: '/production'),
        body: RefreshIndicator(
          onRefresh: _refresh,
          child: FutureBuilder<List<TechCardSummary>>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.connectionState == ConnectionState.waiting) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.hasError) {
                return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
              }
              final all = snapshot.data!;
              final groups = {for (final c in all) c.group ?? _noGroup}.toList()
                ..sort((a, b) {
                  if (a == _noGroup) return 1;
                  if (b == _noGroup) return -1;
                  return a.toLowerCase().compareTo(b.toLowerCase());
                });
              final cards = all
                  .where((c) => _group == null || (c.group ?? _noGroup) == _group)
                  .where((c) => _query.isEmpty || c.name.toLowerCase().contains(_query))
                  .toList();

              return ListView(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
                children: [
                  const Text('Техкарты', style: TK.h1),
                  const SizedBox(height: 4),
                  const Text(
                    'Полный цикл приготовления: рецептура, расчёт партии, себестоимость, этапы и контрольные точки.',
                    style: TextStyle(fontFamily: TK.body, fontSize: 13, color: TK.muted),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    decoration: const InputDecoration(
                      hintText: 'Поиск по названию…',
                      prefixIcon: Icon(Icons.search, color: TK.muted),
                      fillColor: TK.paper,
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.all(Radius.circular(8)),
                        borderSide: BorderSide(color: TK.line),
                      ),
                    ),
                    onChanged: (v) => setState(() => _query = v.toLowerCase()),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    height: 38,
                    child: ListView(
                      scrollDirection: Axis.horizontal,
                      children: [
                        TKPill(text: 'Все', selected: _group == null, onTap: () => setState(() => _group = null)),
                        for (final g in groups) ...[
                          const SizedBox(width: 6),
                          TKPill(text: g, selected: _group == g, onTap: () => setState(() => _group = g)),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  if (cards.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(32),
                      child: Center(
                        child: Text(all.isEmpty ? 'Нет товаров с рецептурой' : 'Ничего не найдено', style: TK.note),
                      ),
                    ),
                  for (final c in cards) _TechCardTile(card: c, onTap: () => _open(cards, c)),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

class _TechCardTile extends StatelessWidget {
  final TechCardSummary card;
  final VoidCallback onTap;

  const _TechCardTile({required this.card, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final unit = card.unit ?? 'шт';
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: TK.paper,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
          side: const BorderSide(color: TK.line),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                ProductAvatar(name: card.name, imageUrl: card.imageUrl, radius: 26),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      TKLabel([card.group ?? _noGroup, if (card.semiFinished) 'полуфабрикат'].join(' · ')),
                      const SizedBox(height: 3),
                      Text(
                        card.name,
                        style: const TextStyle(fontFamily: TK.display, fontSize: 14, fontWeight: FontWeight.w700, color: TK.ink, height: 1.25),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        '${formatQty(card.yieldQuantity)} $unit/замес · ${card.ingredientCount} сырья',
                        style: TK.num.copyWith(fontSize: 12, color: TK.muted),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 6,
                        runSpacing: 4,
                        children: card.stageCount == 0
                            ? [const _Tag(text: 'техпроцесс не заполнен', dashed: true)]
                            : [
                                _Tag(text: '${card.stageCount} этап.'),
                                if (card.ccpCount > 0) _Tag(text: '${card.ccpCount} ККТ', ccp: true),
                              ],
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right, color: TK.muted),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  final String text;
  final bool ccp;
  final bool dashed;

  const _Tag({required this.text, this.ccp = false, this.dashed = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(
        color: ccp ? TK.yolkBg : (dashed ? Colors.transparent : TK.soft),
        border: ccp ? Border.all(color: TK.yolk) : (dashed ? Border.all(color: TK.line) : null),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontFamily: TK.body,
          fontSize: 11.5,
          fontWeight: FontWeight.w600,
          color: ccp ? TK.yolkInk : TK.muted,
        ),
      ),
    );
  }
}
