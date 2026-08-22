import 'package:flutter/material.dart';

class AppDrawer extends StatelessWidget {
  final String currentRoute;

  const AppDrawer({super.key, required this.currentRoute});

  static const _items = [
    ('/', 'Дашборд', Icons.dashboard_outlined),
    ('/clients', 'Клиенты', Icons.people_outline),
    ('/products', 'Товары и рецепты', Icons.cake_outlined),
    ('/ingredients', 'Склад', Icons.inventory_2_outlined),
    ('/orders', 'Заказы', Icons.receipt_long_outlined),
    ('/production', 'Производство', Icons.factory_outlined),
    ('/notifications', 'Уведомления', Icons.notifications_none),
  ];

  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: SafeArea(
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              child: Row(
                children: const [
                  Text('🍰', style: TextStyle(fontSize: 28)),
                  SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Кондитерская CRM',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            for (final item in _items)
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
          ],
        ),
      ),
    );
  }
}
