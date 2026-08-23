import 'package:flutter/material.dart';

import '../models/order.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// Заявки (заказы), по которым клиент ещё не оплатил — с сроком оплаты.
/// Просроченные подсвечиваются; по истечении срока бухгалтер получает уведомление
/// (см. ScheduledJobs.checkOverdueDebts на бэкенде).
class DebtsScreen extends StatefulWidget {
  const DebtsScreen({super.key});

  @override
  State<DebtsScreen> createState() => _DebtsScreenState();
}

class _DebtsScreenState extends State<DebtsScreen> {
  final _api = ApiClient();
  late Future<List<OrderSummary>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getDebts();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getDebts();
    });
    await _future;
  }

  bool _isOverdue(OrderSummary o) => o.debtDueDate!.isBefore(DateTime.now());

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Долги')),
      drawer: const AppDrawer(currentRoute: '/debts'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<OrderSummary>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(
                error: snapshot.error.toString(),
                onRetry: _refresh,
              );
            }
            final debts = snapshot.data!;
            if (debts.isEmpty) {
              return ListView(
                children: const [
                  Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(
                      child: Text(
                        'Неоплаченных долгов нет',
                        style: TextStyle(color: Colors.grey),
                      ),
                    ),
                  ),
                ],
              );
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: debts.length,
              itemBuilder: (context, i) {
                final o = debts[i];
                final overdue = _isOverdue(o);
                return AppListTile(
                  leadingIcon: Icons.hourglass_bottom_outlined,
                  leadingColor: overdue
                      ? const Color(0xFFB8433F)
                      : const Color(0xFFD9A73F),
                  title: '#${o.id} · ${o.clientName}',
                  subtitle:
                      '${formatMoney(o.totalPrice)} · срок ${formatDate(o.debtDueDate!)}'
                      '${overdue ? " · просрочен" : ""}',
                  trailing: Icon(
                    Icons.chevron_right,
                    color: overdue ? const Color(0xFFB8433F) : Colors.grey,
                  ),
                  onTap: () async {
                    await Navigator.of(context)
                        .pushNamed('/orders/detail', arguments: o.id);
                    _refresh();
                  },
                );
              },
            );
          },
        ),
      ),
    );
  }
}
