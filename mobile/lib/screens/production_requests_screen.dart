import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/app_choice_chip.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/status_badge.dart';
import 'production_request_detail_screen.dart';
import 'production_request_form_screen.dart';

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

  bool get _canCreate {
    final role = ApiClient.currentSession?.role;
    return role == 'ADMIN' || role == 'ACCOUNTANT';
  }

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

  List<ProductionRequestSummary> _filter(List<ProductionRequestSummary> requests) {
    final end = _range.end.add(const Duration(days: 1));
    return requests
        .where((r) => !r.createdAt.isBefore(_range.start) && r.createdAt.isBefore(end))
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
      appBar: AppBar(title: const Text('Заявки на сырьё')),
      drawer: const AppDrawer(currentRoute: '/production-requests'),
      floatingActionButton: _canCreate
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
                    return ListView(children: const [
                      Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(child: Text('Заявок пока нет', style: TextStyle(color: Colors.grey))),
                      ),
                    ]);
                  }
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: requests.length,
                    itemBuilder: (context, index) {
                      final r = requests[index];
                      return AppListTile(
                        leadingIcon: Icons.science_outlined,
                        leadingColor: const Color(0xFFD9A73F),
                        title: r.title,
                        subtitle: '№${r.id} · ${r.productNames.join(", ")} · ${formatQty(r.totalBatches)} замес. всего · '
                            '${r.createdBy} · ${formatDateTime(r.createdAt)}',
                        trailing: StatusBadge(status: r.status, label: r.statusLabel),
                        onTap: () => _openDetail(r.id),
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
