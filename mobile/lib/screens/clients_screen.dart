import 'package:flutter/material.dart';

import '../models/client.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/searchable_app_bar.dart';
import '../theme/app_theme.dart';

class ClientsScreen extends StatefulWidget {
  const ClientsScreen({super.key});

  @override
  State<ClientsScreen> createState() => _ClientsScreenState();
}

class _ClientsScreenState extends State<ClientsScreen> {
  final _api = ApiClient();
  late Future<List<Client>> _future;
  String _query = '';

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

  List<Client> _filter(List<Client> clients) {
    if (_query.isEmpty) return clients;
    final q = _query.toLowerCase();
    return clients
        .where((c) => c.name.toLowerCase().contains(q) || (c.phone?.toLowerCase().contains(q) ?? false))
        .toList();
  }

  /// Только ADMIN (см. ClientApiController.delete на бэкенде) — каскадно удаляет клиента со
  /// всей его историей (заказы, цены, WhatsApp-сообщения), необратимо.
  Future<void> _delete(Client c) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить клиента?'),
        content: Text('«${c.name}» — вместе со всеми его заказами и историей. Это необратимо.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await _api.deleteClient(c.id);
      _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final isAdmin = ApiClient.currentSession?.role == 'ADMIN';
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Клиенты',
        hintText: 'Имя или номер телефона...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/clients'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/clients'),
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
            final clients = _filter(snapshot.data!);
            if (clients.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(_query.isEmpty ? 'Клиентов пока нет' : 'Ничего не найдено',
                        style: TextStyle(color: context.pal.muted)),
                  ),
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
                  trailing: isAdmin
                      ? PopupMenuButton<String>(
                          onSelected: (value) {
                            if (value == 'delete') _delete(c);
                          },
                          itemBuilder: (context) => const [
                            PopupMenuItem(value: 'delete', child: Text('Удалить')),
                          ],
                        )
                      : Icon(Icons.chevron_right, color: context.pal.muted),
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
