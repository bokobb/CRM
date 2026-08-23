import 'package:flutter/material.dart';

import '../models/notification.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  final _api = ApiClient();
  late Future<List<AppNotification>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getNotifications();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getNotifications();
    });
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Уведомления')),
      drawer: const AppDrawer(currentRoute: '/notifications'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<AppNotification>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final notifications = snapshot.data!;
            if (notifications.isEmpty) {
              return ListView(children: const [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Уведомлений пока нет', style: TextStyle(color: Colors.grey))),
                ),
              ]);
            }
            return ListView.separated(
              itemCount: notifications.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final n = notifications[i];
                return ListTile(
                  title: Text(n.message),
                  subtitle: Text('${n.recipient} · ${formatDateTime(n.createdAt)}'),
                  trailing: Text(n.channel, style: const TextStyle(fontSize: 11, color: Colors.grey)),
                );
              },
            );
          },
        ),
      ),
    );
  }
}
