import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/app_choice_chip.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/production_composition_sheet.dart';
import '../widgets/production_merge_sheet.dart';
import '../widgets/status_badge.dart';
import '../widgets/swipe_cancel_delete.dart';
import 'production_request_detail_screen.dart';
import 'production_request_form_screen.dart';
import '../theme/app_theme.dart';

/// Порядок и подписи статусов для фильтра — совпадают с ProductionRequestStatus на бэкенде
/// (см. ProductionRequestService.STATUS_ORDER — там же порядок для сортировки списка).
const _statuses = ['NEW', 'COLLECTED', 'RECEIVED', 'CANCELLED'];
const _statusLabels = {
  'NEW': 'Новая',
  'COLLECTED': 'Собрано',
  'RECEIVED': 'Оприходовано',
  'CANCELLED': 'Отменена',
};

/// Заявки на сырьё под производство: бухгалтер создаёт -> зав. склада сырья собирает по
/// чек-листу -> зав. склада ГП оприходует готовую продукцию. См. ProductionRequestService
/// на бэкенде — весь workflow и переиспользование StockService там.
class ProductionRequestsScreen extends StatefulWidget {
  const ProductionRequestsScreen({super.key});

  @override
  State<ProductionRequestsScreen> createState() => _ProductionRequestsScreenState();
}

class _ProductionRequestsScreenState extends State<ProductionRequestsScreen> {
  final _api = ApiClient();
  late Future<List<ProductionRequestSummary>> _future;
  DateTimeRange _range = todayRange();
  String? _status;

  /// Режим слияния рецептур: иконка в шапке включает галочки на карточках; при 2+ выбранных
  /// — кнопка «Показать слияние» (см. showProductionMergePreview).
  bool _merging = false;
  final Set<int> _selected = {};

  void _toggleMerging() => setState(() {
        _merging = !_merging;
        _selected.clear();
      });

  bool get _canCreate {
    final role = ApiClient.currentSession?.role;
    return role == 'ADMIN' || role == 'ACCOUNTANT';
  }

  /// По просьбе пользователя — только на телефоне и только для WAREHOUSE_RAW: карточка
  /// завершённой заявки (требование-накладная уже проведена в 1С) становится серой, чтобы
  /// отличаться от остальных в списке. Веб не трогаем — не входило в запрос.
  /// Отмена и удаление заявки (свайп влево) — только админ: откатывают реальные остатки склада.
  bool get _isAdmin => ApiClient.currentSession?.role == 'ADMIN';

  bool get _isWarehouseRaw => ApiClient.currentSession?.role == 'WAREHOUSE_RAW';

  @override
  void initState() {
    super.initState();
    _future = _api.getProductionRequests();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getProductionRequests());
    await _future;
  }

  void _onRangeChanged(DateTimeRange range) => setState(() => _range = range);

  /// Фильтруем по documentDate (дата документа для 1С, выбранная при создании), а не по
  /// createdAt — иначе список расходится с тем, что видно в 1С, если заявка создана "на
  /// завтра"/"задним числом" (тот же приём, что и у закупок — см. purchases_screen.dart).
  List<ProductionRequestSummary> _filter(List<ProductionRequestSummary> requests) {
    final end = _range.end.add(const Duration(days: 1));
    return requests
        .where((r) => !r.documentDate.isBefore(_range.start) && r.documentDate.isBefore(end))
        .where((r) => _status == null || r.status == _status)
        .toList();
  }

  Future<void> _openNew() async {
    final created = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => const ProductionRequestFormScreen()),
    );
    if (created == true) _refresh();
  }

  Future<void> _openDetail(int id) async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => ProductionRequestDetailScreen(requestId: id)),
    );
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: _merging ? IconButton(icon: const Icon(Icons.close), tooltip: 'Отменить', onPressed: _toggleMerging) : null,
        title: Text(_merging ? 'Выберите заявки (${_selected.length})' : 'Заявки на сырьё'),
        actions: [
          if (!_merging)
            IconButton(icon: const Icon(Icons.call_merge), tooltip: 'Слияние рецептур', onPressed: _toggleMerging),
          const SizedBox(width: 4),
        ],
      ),
      drawer: const AppDrawer(currentRoute: '/production-requests'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/production-requests'),
      floatingActionButton: _merging
          ? FloatingActionButton.extended(
              onPressed: _selected.length < 2
                  ? null
                  : () => showProductionMergePreview(context, _selected.toList()),
              backgroundColor: _selected.length < 2 ? context.pal.line : null,
              icon: const Icon(Icons.call_merge),
              label: Text(_selected.length < 2 ? 'Отметьте 2 и больше' : 'Показать слияние (${_selected.length})'),
            )
          : _canCreate
              ? FloatingActionButton(onPressed: _openNew, child: const Icon(Icons.add))
              : null,
      body: Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          SizedBox(
            height: 48,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              children: [
                AppChoiceChip(
                  label: 'Все',
                  selected: _status == null,
                  onTap: () => setState(() => _status = null),
                ),
                for (final s in _statuses)
                  AppChoiceChip(
                    label: _statusLabels[s]!,
                    selected: _status == s,
                    onTap: () => setState(() => _status = s),
                  ),
              ],
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: FutureBuilder<List<ProductionRequestSummary>>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final requests = _filter(snapshot.data!);
                  if (requests.isEmpty) {
                    return ListView(children: [
                      Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(child: Text('Заявок пока нет', style: TextStyle(color: context.pal.muted))),
                      ),
                    ]);
                  }
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: requests.length,
                    itemBuilder: (context, index) {
                      final r = requests[index];
                      final selected = _selected.contains(r.id);
                      final tile = AppListTile(
                        leading: _merging
                            ? Checkbox(
                                value: selected,
                                onChanged: (_) => setState(() => selected ? _selected.remove(r.id) : _selected.add(r.id)),
                              )
                            : null,
                        leadingIcon: Icons.science_outlined,
                        leadingColor: context.pal.warn,
                        backgroundColor: (_isWarehouseRaw && r.requisitionCompleted) ? context.pal.line : null,
                        title: r.title,
                        subtitle: '№${r.id} · ${r.productNames.join(", ")} · ${formatQty(r.totalBatches)} замес. всего · '
                            '${r.createdBy} · ${formatDate(r.documentDate)}',
                        subtitleExtra: (r.hasShortfall || r.compositionDiffPending)
                            ? Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  if (r.hasShortfall)
                                    Text(
                                      'Недостаточно: ${r.shortfallText}',
                                      style: TextStyle(
                                          color: context.pal.danger, fontWeight: FontWeight.w600, fontSize: 12),
                                    ),
                                  if (r.compositionDiffPending)
                                    Text(
                                      r.compositionDiffBatches != null
                                          ? 'Замес изменён после выдачи: ${r.compositionDiffBatches} — удерживайте, чтобы увидеть разницу'
                                          : 'Состав изменён после выдачи — удерживайте, чтобы увидеть разницу',
                                      style: const TextStyle(
                                          color: Color(0xFFC77C12), fontWeight: FontWeight.w600, fontSize: 12),
                                    ),
                                ],
                              )
                            : null,
                        trailing: StatusBadge(
                          status: r.status,
                          label: r.requisitionCompleted ? 'Завершена' : r.statusLabel,
                          insufficient: r.hasShortfall,
                        ),
                        onTap: _merging
                            ? () => setState(() => selected ? _selected.remove(r.id) : _selected.add(r.id))
                            : () => _openDetail(r.id),
                        onLongPress: _merging ? null : () async {
                          await showProductionCompositionDiff(context, requestId: r.id);
                          _refresh();
                        },
                      );
                      if (_merging) return tile;
                      return SwipeCancelDelete(
                        itemKey: r.id,
                        cancelled: r.status == 'CANCELLED',
                        canCancel: _isAdmin,
                        canDelete: _isAdmin,
                        subject: 'заявку №${r.id}',
                        onCancel: () => _api.cancelProductionRequest(r.id),
                        onDelete: () => _api.deleteProductionRequest(r.id),
                        onDone: _refresh,
                        child: tile,
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
