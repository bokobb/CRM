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
class ProductionScreen extends StatefulWidget {
  const ProductionScreen({super.key});

  @override
  State<ProductionScreen> createState() => _ProductionScreenState();
}

class _ProductionScreenState extends State<ProductionScreen> {
  final _api = ApiClient();
  late Future<List<ProductionRequestSummary>> _future;

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
            final requests = snapshot.data!;
            final pending = requests.where((r) => r.status == 'NEW').toList();
            final inProgress = requests.where((r) => r.status == 'COLLECTED').toList();
            final done = requests.where((r) => r.status == 'RECEIVED').toList();

            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _RequestSection(title: 'Ожидает', requests: pending, onTap: _openDetail),
                const SizedBox(height: 20),
                _RequestSection(title: 'В работе', requests: inProgress, onTap: _openDetail),
                const SizedBox(height: 20),
                _RequestSection(title: 'Готово', requests: done, onTap: _openDetail),
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
          Card(
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
          ),
      ],
    );
  }
}
