import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../services/push_service.dart';

typedef _Item = (String route, String label, IconData icon);

const _dashboard = ('/', 'Дашборд', Icons.dashboard_outlined);
const _orders = ('/orders', 'Заказы', Icons.receipt_long_outlined);
const _clients = ('/clients', 'Клиенты', Icons.people_outline);
const _products = ('/products', 'Товары', Icons.cake_outlined);
const _recipes = ('/recipes', 'Рецепты', Icons.menu_book_outlined);
const _warehouse = ('/warehouse', 'Склад', Icons.inventory_2_outlined);
const _ingredientWarehouse = ('/ingredients-warehouse', 'Склад сырья', Icons.grain_outlined);
const _production = ('/production', 'Производство', Icons.factory_outlined);
const _productionRequests = ('/production-requests', 'Заявки на сырьё', Icons.science_outlined);
const _productionReport = ('/production-requests/report', 'Отчёт', Icons.summarize_outlined);
const _purchases = ('/purchases', 'Покупки', Icons.local_shipping_outlined);
const _debts = ('/debts', 'Долги', Icons.hourglass_bottom_outlined);
const _whatsappInbox = ('/whatsapp-inbox', 'WhatsApp-заказы', Icons.chat_bubble_outline);
const _notifications = (
  '/notifications',
  'Уведомления',
  Icons.notifications_none,
);

class AppDrawer extends StatelessWidget {
  final String currentRoute;

  const AppDrawer({super.key, required this.currentRoute});

  static List<_Item> _itemsForRole(String? role) {
    switch (role) {
      case 'ADMIN':
      case 'ACCOUNTANT':
        // Бухгалтер имеет полный доступ ко всему разделу приложения (кроме кнопок
        // "Заказ собран"/"Отгрузил" — те гейтятся отдельно внутри order_detail_screen.dart).
        // Порядок зеркалит веб-меню (см. fragments/nav.html).
        return [
          _dashboard,
          _orders,
          _productionRequests,
          _purchases,
          _products,
          _recipes,
          _warehouse,
          _ingredientWarehouse,
          _clients,
          _productionReport,
          _production,
          _debts,
          _whatsappInbox,
          _notifications,
        ];
      case 'SALES':
        return [_dashboard, _orders, _clients, _products, _recipes, _whatsappInbox, _notifications];
      case 'WAREHOUSE_FG':
        return [_orders, _productionRequests, _warehouse, _ingredientWarehouse, _productionReport, _notifications];
      case 'WAREHOUSE_RAW':
        // Дашборд по производству (не по заказам — см. DashboardScreen), заявки (сборка по
        // чек-листу), покупки (приход от поставщиков) и склад сырья (остатки/поступления/
        // списание — раньше был только пункт "Списание", теперь та же тройка, что у всех).
        return [_dashboard, _productionRequests, _purchases, _ingredientWarehouse, _notifications];
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
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Image.asset('assets/images/logo-full.png', width: 170),
                  if (session != null) ...[
                    const SizedBox(height: 10),
                    Text(
                      '${session.fullName} · ${session.roleLabel}',
                      style: const TextStyle(
                        fontSize: 12,
                        color: Colors.grey,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const Divider(height: 1),
            for (final item in items)
              ListTile(
                leading: item.$1 == _notifications.$1
                    ? ValueListenableBuilder<int>(
                        valueListenable: PushService.unreadCount,
                        builder: (context, count, _) => Badge(
                          isLabelVisible: count > 0,
                          label: Text('$count'),
                          child: Icon(item.$3),
                        ),
                      )
                    : Icon(item.$3),
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
              title: const Text('Настройки'),
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
                  Navigator.of(context)
                      .pushNamedAndRemoveUntil('/login', (route) => false);
                }
              },
            ),
          ],
        ),
      ),
    );
  }
}
