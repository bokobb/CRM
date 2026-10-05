import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../theme/app_theme.dart';

/// Смена адреса backend (Base URL) — вынесен в общий виджет, т.к. нужен и из Настроек
/// (после входа), и с экрана логина (до входа — иначе новый человек с неверным адресом
/// в принципе не может попасть внутрь приложения, чтобы поменять адрес).
class ConnectionSheet extends StatefulWidget {
  const ConnectionSheet({super.key});

  @override
  State<ConnectionSheet> createState() => _ConnectionSheetState();
}

class _ConnectionSheetState extends State<ConnectionSheet> {
  final _controller = TextEditingController();
  bool _loading = true;
  bool _saved = false;

  @override
  void initState() {
    super.initState();
    ApiClient.getBaseUrl().then((url) {
      setState(() {
        _controller.text = url;
        _loading = false;
      });
    });
  }

  Future<void> _save() async {
    await ApiClient.setBaseUrl(_controller.text.trim());
    setState(() => _saved = true);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: 16 + MediaQuery.of(context).viewInsets.bottom,
      ),
      child: _loading
          ? const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()))
          : Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Подключение', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 12),
                Text(
                  'Адрес backend API кондитерской CRM (Spring Boot). '
                  'Для Android-эмулятора хост-машина доступна как 10.0.2.2, '
                  'для физического устройства укажите IP компьютера в вашей Wi-Fi сети '
                  '(например http://192.168.1.50:8080/api).',
                  style: TextStyle(color: context.pal.muted),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _controller,
                  decoration: const InputDecoration(labelText: 'Base URL', hintText: 'http://10.0.2.2:8080/api'),
                  onChanged: (_) => setState(() => _saved = false),
                ),
                const SizedBox(height: 16),
                FilledButton(onPressed: _save, child: const Text('Сохранить')),
                if (_saved) ...[
                  const SizedBox(height: 8),
                  Text('Сохранено', style: TextStyle(color: context.pal.success)),
                ],
              ],
            ),
    );
  }
}
