import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/connection_sheet.dart';
import 'theme_settings_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final role = ApiClient.currentSession?.role;
    final canManageBusiness = role == 'ADMIN' || role == 'SALES' || role == 'ACCOUNTANT';
    final canManageAdmin = role == 'ADMIN' || role == 'ACCOUNTANT';
    // Контрагенты (поставщики сырья) — те же роли, что управляют ими на вебе (см.
    // CounterpartyController @PreAuthorize), не входят ни в одну группу выше: WAREHOUSE_RAW
    // не управляет бизнес-справочниками (клиенты/товары/цены) и не админ.
    final canManageCounterparties = role == 'ADMIN' || role == 'WAREHOUSE_RAW';
    return Scaffold(
      appBar: AppBar(title: const Text('Настройки')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/settings'),
      body: ListView(
        children: [
          ListTile(
            leading: const Icon(Icons.palette_outlined),
            title: const Text('Оформление'),
            subtitle: const Text('Темы: дневная, ночная, режим'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const ThemeSettingsScreen()),
            ),
          ),
          const Divider(height: 1),
          ListTile(
            leading: const Icon(Icons.wifi_outlined),
            title: const Text('Подключение'),
            subtitle: const Text('Адрес сервера (Base URL)'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => showModalBottomSheet<void>(
              context: context,
              isScrollControlled: true,
              builder: (context) => const ConnectionSheet(),
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
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.chat_outlined),
              title: const Text('WhatsApp-сессия'),
              subtitle: const Text('Статус подключения, разрыв и новый QR'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/settings/whatsapp'),
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
          if (canManageCounterparties) ...[
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.handshake_outlined),
              title: const Text('Контрагенты'),
              subtitle: const Text('Поставщики сырья для закупок'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).pushNamed('/settings/counterparties'),
            ),
          ],
        ],
      ),
    );
  }
}

