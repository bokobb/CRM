import 'package:flutter/material.dart';

import '../models/notification.dart';
import '../services/api_client.dart';
import '../services/push_service.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/order_notification_sheet.dart';
import '../widgets/status_badge.dart';
import '../theme/app_theme.dart';

class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  final _api = ApiClient();
  late Future<(List<OrderNotificationGroup>, List<AppNotification>)> _future;
  DateTimeRange _range = todayRange();

  @override
  void initState() {
    super.initState();
    _future = _api.getNotificationsGrouped(from: _range.start, to: _range.end);
    PushService.markRead();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getNotificationsGrouped(from: _range.start, to: _range.end);
    });
    await _future;
  }

  void _onRangeChanged(DateTimeRange range) {
    setState(() => _range = range);
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Уведомления')),
      drawer: const AppDrawer(currentRoute: '/notifications'),
      body: Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: FutureBuilder<(List<OrderNotificationGroup>, List<AppNotification>)>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final (groups, general) = snapshot.data!;
                  if (groups.isEmpty && general.isEmpty) {
                    return ListView(children: [
                      Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(child: Text('Уведомлений пока нет', style: TextStyle(color: context.pal.muted))),
                      ),
                    ]);
                  }
                  return ListView(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    children: [
                      for (final g in groups)
                        AppListTile(
                          leadingIcon: Icons.receipt_long_outlined,
                          title: 'Заказ №${g.orderId} (${g.clientName})',
                          subtitle:
                              '${g.history.length} уведомл${g.history.length == 1 ? "ение" : "ений"} · ${formatDateTime(g.lastNotifiedAt)}',
                          trailing: StatusBadge(status: g.status, label: g.statusLabel),
                          onTap: () => showOrderNotificationHistory(context, g),
                        ),
                      if (general.isNotEmpty) ...[
                        if (groups.isNotEmpty) const SizedBox(height: 12),
                        for (final n in general)
                          AppListTile(
                            leadingIcon: Icons.info_outline,
                            leadingColor: context.pal.muted,
                            title: n.message,
                            subtitle: formatDateTime(n.createdAt),
                          ),
                      ],
                    ],
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}
