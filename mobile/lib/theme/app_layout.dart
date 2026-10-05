import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Компоновки экранов (Настройки › Оформление › Компоновка) — хранятся на телефоне, как темы.
/// Страницы дашборда (листаются свайпом): продажи, график продаж, лента дня, сырьё, график сырья.
enum DashboardView { standard, chart, feed, raw, rawChart }

enum OrdersView { list, table, board }

class LayoutController {
  static final dashboard = ValueNotifier<DashboardView>(DashboardView.standard);
  static final orders = ValueNotifier<OrdersView>(OrdersView.list);
  static final bottomNav = ValueNotifier<bool>(false);
  static final listenable = Listenable.merge([dashboard, orders, bottomNav]);

  static const _dashKey = 'layout_dashboard';
  static const _ordersKey = 'layout_orders';
  static const _navKey = 'layout_bottom_nav';

  static Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      dashboard.value = DashboardView.values.firstWhere(
        (v) => v.name == prefs.getString(_dashKey),
        orElse: () => DashboardView.standard,
      );
      orders.value = OrdersView.values.firstWhere(
        (v) => v.name == prefs.getString(_ordersKey),
        orElse: () => OrdersView.list,
      );
      bottomNav.value = prefs.getBool(_navKey) ?? false;
    } catch (_) {}
  }

  static Future<void> setDashboard(DashboardView v) async {
    dashboard.value = v;
    await _save((p) => p.setString(_dashKey, v.name));
  }

  static Future<void> setOrders(OrdersView v) async {
    orders.value = v;
    await _save((p) => p.setString(_ordersKey, v.name));
  }

  static Future<void> setBottomNav(bool v) async {
    bottomNav.value = v;
    await _save((p) => p.setBool(_navKey, v));
  }

  static Future<void> _save(Future<bool> Function(SharedPreferences) write) async {
    try {
      await write(await SharedPreferences.getInstance());
    } catch (_) {}
  }
}
