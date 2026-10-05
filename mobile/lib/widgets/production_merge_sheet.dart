import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';
import 'error_view.dart';
import 'format.dart';

/// Слияние рецептур нескольких заявок (иконка слияния на экране «Заявки на сырьё»):
/// сырьё всех выбранных заявок складывается в одну рецептуру — предварительный просмотр, в
/// заявках ничего не меняется. По каждому сырью — итог, из какой заявки сколько и хватает ли
/// на складе на общий итог (а не на каждую заявку по отдельности).
Future<void> showProductionMergePreview(BuildContext context, List<int> requestIds) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (_) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.9,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      builder: (context, controller) => _MergeBody(requestIds: requestIds, controller: controller),
    ),
  );
}

class _MergedIngredient {
  final String name;
  final String unit;
  double total = 0;
  double available = 0;
  final Map<int, double> byRequest = {};

  _MergedIngredient(this.name, this.unit);

  bool get insufficient => available < total - 1e-9;
}

class _MergeBody extends StatefulWidget {
  final List<int> requestIds;
  final ScrollController controller;

  const _MergeBody({required this.requestIds, required this.controller});

  @override
  State<_MergeBody> createState() => _MergeBodyState();
}

class _MergeBodyState extends State<_MergeBody> {
  final _api = ApiClient();
  late Future<List<ProductionRequest>> _future = _load();

  Future<List<ProductionRequest>> _load() => Future.wait(widget.requestIds.map(_api.getProductionRequest));

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    return FutureBuilder<List<ProductionRequest>>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return ErrorView(error: snapshot.error.toString(), onRetry: () => setState(() => _future = _load()));
        }
        final requests = snapshot.data!;
        // Сырьё — по ingredientId, в порядке первого появления (как в составе заявок).
        final merged = <int, _MergedIngredient>{};
        for (final r in requests) {
          for (final it in r.items) {
            final m = merged.putIfAbsent(it.ingredientId, () => _MergedIngredient(it.ingredientName, it.ingredientUnit));
            m.total += it.requiredQuantity;
            m.available = it.availableQuantity;
            m.byRequest[r.id] = (m.byRequest[r.id] ?? 0) + it.requiredQuantity;
          }
        }
        // Продукция — тоже складывается, если один товар есть в нескольких заявках.
        final products = <String, double>{};
        for (final r in requests) {
          for (final l in r.lines) {
            products[l.productName] = (products[l.productName] ?? 0) + l.batches;
          }
        }
        final shortCount = merged.values.where((m) => m.insufficient).length;
        final shortName = {for (final r in requests) r.id: r.title};

        return ListView(
          controller: widget.controller,
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 14),
                decoration: BoxDecoration(color: p.line, borderRadius: BorderRadius.circular(2)),
              ),
            ),
            Row(
              children: [
                Icon(Icons.call_merge, color: p.accent),
                const SizedBox(width: 8),
                const Expanded(
                  child: Text('Слияние рецептур', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w600)),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Предварительный просмотр — в заявках ничего не меняется.',
              style: TextStyle(fontSize: 12.5, color: p.muted),
            ),
            const SizedBox(height: 14),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final r in requests)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(color: p.seg, borderRadius: BorderRadius.circular(20)),
                    child: Text('№${r.id} · ${r.title}', style: const TextStyle(fontSize: 13)),
                  ),
              ],
            ),
            const SizedBox(height: 18),
            _header(p, 'Продукция'),
            Card(
              margin: EdgeInsets.zero,
              child: Column(
                children: [
                  for (final e in products.entries)
                    ListTile(
                      dense: true,
                      title: Text(e.key),
                      trailing: Text(
                        '${formatQty(e.value)} замес.',
                        style: const TextStyle(fontWeight: FontWeight.w500, fontFeatures: [FontFeature.tabularFigures()]),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            Row(
              children: [
                Expanded(child: _header(p, 'Общая рецептура (сырьё)')),
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    shortCount == 0 ? 'всего хватает' : 'не хватает: $shortCount',
                    style: TextStyle(fontSize: 12.5, color: shortCount == 0 ? p.success : p.danger),
                  ),
                ),
              ],
            ),
            Card(
              margin: EdgeInsets.zero,
              child: Column(
                children: [
                  for (final (i, m) in merged.values.indexed) ...[
                    if (i > 0) Divider(height: 1, indent: 14, endIndent: 14, color: p.line),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(14, 11, 14, 11),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(
                                child: Text(m.name, style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w500)),
                              ),
                              const SizedBox(width: 10),
                              Text(
                                '${formatQty(m.total)} ${m.unit}',
                                style: TextStyle(
                                  fontSize: 14.5,
                                  fontWeight: FontWeight.w600,
                                  color: m.insufficient ? p.danger : p.ink,
                                  fontFeatures: const [FontFeature.tabularFigures()],
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 3),
                          Text(
                            [
                              for (final e in m.byRequest.entries) '${shortName[e.key]} ${formatQty(e.value)}',
                            ].join(' · '),
                            style: TextStyle(fontSize: 12.5, color: p.muted),
                          ),
                          if (m.insufficient)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text(
                                'на складе ${formatQty(m.available)} ${m.unit}, не хватает ${formatQty(m.total - m.available)}',
                                style: TextStyle(fontSize: 12.5, color: p.danger, fontWeight: FontWeight.w500),
                              ),
                            ),
                        ],
                      ),
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

  Widget _header(AppPalette p, String text) => Padding(
        padding: const EdgeInsets.only(left: 4, bottom: 8),
        child: Text(
          text.toUpperCase(),
          style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.8, color: p.muted),
        ),
      );
}
