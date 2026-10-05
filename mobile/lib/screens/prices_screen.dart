import 'package:flutter/material.dart';

import '../models/client.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../theme/app_theme.dart';

/// Точка входа в индивидуальные цены из Настроек: список клиентов, тап ведёт на
/// уже существующий client_prices_screen.dart (обычно туда попадают из карточки клиента).
class PricesScreen extends StatefulWidget {
  const PricesScreen({super.key});

  @override
  State<PricesScreen> createState() => _PricesScreenState();
}

class _PricesScreenState extends State<PricesScreen> {
  final _api = ApiClient();
  late Future<List<Client>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getClients();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getClients();
    });
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Цены')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Client>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final clients = snapshot.data!;
            if (clients.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Клиентов пока нет', style: TextStyle(color: context.pal.muted))),
                ),
              ]);
            }
            return ListView.separated(
              itemCount: clients.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final c = clients[i];
                return ListTile(
                  leading: const Icon(Icons.sell_outlined),
                  title: Text(c.name),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).pushNamed('/clients/prices', arguments: c.id),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
