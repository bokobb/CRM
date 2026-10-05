import 'package:flutter/material.dart';

import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../theme/app_theme.dart';

class WarehouseScreen extends StatelessWidget {
  const WarehouseScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Склад')),
      drawer: const AppDrawer(currentRoute: '/warehouse'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/warehouse'),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
        children: [
          AppListTile(
            leadingIcon: Icons.inventory_2_outlined,
            title: 'Остатки',
            subtitle: 'Готовая продукция на складе',
            trailing: Icon(Icons.chevron_right, color: context.pal.muted),
            onTap: () => Navigator.of(context).pushNamed('/warehouse/stock'),
          ),
          AppListTile(
            leadingIcon: Icons.add_box_outlined,
            leadingColor: context.pal.success,
            title: 'Поступления',
            subtitle: 'Приход готовой продукции на склад',
            trailing: Icon(Icons.chevron_right, color: context.pal.muted),
            onTap: () => Navigator.of(context).pushNamed('/warehouse/receipts'),
          ),
          AppListTile(
            leadingIcon: Icons.remove_circle_outline,
            leadingColor: context.pal.danger,
            title: 'Списание товара',
            subtitle: 'Порча, недостача и другие списания',
            trailing: Icon(Icons.chevron_right, color: context.pal.muted),
            onTap: () => Navigator.of(context).pushNamed('/warehouse/writeoffs'),
          ),
        ],
      ),
    );
  }
}
