import 'package:flutter/material.dart';

import '../models/whatsapp_inbox.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../theme/app_theme.dart';

/// Заказы из WhatsApp, которые ИИ не смог распознать полностью — здесь администратор
/// подбирает товар для непонятных позиций (см. WhatsAppOrderIntakeService.resolvePending
/// на бэкенде; мобильный аналог веб-экрана /whatsapp-inbox).
class WhatsAppInboxScreen extends StatefulWidget {
  const WhatsAppInboxScreen({super.key});

  @override
  State<WhatsAppInboxScreen> createState() => _WhatsAppInboxScreenState();
}

class _WhatsAppInboxScreenState extends State<WhatsAppInboxScreen> {
  final _api = ApiClient();
  late Future<List<WhatsAppInboundMessage>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getWhatsAppInbox();
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getWhatsAppInbox();
    });
    await _future;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('WhatsApp-заказы')),
      drawer: const AppDrawer(currentRoute: '/whatsapp-inbox'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<WhatsAppInboundMessage>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final messages = snapshot.data!;
            if (messages.isEmpty) {
              return ListView(
                children: [
                  Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(
                      child: Text('Нет сообщений, ожидающих уточнения', style: TextStyle(color: context.pal.muted)),
                    ),
                  ),
                ],
              );
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: messages.length,
              itemBuilder: (context, i) {
                final m = messages[i];
                return AppListTile(
                  leadingIcon: Icons.chat_bubble_rounded,
                  leadingColor: const Color(0xFF25D366),
                  title: m.clientName,
                  subtitle: '${formatDateTime(m.createdAt)} · ${m.rawText.split("\n").join(" / ")}',
                  trailing: Icon(Icons.chevron_right, color: context.pal.muted),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/whatsapp-inbox/detail', arguments: m);
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
