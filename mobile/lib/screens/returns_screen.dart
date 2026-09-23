import 'package:flutter/material.dart';

import '../models/product_return.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/searchable_app_bar.dart';
import 'return_form_screen.dart';

const returnColor = Color(0xFFD9822B);

/// Возвраты испорченного / просроченного товара — см. ProductReturnService на бэкенде.
/// Создаются с нуля (товар мог быть продан когда угодно), сразу уходят в 1С на склад
/// "Испорченные товары"; за заказом закрепляются из списка заказов (см. orders_screen.dart).
class ReturnsScreen extends StatefulWidget {
  const ReturnsScreen({super.key});

  @override
  State<ReturnsScreen> createState() => _ReturnsScreenState();
}

class _ReturnsScreenState extends State<ReturnsScreen> {
  final _api = ApiClient();
  late Future<List<ProductReturn>> _future;
  String _query = '';
  DateTimeRange _range = todayRange();
  final Set<int> _syncingIds = {};

  bool get _canResync {
    final role = ApiClient.currentSession?.role;
    return role == 'ADMIN' || role == 'ACCOUNTANT';
  }

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<List<ProductReturn>> _load() => _api.getReturns(from: _range.start, to: _range.end);

  Future<void> _refresh() async {
    setState(() => _future = _load());
    await _future;
  }

  void _onRangeChanged(DateTimeRange range) {
    setState(() {
      _range = range;
      _future = _load();
    });
  }

  Future<void> _syncOneC(ProductReturn r) async {
    setState(() => _syncingIds.add(r.id));
    try {
      final updated = await _api.syncReturnOneC(r.id);
      await _refresh();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              updated.oneCSyncError == null
                  ? 'Возврат №${r.id} проведён в 1С'
                  : 'Не проведён: ${updated.oneCSyncError}',
            ),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось синхронизировать: $e')));
      }
    } finally {
      if (mounted) setState(() => _syncingIds.remove(r.id));
    }
  }

  Future<void> _openForm() async {
    if (await openReturnForm(context) != null) _refresh();
  }

  List<ProductReturn> _filter(List<ProductReturn> returns) {
    if (_query.isEmpty) return returns;
    final q = _query.toLowerCase();
    return returns
        .where(
          (r) => r.clientName.toLowerCase().contains(q) || r.items.any((i) => i.productName.toLowerCase().contains(q)),
        )
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Возвраты',
        hintText: 'Клиент или товар...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/returns'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: FutureBuilder<List<ProductReturn>>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final returns = _filter(snapshot.data!);
                  if (returns.isEmpty) {
                    return ListView(
                      children: [
                        Padding(
                          padding: const EdgeInsets.all(32),
                          child: Center(
                            child: Text(
                              _query.isEmpty ? 'Возвратов нет' : 'Ничего не найдено',
                              style: const TextStyle(color: Colors.grey),
                            ),
                          ),
                        ),
                      ],
                    );
                  }
                  final total = returns.fold<double>(0, (s, r) => s + r.totalSum);
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: returns.length + 1,
                    itemBuilder: (context, index) {
                      if (index == 0) {
                        return Padding(
                          padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
                          child: Text(
                            'Итого возвратов: ${formatMoney(total)}',
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                        );
                      }
                      final r = returns[index - 1];
                      final itemsSummary = r.items.map((i) => '${i.productName} × ${formatQty(i.quantity)}').join(', ');
                      final syncing = _syncingIds.contains(r.id);
                      return AppListTile(
                        leadingIcon: Icons.assignment_return_outlined,
                        leadingColor: returnColor,
                        title: '№${r.id} · ${r.clientName}',
                        subtitle: [
                          itemsSummary,
                          formatDate(r.returnDate),
                          if (r.reason != null) r.reason!,
                          r.orderId != null ? 'заказ #${r.orderId}' : 'не закреплён',
                        ].join(' · '),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(formatMoney(r.totalSum)),
                            const SizedBox(width: 6),
                            if (syncing)
                              const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                            else if (r.oneCSyncError != null)
                              IconButton(
                                icon: const Icon(Icons.sync_problem, color: Colors.red),
                                tooltip: _canResync ? '${r.oneCSyncError}\nНажмите, чтобы повторить' : r.oneCSyncError,
                                onPressed: _canResync ? () => _syncOneC(r) : null,
                              )
                            else if (r.oneCDocumentNumber != null)
                              Icon(
                                Icons.check_circle_outline,
                                color: const Color(0xFF4C8A5E),
                                size: 20,
                                semanticLabel: 'В 1С №${r.oneCDocumentNumber}',
                              ),
                          ],
                        ),
                        onTap: () => showReturnDetails(context, r, onChanged: _refresh),
                      );
                    },
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Состав возврата — нижняя шторка; используется и в списке возвратов, и из карточки заказа.
void showReturnDetails(BuildContext context, ProductReturn r, {VoidCallback? onChanged}) {
  final outerContext = context;
  final role = ApiClient.currentSession?.role;
  final canDelete = role == 'ADMIN' || role == 'ACCOUNTANT';
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Возврат №${r.id} · ${r.clientName}', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              [
                formatDate(r.returnDate),
                if (r.reason != null) r.reason!,
                r.orderId != null ? 'закреплён за заказом #${r.orderId}' : 'не закреплён за заказом',
              ].join(' · '),
              style: const TextStyle(color: Colors.grey),
            ),
            if (r.oneCDocumentNumber != null)
              Text('1С: документ №${r.oneCDocumentNumber}', style: const TextStyle(color: Colors.grey)),
            if (r.oneCSyncError != null) Text(r.oneCSyncError!, style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 8),
            for (final i in r.items)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(i.productName),
                subtitle: Text('${formatQty(i.quantity)} × ${formatMoney(i.price)}'),
                trailing: Text(formatMoney(i.sum)),
              ),
            const Divider(),
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('Итого', style: TextStyle(fontWeight: FontWeight.bold)),
              trailing: Text(formatMoney(r.totalSum), style: const TextStyle(fontWeight: FontWeight.bold)),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                if (canDelete) ...[
                  Expanded(
                    child: OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                      icon: const Icon(Icons.delete_outline, size: 18),
                      label: const Text('Удалить'),
                      onPressed: () async {
                        Navigator.pop(context);
                        if (await _deleteReturn(outerContext, r)) onChanged?.call();
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                ],
                Expanded(
                  child: FilledButton.icon(
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    label: const Text('Изменить'),
                    onPressed: () async {
                      Navigator.pop(context);
                      if (await openReturnForm(outerContext, initial: r) != null) onChanged?.call();
                    },
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

/// Полноэкранная форма возврата (см. ReturnFormScreen) — новый или правка ([initial]). После
/// сохранения показывает, как прошла выгрузка в 1С. Null — форму закрыли без сохранения.
Future<ProductReturn?> openReturnForm(
  BuildContext context, {
  ProductReturn? initial,
  int? clientId,
  String? clientName,
  int? orderId,
}) async {
  final saved = await Navigator.of(context).push<ProductReturn>(
    MaterialPageRoute(
      builder: (_) => ReturnFormScreen(initial: initial, clientId: clientId, clientName: clientName, orderId: orderId),
    ),
  );
  if (saved != null && context.mounted) {
    final action = initial == null ? 'оформлен' : 'изменён';
    final posted = initial == null ? 'проведён в 1С' : 'перепроведён в 1С';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          saved.oneCSyncError == null
              ? 'Возврат №${saved.id} $action${saved.oneCDocumentNumber != null ? ' и $posted' : ''}'
              : 'Возврат №${saved.id} $action, но в 1С ошибка: ${saved.oneCSyncError}',
        ),
      ),
    );
  }
  return saved;
}

/// Удаление с подтверждением — сервер сначала отменяет проведение в 1С и помечает документ на
/// удаление; если 1С не ответила, возврат остаётся и в CRM (показываем причину).
Future<bool> _deleteReturn(BuildContext context, ProductReturn r) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Удалить возврат №${r.id}?'),
      content: Text(
        r.oneCDocumentNumber != null
            ? 'В 1С документ №${r.oneCDocumentNumber} будет распроведён и помечен на удаление.'
            : 'Возврат будет удалён.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
        TextButton(
          onPressed: () => Navigator.pop(context, true),
          style: TextButton.styleFrom(foregroundColor: Colors.red),
          child: const Text('Удалить'),
        ),
      ],
    ),
  );
  if (confirmed != true) return false;
  try {
    await ApiClient().deleteReturn(r.id);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Возврат №${r.id} удалён')));
    }
    return true;
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось удалить: $e')));
    }
    return false;
  }
}
