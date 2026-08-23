import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../widgets/app_drawer.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final role = ApiClient.currentSession?.role;
    final canManageBusiness = role == 'ADMIN' || role == 'SALES' || role == 'ACCOUNTANT';
    final canManageAdmin = role == 'ADMIN' || role == 'ACCOUNTANT';
    return Scaffold(
      appBar: AppBar(title: const Text('Настройки')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      body: ListView(
        children: [
          ListTile(
            leading: const Icon(Icons.wifi_outlined),
            title: const Text('Подключение'),
            subtitle: const Text('Адрес сервера (Base URL)'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => showModalBottomSheet<void>(
              context: context,
              isScrollControlled: true,
              builder: (context) => const _ConnectionSheet(),
            ),
          ),
          if (canManageAdmin) ...[
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.storefront_outlined),
              title: const Text('Профиль компании'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/settings/company'),
            ),
          ],
          if (canManageBusiness) ...[
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.people_outline),
              title: const Text('Клиенты'),
              subtitle: const Text('Создать нового клиента'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/clients'),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.cake_outlined),
              title: const Text('Товар'),
              subtitle: const Text('Добавить новый товар'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/products'),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.sell_outlined),
              title: const Text('Цены'),
              subtitle: const Text('Индивидуальные цены клиентов'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/settings/prices'),
            ),
          ],
          if (canManageAdmin) ...[
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.badge_outlined),
              title: const Text('Пользователи'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/settings/users'),
            ),
          ],
        ],
      ),
    );
  }
}

class _ConnectionSheet extends StatefulWidget {
  const _ConnectionSheet();

  @override
  State<_ConnectionSheet> createState() => _ConnectionSheetState();
}

class _ConnectionSheetState extends State<_ConnectionSheet> {
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
    );
  }
}
