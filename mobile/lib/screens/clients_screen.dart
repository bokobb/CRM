import 'package:flutter/material.dart';

import '../models/client.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';

class ClientsScreen extends StatefulWidget {
  const ClientsScreen({super.key});

  @override
  State<ClientsScreen> createState() => _ClientsScreenState();
}

class _ClientsScreenState extends State<ClientsScreen> {
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
      appBar: AppBar(title: const Text('Клиенты')),
      drawer: const AppDrawer(currentRoute: '/clients'),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/clients/new');
          _refresh();
        },
        child: const Icon(Icons.add),
      ),
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
              return ListView(children: const [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Клиентов пока нет', style: TextStyle(color: Colors.grey))),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: clients.length,
              itemBuilder: (context, i) {
                final c = clients[i];
                return AppListTile(
                  leading: InitialAvatar(text: c.name),
                  title: c.name,
                  subtitle: [if (c.phone != null) c.phone!, if (c.email != null) c.email!].join(' · '),
                  trailing: const Icon(Icons.chevron_right, color: Colors.grey),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/clients/detail', arguments: c.id);
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
