import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import 'production_request_detail_screen.dart';

/// Доска "Производство": те же заявки на сырьё, что и на странице "Заявки на сырьё"
/// (production_requests_screen.dart), но сгруппированные по стадии в три секции — Ожидает/
/// В работе/Готово (= статусы NEW/COLLECTED/RECEIVED). Карточка ведёт на тот же экран заявки,
/// где и происходят реальные действия (сборка по чек-листу, оприходование по строкам) — здесь
/// только обзорная витрина.
///
/// Поиск по товару фильтрует все три секции. Фильтр по дате — ТОЛЬКО "Готово": часть товаров
/// готовят не каждый день, и заявка на такой товар может провисеть в "Ожидает"/"В работе"
/// несколько дней — фильтровать там по дате создания значило бы прятать ещё не закрытую
/// работу только потому, что она началась не сегодня. Для архива ("Готово") дата осмысленна.
class ProductionScreen extends StatefulWidget {
  const ProductionScreen({super.key});

  @override
  State<ProductionScreen> createState() => _ProductionScreenState();
}

class _ProductionScreenState extends State<ProductionScreen> {
  final _api = ApiClient();
  late Future<List<ProductionRequestSummary>> _future;
  String _query = '';
  DateTime? _doneFrom;
  DateTime? _doneTo;

  @override
  void initState() {
    super.initState();
    _future = _api.getProductionRequests();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getProductionRequests());
    await _future;
  }

  Future<void> _openDetail(int id) async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => ProductionRequestDetailScreen(requestId: id)),
    );
    _refresh();
  }

  bool _matchesQuery(ProductionRequestSummary r) {
    if (_query.isEmpty) return true;
    final q = _query.toLowerCase();
    return r.productNames.any((n) => n.toLowerCase().contains(q)) || r.title.toLowerCase().contains(q);
  }

  bool _matchesDoneDate(ProductionRequestSummary r) {
    if (_doneFrom == null && _doneTo == null) return true;
    final created = DateTime(r.createdAt.year, r.createdAt.month, r.createdAt.day);
    if (_doneFrom != null && created.isBefore(_doneFrom!)) return false;
    if (_doneTo != null && created.isAfter(_doneTo!)) return false;
    return true;
  }

  Future<void> _pickDoneRange() async {
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 1)),
      initialDateRange: _doneFrom != null && _doneTo != null ? DateTimeRange(start: _doneFrom!, end: _doneTo!) : null,
    );
    if (range != null) {
      setState(() {
        _doneFrom = range.start;
        _doneTo = range.end;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Производство')),
      drawer: const AppDrawer(currentRoute: '/production'),
      body: RefreshIndicator(
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
            final requests = snapshot.data!.where(_matchesQuery).toList();
            final pending = requests.where((r) => r.status == 'NEW').toList();
            final inProgress = requests.where((r) => r.status == 'COLLECTED').toList();
            final done = requests.where((r) => r.status == 'RECEIVED').where(_matchesDoneDate).toList();

            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                TextField(
                  decoration: const InputDecoration(
                    labelText: 'Поиск по товару…',
                    prefixIcon: Icon(Icons.search),
                    isDense: true,
                  ),
                  onChanged: (v) => setState(() => _query = v),
                ),
                const SizedBox(height: 16),
                _RequestSection(title: 'Ожидает', requests: pending, onTap: _openDetail),
                const SizedBox(height: 20),
                _RequestSection(title: 'В работе', requests: inProgress, onTap: _openDetail),
                const SizedBox(height: 20),
                Row(
                  children: [
                    Expanded(
                      child: Text('Готово (${done.length})', style: Theme.of(context).textTheme.titleMedium),
                    ),
                    TextButton.icon(
                      onPressed: _pickDoneRange,
                      icon: const Icon(Icons.calendar_today, size: 16),
                      label: Text(_doneFrom == null ? 'За всё время' : 'Период'),
                    ),
                    if (_doneFrom != null)
                      IconButton(
                        icon: const Icon(Icons.close, size: 18),
                        tooltip: 'Сбросить период',
                        onPressed: () => setState(() {
                          _doneFrom = null;
                          _doneTo = null;
                        }),
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                if (done.isEmpty)
                  const Text('Пусто', style: TextStyle(color: Colors.grey))
                else
                  _RequestList(requests: done, onTap: _openDetail),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _RequestSection extends StatelessWidget {
  final String title;
  final List<ProductionRequestSummary> requests;
  final ValueChanged<int> onTap;

  const _RequestSection({required this.title, required this.requests, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$title (${requests.length})', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (requests.isEmpty)
          const Text('Пусто', style: TextStyle(color: Colors.grey))
        else
          _RequestList(requests: requests, onTap: onTap),
      ],
    );
  }
}

class _RequestList extends StatelessWidget {
  final List<ProductionRequestSummary> requests;
  final ValueChanged<int> onTap;

  const _RequestList({required this.requests, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Column(
        children: [
          for (final r in requests)
            InkWell(
              onTap: () => onTap(r.id),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(r.title, style: const TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(
                      '${r.productNames.join(", ")} · ${formatQty(r.totalBatches)} замес.',
                      style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                    ),
                    Text(
                      '${r.createdBy} · ${formatDateTime(r.createdAt)}',
                      style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}
