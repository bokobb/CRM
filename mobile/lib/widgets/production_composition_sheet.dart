import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import 'error_view.dart';
import 'format.dart';

const _green = Color(0xFF2E8B57);
const _red = Color(0xFFC0392B);

/// Состав заявки против выданного работникам — открывается долгим нажатием на карточку заявки
/// (см. production_requests_screen.dart). После правки состава одних позиций не хватает
/// (зелёным — довыдать), других выдано лишнее (красным — вернуть на склад). Ниже — история
/// всех правок; «Рассчитались» делает текущий состав выданным и обнуляет разницу.
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
            // Изменённые позиции — наверх, чтобы зав. склада сразу видел, что выдать/забрать.
            final rows = [...d.rows]..sort((a, b) => (b.diff != 0 ? 1 : 0).compareTo(a.diff != 0 ? 1 : 0));
            final toGive = rows.where((r) => r.diff > 0).length;
            final toReturn = rows.where((r) => r.diff < 0).length;
            final theme = Theme.of(context);
            return ConstrainedBox(
              constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.85),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Заявка №${d.requestId} · ${d.title}', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 4),
                  Text(
                    d.issuedAt == null
                        ? 'Сырьё ещё не выдавали — разницы нет'
                        : 'Выдано работникам: ${formatDateTime(d.issuedAt!)}'
                            '${d.issuedBy != null ? ' · ${d.issuedBy}' : ''}',
                    style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                  ),
                  if (d.issuedAt != null) ...[
                    const SizedBox(height: 8),
                    Wrap(spacing: 12, runSpacing: 4, children: [
                      if (!d.hasDiff) const Text('Разницы нет', style: TextStyle(fontWeight: FontWeight.w600)),
                      if (toGive > 0) _legend(_green, 'довыдать: $toGive поз.'),
                      if (toReturn > 0) _legend(_red, 'вернуть: $toReturn поз.'),
                    ]),
                  ],
                  const Divider(height: 20),
                  Flexible(
                    child: ListView(
                      shrinkWrap: true,
                      children: [
                        _headerRow(d.issuedAt != null),
                        for (final r in rows) _row(r, d.issuedAt != null),
                        if (d.history.isNotEmpty) ...[
                          const SizedBox(height: 12),
                          ExpansionTile(
                            tilePadding: EdgeInsets.zero,
                            title: Text('История правок (${d.history.length})',
                                style: const TextStyle(fontWeight: FontWeight.w600)),
                            children: [for (final h in d.history.reversed) _revision(h)],
                          ),
                        ],
                      ],
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

  Widget _legend(Color color, String text) => Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 10, height: 10, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 6),
        Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w600)),
      ]);

  static const _numStyle = TextStyle(fontSize: 13);
  static const _headStyle = TextStyle(fontSize: 11.5, color: Colors.grey);

  Widget _headerRow(bool issued) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Row(children: [
          const Expanded(flex: 5, child: Text('Сырьё', style: _headStyle)),
          if (issued) const Expanded(flex: 3, child: Text('Выдано', style: _headStyle, textAlign: TextAlign.right)),
          const Expanded(flex: 3, child: Text('Сейчас', style: _headStyle, textAlign: TextAlign.right)),
          if (issued) const Expanded(flex: 4, child: Text('Разница', style: _headStyle, textAlign: TextAlign.right)),
        ]),
      );

  Widget _row(CompositionDiffRow r, bool issued) {
    final unit = r.unit ?? '';
    final color = r.diff > 0 ? _green : (r.diff < 0 ? _red : null);
    final diffText = r.diff > 0
        ? '+${formatQty(r.diff)} $unit'
        : r.diff < 0
            ? '−${formatQty(-r.diff)} $unit'
            : '—';
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
      decoration: BoxDecoration(
        color: color?.withValues(alpha: 0.08),
        border: Border(bottom: BorderSide(color: Colors.grey.shade200)),
      ),
      child: Row(children: [
        Expanded(
          flex: 5,
          child: Text(r.ingredientName,
              style: TextStyle(fontSize: 13, color: color, fontWeight: color != null ? FontWeight.w600 : null)),
        ),
        if (issued) Expanded(flex: 3, child: Text(formatQty(r.issued), style: _numStyle, textAlign: TextAlign.right)),
        Expanded(flex: 3, child: Text(formatQty(r.current), style: _numStyle, textAlign: TextAlign.right)),
        if (issued)
          Expanded(
            flex: 4,
            child: Text(diffText,
                textAlign: TextAlign.right,
                style: TextStyle(fontSize: 13, color: color ?? Colors.grey, fontWeight: FontWeight.w700)),
          ),
      ]),
    );
  }

  Widget _revision(CompositionRevision h) => ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 4),
        dense: true,
        leading: Icon(h.issued ? Icons.inventory_2_outlined : Icons.edit_outlined,
            size: 20, color: h.issued ? _green : Colors.grey),
        title: Text(h.note ?? (h.issued ? 'Выдано' : 'Правка'), style: const TextStyle(fontSize: 13)),
        subtitle: Text('${formatDateTime(h.createdAt)} · ${h.createdBy}', style: const TextStyle(fontSize: 11.5)),
        children: [
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
