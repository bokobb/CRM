import 'package:flutter/material.dart';

import '../models/user.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';

class UsersScreen extends StatefulWidget {
  const UsersScreen({super.key});

  @override
  State<UsersScreen> createState() => _UsersScreenState();
}

class _UsersScreenState extends State<UsersScreen> {
  final _api = ApiClient();
  late Future<List<AppUser>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getUsers();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getUsers();
    });
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Пользователи')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/settings'),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/settings/users/new');
          _refresh();
        },
        child: const Icon(Icons.add),
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<AppUser>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final users = snapshot.data!;
            return ListView.separated(
              itemCount: users.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final u = users[i];
                return ListTile(
                  title: Text(u.fullName),
                  subtitle: Text('${u.username} · ${u.roleLabel}${u.enabled ? '' : ' · отключён'}'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/settings/users/edit', arguments: u.id);
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
