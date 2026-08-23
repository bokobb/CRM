import 'package:flutter/material.dart';

import '../services/api_client.dart';

typedef _Item = (String route, String label, IconData icon);

const _dashboard = ('/', 'Дашборд', Icons.dashboard_outlined);
const _clients = ('/clients', 'Клиенты', Icons.people_outline);
const _products = ('/products', 'Товары и рецепты', Icons.cake_outlined);
const _ingredients = ('/ingredients', 'Склад', Icons.inventory_2_outlined);
const _orders = ('/orders', 'Заказы', Icons.receipt_long_outlined);
const _production = ('/production', 'Производство', Icons.factory_outlined);
const _notifications = ('/notifications', 'Уведомления', Icons.notifications_none);

class AppDrawer extends StatelessWidget {
  final String currentRoute;

  const AppDrawer({super.key, required this.currentRoute});

  static List<_Item> _itemsForRole(String? role) {
    switch (role) {
      case 'ADMIN':
        return [_dashboard, _clients, _products, _ingredients, _orders, _production, _notifications];
      case 'SALES':
        return [_dashboard, _clients, _products, _orders, _notifications];
      case 'WAREHOUSE_FG':
        return [_orders, _notifications];
      case 'ACCOUNTANT':
        return [_orders, _notifications];
      default:
        return [_dashboard];
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ApiClient.currentSession;
    final items = _itemsForRole(session?.role);
    return Drawer(
      child: SafeArea(
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              child: Row(
                children: [
                  const Text('🍰', style: TextStyle(fontSize: 28)),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Кондитерская CRM', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        if (session != null)
                          Text(
                            '${session.fullName} · ${session.roleLabel}',
                            style: const TextStyle(fontSize: 12, color: Colors.grey),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            for (final item in items)
              ListTile(
                leading: Icon(item.$3),
                title: Text(item.$2),
                selected: currentRoute == item.$1,
                onTap: () {
                  Navigator.pop(context);
                  if (currentRoute != item.$1) {
                    Navigator.of(context).pushReplacementNamed(item.$1);
                  }
                },
              ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.settings_outlined),
              title: const Text('Настройки подключения'),
              selected: currentRoute == '/settings',
              onTap: () {
                Navigator.pop(context);
                if (currentRoute != '/settings') {
                  Navigator.of(context).pushReplacementNamed('/settings');
                }
              },
            ),
            ListTile(
              leading: const Icon(Icons.logout),
              title: const Text('Выйти'),
              onTap: () async {
                await ApiClient().logout();
                if (context.mounted) {
                  Navigator.of(context).pushNamedAndRemoveUntil('/login', (route) => false);
                }
              },
            ),
          ],
        ),
      ),
    );
  }
}
