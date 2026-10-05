import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../theme/app_layout.dart';
import '../theme/app_theme.dart';
import 'app_drawer.dart';

/// Нижнее меню (Настройки › Оформление › Нижнее меню): три первых раздела роли + «Ещё»,
/// которое открывает обычное боковое меню. Выключено — ничего не занимает.
class AppBottomNav extends StatelessWidget {
  final String currentRoute;

  const AppBottomNav({super.key, required this.currentRoute});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: LayoutController.bottomNav,
      builder: (context, _) {
        if (!LayoutController.bottomNav.value) return const SizedBox.shrink();
        final items = AppDrawer.menuForRole(ApiClient.currentSession?.role).take(3).toList();
        final selected = items.indexWhere((i) => i.$1 == currentRoute);
        final p = context.pal;
        return NavigationBarTheme(
          data: NavigationBarThemeData(
            backgroundColor: p.card,
            indicatorColor: p.tint(p.accent),
            surfaceTintColor: Colors.transparent,
            labelTextStyle: WidgetStatePropertyAll(TextStyle(fontSize: 12, color: p.ink)),
          ),
          child: DecoratedBox(
            decoration: BoxDecoration(border: Border(top: BorderSide(color: p.line))),
            child: NavigationBar(
              height: 64,
              selectedIndex: selected < 0 ? items.length : selected,
              onDestinationSelected: (i) {
                if (i == items.length) {
                  Scaffold.of(context).openDrawer();
                } else if (items[i].$1 != currentRoute) {
                  Navigator.of(context).pushReplacementNamed(items[i].$1);
                }
              },
              destinations: [
                for (final it in items) NavigationDestination(icon: Icon(it.$3), label: it.$2),
                const NavigationDestination(icon: Icon(Icons.menu), label: 'Ещё'),
              ],
            ),
          ),
        );
      },
    );
  }
}
