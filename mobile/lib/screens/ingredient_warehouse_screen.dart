import 'package:flutter/material.dart';

import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../theme/app_theme.dart';

/// "Склад сырья" — зеркало WarehouseScreen, но для сырья: раньше "Сырьё" в меню вело сразу на
/// остатки, а поступления/списание сырья были смешаны с товаром на страницах "Склада". Теперь
/// у сырья своя тройка страниц, как у готовой продукции.
class IngredientWarehouseScreen extends StatelessWidget {
  const IngredientWarehouseScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Склад сырья')),
      drawer: const AppDrawer(currentRoute: '/ingredients-warehouse'),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
        children: [
          AppListTile(
            leadingIcon: Icons.grain_outlined,
            title: 'Остатки',
            subtitle: 'Сырьё на складе и пороги для уведомлений',
            trailing: Icon(Icons.chevron_right, color: context.pal.muted),
            onTap: () => Navigator.of(context).pushNamed('/ingredients'),
          ),
          AppListTile(
            leadingIcon: Icons.add_box_outlined,
            leadingColor: context.pal.success,
            title: 'Поступления',
            subtitle: 'Приход сырья на склад',
            trailing: Icon(Icons.chevron_right, color: context.pal.muted),
            onTap: () => Navigator.of(context).pushNamed('/ingredients/receipts'),
          ),
          AppListTile(
            leadingIcon: Icons.remove_circle_outline,
            leadingColor: context.pal.danger,
            title: 'Списание',
            subtitle: 'Порча, недостача и другие списания сырья',
            trailing: Icon(Icons.chevron_right, color: context.pal.muted),
            onTap: () => Navigator.of(context).pushNamed('/ingredients/writeoffs'),
          ),
        ],
      ),
    );
  }
}
