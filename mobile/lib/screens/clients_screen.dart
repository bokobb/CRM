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

  /// Заявки на доступ к сайту клиентов с новых номеров — только у ADMIN.
  List<Map<String, dynamic>> _requests = [];

  bool get _isAdmin => ApiClient.currentSession?.role == 'ADMIN';

  @override
  void initState() {
    super.initState();
    _future = _api.getClients();
    _loadRequests();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getClients();
    });
    _loadRequests();
    await _future;
  }

  Future<void> _loadRequests() async {
    if (!_isAdmin) return;
    try {
      final list = await _api.getShopAccessRequests();
      if (mounted) setState(() => _requests = list);
    } catch (_) {
      // не мешаем списку клиентов, если заявки не загрузились
    }
  }

  Future<void> _decide(Map<String, dynamic> r, bool approve) async {
    try {
      await _api.decideShopAccessRequest(r['id'] as int, approve);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(approve ? '«${r['name']}» может входить на сайт' : 'Заявка «${r['name']}» отклонена')));
      }
      _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Widget _requestCard(Map<String, dynamic> r) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Просит доступ к сайту', style: TextStyle(color: context.pal.muted, fontSize: 12)),
            const SizedBox(height: 2),
            Text('${r['name']}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
            Text('${r['phone']}'),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(onPressed: () => _decide(r, false), child: const Text('Отклонить')),
                const SizedBox(width: 8),
                FilledButton(onPressed: () => _decide(r, true), child: const Text('Разрешить')),
              ],
            ),
          ],
        ),
      ),
    );
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
    final isAdmin = _isAdmin;
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
              return ListView(padding: const EdgeInsets.fromLTRB(12, 10, 12, 12), children: [
                for (final r in _requests) _requestCard(r),
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
              itemCount: _requests.length + clients.length,
              itemBuilder: (context, i) {
                if (i < _requests.length) return _requestCard(_requests[i]);
                final c = clients[i - _requests.length];
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
