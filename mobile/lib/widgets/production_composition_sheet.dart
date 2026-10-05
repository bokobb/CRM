import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import 'error_view.dart';
import 'format.dart';
import '../theme/app_theme.dart';

const _green = Color(0xFF2E8B57);
const _orange = Color(0xFFC77C12);
Color _red(BuildContext context) => context.pal.danger;

/// Открывается долгим нажатием на карточку заявки (см. production_requests_screen.dart), две
/// таблицы листаются свайпом. «Выдача» — состав против выданного работникам: зелёным довыдать,
/// красным вернуть на склад, сверху — изменённые после выдачи замесы, ниже история правок;
/// «Рассчитались» делает текущий состав выданным и обнуляет разницу. «К норме» — состав против
/// рецептуры × замесы: сколько сверх нормы и сколько не хватает.
Future<void> showProductionCompositionDiff(BuildContext context, {required int requestId}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (context) => _CompositionSheet(requestId: requestId),
  );
}

class _CompositionSheet extends StatefulWidget {
  final int requestId;

  const _CompositionSheet({required this.requestId});

  @override
  State<_CompositionSheet> createState() => _CompositionSheetState();
}

class _CompositionSheetState extends State<_CompositionSheet> {
  final _api = ApiClient();
  late Future<ProductionCompositionDiff> _future = _api.getProductionCompositionDiff(widget.requestId);
  bool _settling = false;
  final _pages = PageController();
  int _page = 0;

  bool get _canSettle {
    final role = ApiClient.currentSession?.role;
    return role == 'ADMIN' || role == 'WAREHOUSE_RAW';
  }

  void _reload() => setState(() => _future = _api.getProductionCompositionDiff(widget.requestId));

  Future<void> _settle() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Рассчитались?'),
        content: const Text('Недостающее выдано, лишнее возвращено на склад. '
            'Текущий состав станет выданным, разница обнулится.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Нет')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Да')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _settling = true);
    try {
      final result = await _api.settleProductionComposition(widget.requestId);
      if (mounted) setState(() => _future = Future.value(result));
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    } finally {
      if (mounted) setState(() => _settling = false);
    }
  }

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  void _goTo(int page) =>
      _pages.animateToPage(page, duration: const Duration(milliseconds: 250), curve: Curves.easeOut);

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: FutureBuilder<ProductionCompositionDiff>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
            }
            if (snapshot.hasError) {
              return SizedBox(height: 140, child: ErrorView(error: snapshot.error.toString(), onRetry: _reload));
            }
            final d = snapshot.data!;
            final theme = Theme.of(context);
            // Две таблицы, листаются свайпом влево/вправо: «Выдача» (что довыдать/вернуть
            // работникам) и «К норме» (сколько сверх/меньше рецептуры).
            return SizedBox(
              height: MediaQuery.of(context).size.height * 0.85,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Заявка №${d.requestId} · ${d.title}', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 10),
                  Row(children: [
                    _tab(0, 'Выдача'),
                    const SizedBox(width: 8),
                    _tab(1, 'К норме'),
                    const Spacer(),
                    Icon(Icons.swipe, size: 18, color: context.pal.muted),
                  ]),
                  const Divider(height: 16),
                  Expanded(
                    child: PageView(
                      controller: _pages,
                      onPageChanged: (p) => setState(() => _page = p),
                      children: [_issuePage(d), _normPage(d)],
                    ),
                  ),
                  if (_canSettle && d.hasDiff) ...[
                    const SizedBox(height: 8),
                    FilledButton.icon(
                      onPressed: _settling ? null : _settle,
                      icon: _settling
                          ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.done_all),
                      label: const Text('Рассчитались'),
                    ),
                  ],
                  TextButton(onPressed: () => Navigator.pop(context), child: const Text('Закрыть')),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _tab(int page, String label) {
    final active = _page == page;
    final color = Theme.of(context).colorScheme.primary;
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: () => _goTo(page),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          color: active ? color.withValues(alpha: 0.12) : null,
          border: Border.all(color: active ? color : context.pal.line),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Text(label,
            style: TextStyle(
                fontSize: 13, fontWeight: FontWeight.w600, color: active ? color : context.pal.muted)),
      ),
    );
  }

  /// Таблица 1 — состав против выданного работникам: зелёным довыдать, красным вернуть.
  Widget _issuePage(ProductionCompositionDiff d) {
    // Изменённые позиции — наверх, чтобы зав. склада сразу видел, что выдать/забрать.
    final rows = [...d.rows]..sort((a, b) => (b.diff != 0 ? 1 : 0).compareTo(a.diff != 0 ? 1 : 0));
    final toGive = rows.where((r) => r.diff > 0).length;
    final toReturn = rows.where((r) => r.diff < 0).length;
    final issued = d.issuedAt != null;
    return ListView(
      children: [
        Text(
          !issued
              ? 'Сырьё ещё не выдавали — разницы нет'
              : 'Выдано работникам: ${formatDateTime(d.issuedAt!)}${d.issuedBy != null ? ' · ${d.issuedBy}' : ''}',
          style: TextStyle(color: context.pal.muted, fontSize: 12.5),
        ),
        if (d.batchChanges.isNotEmpty) ...[
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: _orange.withValues(alpha: 0.10),
              border: Border.all(color: _orange.withValues(alpha: 0.5)),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Замес изменён после выдачи',
                  style: TextStyle(color: _orange, fontWeight: FontWeight.w700, fontSize: 13)),
              const SizedBox(height: 4),
              for (final c in d.batchChanges)
                Text(
                  '${c.productName}: ${formatQty(c.issued)} → ${formatQty(c.current)} '
                  '(${c.current > c.issued ? '+' : '−'}${formatQty((c.current - c.issued).abs())})',
                  style: const TextStyle(fontSize: 13),
                ),
            ]),
          ),
        ],
        if (issued) ...[
          const SizedBox(height: 8),
          Wrap(spacing: 12, runSpacing: 4, children: [
            if (!d.hasDiff) const Text('Разницы нет', style: TextStyle(fontWeight: FontWeight.w600)),
            if (toGive > 0) _legend(_green, 'довыдать: $toGive поз.'),
            if (toReturn > 0) _legend(_red(context), 'вернуть: $toReturn поз.'),
          ]),
        ],
        const SizedBox(height: 10),
        _headerRow(issued ? 'Выдано' : null, 'Сейчас', issued ? 'Разница' : null),
        for (final r in rows)
          _row(r.ingredientName, null, r.unit, issued ? r.issued : null, r.current, issued ? r.diff : null,
              r.diff > 0 ? _green : (r.diff < 0 ? _red(context) : null)),
        if (d.history.isNotEmpty) ...[
          const SizedBox(height: 12),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: Text('История правок (${d.history.length})', style: const TextStyle(fontWeight: FontWeight.w600)),
            children: [for (final h in d.history.reversed) _revision(h)],
          ),
        ],
      ],
    );
  }

  /// Таблица 2 — состав против нормы по рецептуре (рецептура × замесы): красным — сверх
  /// нормы (лишнее), оранжевым — меньше нормы (не хватает).
  Widget _normPage(ProductionCompositionDiff d) {
    final rows = [...d.normRows]..sort((a, b) => (b.diff != 0 ? 1 : 0).compareTo(a.diff != 0 ? 1 : 0));
    final over = rows.where((r) => r.diff > 0).length;
    final under = rows.where((r) => r.diff < 0).length;
    return ListView(
      children: [
        Text(
          d.lines.isEmpty
              ? 'Норма = рецептура × замесы'
              : 'Норма по рецептуре: ${d.lines.map((l) => '${l.productName} — ${formatQty(l.batches)} замес.').join(', ')}',
          style: TextStyle(color: context.pal.muted, fontSize: 12.5),
        ),
        const SizedBox(height: 8),
        Wrap(spacing: 12, runSpacing: 4, children: [
          if (over == 0 && under == 0) const Text('Всё по норме', style: TextStyle(fontWeight: FontWeight.w600)),
          if (over > 0) _legend(_red(context), 'сверх нормы: $over поз.'),
          if (under > 0) _legend(_orange, 'меньше нормы: $under поз.'),
        ]),
        const SizedBox(height: 10),
        _headerRow('Норма', 'В заявке', 'К норме'),
        for (final r in rows)
          _row(r.ingredientName, r.note, r.unit, r.norm, r.current, r.diff,
              r.diff > 0 ? _red(context) : (r.diff < 0 ? _orange : null)),
      ],
    );
  }

  Widget _legend(Color color, String text) => Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 10, height: 10, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 6),
        Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w600)),
      ]);

  static const _numStyle = TextStyle(fontSize: 13);
  static TextStyle _headStyle(BuildContext context) => TextStyle(fontSize: 11.5, color: context.pal.muted);

  Widget _headerRow(String? first, String second, String? diff) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Row(children: [
          Expanded(flex: 5, child: Text('Сырьё', style: _headStyle(context))),
          if (first != null)
            Expanded(flex: 3, child: Text(first, style: _headStyle(context), textAlign: TextAlign.right)),
          Expanded(flex: 3, child: Text(second, style: _headStyle(context), textAlign: TextAlign.right)),
          if (diff != null) Expanded(flex: 4, child: Text(diff, style: _headStyle(context), textAlign: TextAlign.right)),
        ]),
      );

  Widget _row(String name, String? note, String? unitOrNull, double? first, double second, double? diff, Color? color) {
    final unit = unitOrNull ?? '';
    final d = diff ?? 0;
    final diffText = d > 0
        ? '+${formatQty(d)} $unit'
        : d < 0
            ? '−${formatQty(-d)} $unit'
            : '—';
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
      decoration: BoxDecoration(
        color: color?.withValues(alpha: 0.08),
        border: Border(bottom: BorderSide(color: context.pal.line)),
      ),
      child: Row(children: [
        Expanded(
          flex: 5,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(name, style: TextStyle(fontSize: 13, color: color, fontWeight: color != null ? FontWeight.w600 : null)),
            if (note != null) Text(note, style: TextStyle(fontSize: 11, color: context.pal.muted)),
          ]),
        ),
        if (first != null) Expanded(flex: 3, child: Text(formatQty(first), style: _numStyle, textAlign: TextAlign.right)),
        Expanded(flex: 3, child: Text(formatQty(second), style: _numStyle, textAlign: TextAlign.right)),
        if (diff != null)
          Expanded(
            flex: 4,
            child: Text(diffText,
                textAlign: TextAlign.right,
                style: TextStyle(fontSize: 13, color: color ?? context.pal.muted, fontWeight: FontWeight.w700)),
          ),
      ]),
    );
  }

  Widget _revision(CompositionRevision h) => ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 4),
        dense: true,
        leading: Icon(h.issued ? Icons.inventory_2_outlined : Icons.edit_outlined,
            size: 20, color: h.issued ? _green : context.pal.muted),
        title: Text(h.note ?? (h.issued ? 'Выдано' : 'Правка'), style: const TextStyle(fontSize: 13)),
        subtitle: Text('${formatDateTime(h.createdAt)} · ${h.createdBy}', style: const TextStyle(fontSize: 11.5)),
        children: [
          if (h.lines.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(40, 2, 8, 4),
              child: Text(h.lines.map((l) => '${l.productName} — ${formatQty(l.batches)} замес.').join(', '),
                  style: TextStyle(fontSize: 12, color: context.pal.muted, fontWeight: FontWeight.w600)),
            ),
          for (final i in h.items)
            Padding(
              padding: const EdgeInsets.fromLTRB(40, 2, 8, 2),
              child: Row(children: [
                Expanded(child: Text(i.ingredientName, style: const TextStyle(fontSize: 12.5))),
                Text('${formatQty(i.quantity)} ${i.unit ?? ''}', style: const TextStyle(fontSize: 12.5)),
              ]),
            ),
          const SizedBox(height: 6),
        ],
      );
}
