import 'package:flutter/material.dart';

import '../models/dashboard.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final _api = ApiClient();
  late Future<Dashboard> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getDashboard();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getDashboard());
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Дашборд')),
      drawer: const AppDrawer(currentRoute: '/'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<Dashboard>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final d = snapshot.data!;
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                GridView.count(
                  crossAxisCount: 2,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  mainAxisSpacing: 12,
                  crossAxisSpacing: 12,
                  childAspectRatio: 1.6,
                  children: [
                    _StatCard(value: '${d.newOrders}', label: 'Новых заказов'),
                    _StatCard(value: '${d.inProduction}', label: 'В производстве'),
                    _StatCard(value: '${d.readyOrders}', label: 'Готовы к выдаче'),
                    _StatCard(value: '${d.pendingTasks}', label: 'Задач в очереди цеха'),
                  ],
                ),
                const SizedBox(height: 20),
                Text('⚠️ Низкие остатки на складе', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                if (d.lowStock.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Text('Остатков ниже нормы нет', style: TextStyle(color: Colors.grey)),
                  )
                else
                  Card(
                    child: Column(
                      children: [
                        for (final i in d.lowStock)
                          ListTile(
                            title: Text(i.name),
                            trailing: Text(
                              '${formatQty(i.quantityInStock)} ${i.unit}',
                              style: const TextStyle(color: Color(0xFFB8433F), fontWeight: FontWeight.w600),
                            ),
                          ),
                      ],
                    ),
                  ),
                const SizedBox(height: 20),
                Text('🔔 Последние уведомления', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                if (d.recentNotifications.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Text('Пока нет уведомлений', style: TextStyle(color: Colors.grey)),
                  )
                else
                  Card(
                    child: Column(
                      children: [
                        for (final n in d.recentNotifications)
                          ListTile(
                            title: Text(n.message),
                            subtitle: Text(n.recipient),
                            dense: true,
                          ),
                      ],
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  final String value;
  final String label;

  const _StatCard({required this.value, required this.label});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(value, style: const TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(color: Colors.grey, fontSize: 12)),
          ],
        ),
      ),
    );
  }
}

