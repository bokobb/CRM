import 'package:flutter/material.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class ProductionScreen extends StatefulWidget {
  const ProductionScreen({super.key});

  @override
  State<ProductionScreen> createState() => _ProductionScreenState();
}

class _ProductionScreenState extends State<ProductionScreen> {
  final _api = ApiClient();
  late Future<List<ProductionTask>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getProductionTasks();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getProductionTasks());
    await _future;
  }

  Future<void> _start(ProductionTask t) async {
    final controller = TextEditingController();
    final assignedTo = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Взять в работу'),
        content: TextField(controller: controller, decoration: const InputDecoration(labelText: 'Кто делает')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Взять')),
        ],
      ),
    );
    if (assignedTo == null) return;
    try {
      await _api.startTask(t.id, assignedTo: assignedTo);
      _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }

  Future<void> _complete(ProductionTask t) async {
    try {
      await _api.completeTask(t.id);
      _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Производство')),
      drawer: const AppDrawer(currentRoute: '/production'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<ProductionTask>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final tasks = snapshot.data!;
            final pending = tasks.where((t) => t.status == 'PENDING').toList();
            final inProgress = tasks.where((t) => t.status == 'IN_PROGRESS').toList();
            final done = tasks.where((t) => t.status == 'DONE').toList();

            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _TaskSection(
                  title: 'Ожидает',
                  tasks: pending,
                  emptyText: 'Пусто',
                  actionBuilder: (t) => FilledButton(onPressed: () => _start(t), child: const Text('Взять в работу')),
                ),
                const SizedBox(height: 20),
                _TaskSection(
                  title: 'В работе',
                  tasks: inProgress,
                  emptyText: 'Пусто',
                  actionBuilder: (t) => FilledButton(
                    style: FilledButton.styleFrom(backgroundColor: const Color(0xFF4C8A5E)),
                    onPressed: () => _complete(t),
                    child: const Text('Готово'),
                  ),
                ),
                const SizedBox(height: 20),
                _TaskSection(title: 'Готово', tasks: done, emptyText: 'Пусто', actionBuilder: null),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _TaskSection extends StatelessWidget {
  final String title;
  final List<ProductionTask> tasks;
  final String emptyText;
  final Widget Function(ProductionTask)? actionBuilder;

  const _TaskSection({required this.title, required this.tasks, required this.emptyText, required this.actionBuilder});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$title (${tasks.length})', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (tasks.isEmpty)
          Text(emptyText, style: const TextStyle(color: Colors.grey))
        else
          Card(
            child: Column(
              children: [
                for (final t in tasks)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('${t.productName} × ${formatQty(t.quantity)}',
                                  style: const TextStyle(fontWeight: FontWeight.w600)),
                              Text(
                                'Заказ #${t.orderId}${t.assignedTo != null ? ' · ${t.assignedTo}' : ''}',
                                style: const TextStyle(color: Colors.grey, fontSize: 12),
                              ),
                            ],
                          ),
                        ),
                        if (actionBuilder != null) actionBuilder!(t),
                      ],
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}
