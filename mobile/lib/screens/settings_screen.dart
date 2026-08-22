import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../widgets/app_drawer.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
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
    return Scaffold(
      appBar: AppBar(title: const Text('Настройки подключения')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Адрес backend API кондитерской CRM (Spring Boot). '
                    'Для Android-эмулятора хост-машина доступна как 10.0.2.2, '
                    'для физического устройства укажите IP компьютера в вашей Wi-Fi сети '
                    '(например http://192.168.1.50:8080/api).',
                    style: TextStyle(color: Colors.grey),
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
                    const Text('Сохранено', style: TextStyle(color: Color(0xFF4C8A5E))),
                  ],
                ],
              ),
            ),
    );
  }
}
