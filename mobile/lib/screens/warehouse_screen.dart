import 'package:flutter/material.dart';

import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';

class WarehouseScreen extends StatelessWidget {
  const WarehouseScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Склад')),
      drawer: const AppDrawer(currentRoute: '/warehouse'),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
        children: [
          AppListTile(
            leadingIcon: Icons.inventory_2_outlined,
            title: 'Остатки',
            subtitle: 'Готовая продукция на складе',
            trailing: const Icon(Icons.chevron_right, color: Colors.grey),
            onTap: () => Navigator.of(context).pushNamed('/warehouse/stock'),
          ),
          AppListTile(
            leadingIcon: Icons.add_box_outlined,
            leadingColor: const Color(0xFF3F8F5B),
            title: 'Поступления',
            subtitle: 'Приход сырья или готовой продукции',
            trailing: const Icon(Icons.chevron_right, color: Colors.grey),
            onTap: () => Navigator.of(context).pushNamed('/warehouse/receipts'),
          ),
          AppListTile(
            leadingIcon: Icons.remove_circle_outline,
            leadingColor: const Color(0xFFB8433F),
            title: 'Списание товара',
            subtitle: 'Порча, недостача и другие списания',
            trailing: const Icon(Icons.chevron_right, color: Colors.grey),
            onTap: () => Navigator.of(context).pushNamed('/warehouse/writeoffs'),
          ),
        ],
      ),
    );
  }
}
