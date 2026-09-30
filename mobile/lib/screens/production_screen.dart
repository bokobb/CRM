import 'package:flutter/material.dart';

import '../models/tech_card.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/searchable_app_bar.dart';
import 'tech_card_screen.dart';

/// «Производство» — техкарты товаров: все товары с рецептурой, сгруппированные по группе товара
/// (Product.group). Карточка ведёт на полную техкарту (рецептура, расчёт партии, себестоимость,
/// этапы и ККТ, органолептика). Раньше здесь была доска заявок на сырьё по статусам
/// Ожидает/В работе/Готово — она дублировала экран «Заявки на сырьё» и убрана.
class ProductionScreen extends StatefulWidget {
  const ProductionScreen({super.key});

  @override
  State<ProductionScreen> createState() => _ProductionScreenState();
}

class _ProductionScreenState extends State<ProductionScreen> {
  final _api = ApiClient();
  late Future<List<TechCardSummary>> _future;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _future = _api.getTechCards();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getTechCards());
    await _future;
  }

  Future<void> _open(TechCardSummary c) async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => TechCardScreen(productId: c.productId, title: c.name)),
    );
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Производство',
        hintText: 'Название товара...',
        onQueryChanged: (v) => setState(() => _query = v.toLowerCase()),
      ),
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
            final cards = snapshot.data!
                .where((c) => _query.isEmpty ||
                    c.name.toLowerCase().contains(_query) ||
                    (c.group ?? '').toLowerCase().contains(_query))
                .toList();
            if (cards.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(
                      _query.isEmpty ? 'Нет товаров с рецептурой' : 'Ничего не найдено',
                      style: const TextStyle(color: Colors.grey),
                    ),
                  ),
                ),
              ]);
            }

            final groups = <String, List<TechCardSummary>>{};
            for (final c in cards) {
              groups.putIfAbsent(c.group ?? 'Без группы', () => []).add(c);
            }
            final groupNames = groups.keys.toList()
              ..sort((a, b) {
                if (a == 'Без группы') return 1;
                if (b == 'Без группы') return -1;
                return a.toLowerCase().compareTo(b.toLowerCase());
              });

            return ListView(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
              children: [
                for (final g in groupNames) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(4, 12, 4, 6),
                    child: Text(
                      '$g (${groups[g]!.length})',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Colors.grey.shade700),
                    ),
                  ),
                  for (final c in groups[g]!) _TechCardTile(card: c, onTap: () => _open(c)),
                ],
              ],
            );
          },
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
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              ProductAvatar(name: card.name, imageUrl: card.imageUrl, radius: 26),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(card.name, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                    const SizedBox(height: 2),
                    Text(
                      [
                        if (card.semiFinished) 'полуфабрикат',
                        'выход ${formatQty(card.yieldQuantity)} $unit/замес',
                        '${card.ingredientCount} вид. сырья',
                      ].join(' · '),
                      style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                    ),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: card.stageCount == 0
                          ? [const _Tag(text: 'техпроцесс не заполнен', muted: true)]
                          : [
                              _Tag(text: '${card.stageCount} этап.'),
                              if (card.ccpCount > 0) _Tag(text: '${card.ccpCount} ККТ', ccp: true),
                            ],
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: Colors.grey),
            ],
          ),
        ),
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  final String text;
  final bool ccp;
  final bool muted;

  const _Tag({required this.text, this.ccp = false, this.muted = false});

  @override
  Widget build(BuildContext context) {
    final bg = ccp ? Colors.amber.shade100 : Theme.of(context).colorScheme.surfaceContainerHighest;
    final fg = ccp ? Colors.brown.shade800 : (muted ? Colors.grey.shade600 : Colors.grey.shade800);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Text(text, style: TextStyle(fontSize: 11.5, color: fg, fontWeight: FontWeight.w500)),
    );
  }
}
