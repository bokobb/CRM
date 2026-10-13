import 'package:flutter/material.dart';

import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_choice_chip.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/searchable_app_bar.dart';
import '../theme/app_theme.dart';

class ProductsScreen extends StatefulWidget {
  const ProductsScreen({super.key});

  @override
  State<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends State<ProductsScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;
  String _query = '';
  bool _importing = false;

  /// «Без объёма коробки» — товары на продажу без объёма коробки: без него не считается объём
  /// заказа на сайте (история, корзина) и в бланке накладной.
  bool _noVolume = false;

  static bool _missingVolume(Product p) => !p.semiFinished && (p.boxVolumeM3 == null || p.boxVolumeM3! <= 0);

  @override
  void initState() {
    super.initState();
    _future = _api.getProducts(includeSemiFinished: true);
  }

  Future<void> _refresh() async {
    setState(() {
      _future = _api.getProducts(includeSemiFinished: true);
    });
    await _future;
  }

  List<Product> _filter(List<Product> products) {
    final q = _query.toLowerCase();
    return products
        .where((p) => !_noVolume || _missingVolume(p))
        .where((p) => q.isEmpty || p.name.toLowerCase().contains(q))
        .toList();
  }

  Widget _chips(List<Product> all) => SizedBox(
        height: 48,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          children: [
            AppChoiceChip(label: 'Все', selected: !_noVolume, onTap: () => setState(() => _noVolume = false)),
            AppChoiceChip(
              label: 'Без объёма коробки · ${all.where(_missingVolume).length}',
              selected: _noVolume,
              onTap: () => setState(() => _noVolume = true),
            ),
          ],
        ),
      );

  Future<void> _delete(Product p) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить товар?'),
        content: Text(p.name),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed == true) {
      await _api.deleteProduct(p.id);
      _refresh();
    }
  }

  Future<bool> _confirmImport(String message) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Импорт из 1С'),
        content: Text(message),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Продолжить')),
        ],
      ),
    );
    return confirmed == true;
  }

  /// "Импортировать спецификации из 1С" — на экране рецептуры конкретного товара (см.
  /// RecipeScreen), здесь осталось только подразделение (единственное, что относится к списку
  /// товаров целиком, а не к рецептуре одного товара).
  Future<void> _importDepartments() async {
    if (!await _confirmImport(
        'Определить подразделение (Корпус А/B/С/D) для каждого товара по истории документов в 1С?')) {
      return;
    }
    setState(() => _importing = true);
    try {
      final r = await _api.importDepartmentsFromOneC();
      final message = 'Подразделение обновлено у товаров: ${r.updated}. '
          'Без истории документов в 1С: ${r.skippedNoHistory}. Не найдено по названию: ${r.skippedNoOneCMatch}';
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
      await _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    } finally {
      if (mounted) setState(() => _importing = false);
    }
  }

  /// Выбор срока: товар пропадёт с витрины и из разбора заказов WhatsApp, сотрудники продолжат
  /// его видеть. «До даты» — включительно, со следующего дня товар вернётся клиентам сам.
  Future<void> _hideFromClients(Product p) async {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: Text('Скрыть «${p.name}» от клиентов'),
              subtitle: const Text('Пропадёт с витрины и из заказов WhatsApp. Сотрудники продолжат его видеть.'),
            ),
            ListTile(
              leading: const Icon(Icons.event_outlined),
              title: const Text('На 1 месяц'),
              onTap: () => Navigator.pop(context, '1m'),
            ),
            ListTile(
              leading: const Icon(Icons.event_outlined),
              title: const Text('На 2 месяца'),
              onTap: () => Navigator.pop(context, '2m'),
            ),
            ListTile(
              leading: const Icon(Icons.edit_calendar_outlined),
              title: const Text('До выбранной даты…'),
              onTap: () => Navigator.pop(context, 'date'),
            ),
            ListTile(
              leading: const Icon(Icons.visibility_off_outlined),
              title: const Text('Пока не верну вручную'),
              onTap: () => Navigator.pop(context, 'forever'),
            ),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;
    DateTime? until;
    switch (choice) {
      case '1m':
        until = DateTime(today.year, today.month + 1, today.day);
      case '2m':
        until = DateTime(today.year, today.month + 2, today.day);
      case 'date':
        until = await showDatePicker(
          context: context,
          initialDate: DateTime(today.year, today.month + 2, today.day),
          firstDate: today,
          lastDate: DateTime(today.year + 2),
          helpText: 'Скрыть по какую дату',
        );
        if (until == null) return;
    }
    await _setHidden(p, hidden: true, until: until);
  }

  Future<void> _setHidden(Product p, {required bool hidden, DateTime? until}) async {
    try {
      await _api.setProductHiddenFromClients(p.id, hidden: hidden, until: until);
      if (!mounted) return;
      final message = !hidden
          ? '«${p.name}» снова виден клиентам'
          : until == null
              ? '«${p.name}» скрыт от клиентов'
              : '«${p.name}» скрыт от клиентов до ${formatDate(until)}';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
      _refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final isAdmin = ApiClient.currentSession?.role == 'ADMIN';
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Товары',
        hintText: 'Название товара...',
        onQueryChanged: (v) => setState(() => _query = v),
        extraActions: [
          if (isAdmin)
            IconButton(
              icon: _importing
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.sync),
              tooltip: 'Импортировать подразделения из 1С',
              onPressed: _importing ? null : _importDepartments,
            ),
        ],
      ),
      drawer: const AppDrawer(currentRoute: '/products'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/products'),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          await Navigator.of(context).pushNamed('/products/new');
          _refresh();
        },
        child: const Icon(Icons.add),
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Product>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final products = _filter(snapshot.data!);
            if (products.isEmpty) {
              return ListView(children: [
                _chips(snapshot.data!),
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(_query.isEmpty && !_noVolume ? 'Товаров пока нет' : 'Ничего не найдено',
                        style: TextStyle(color: context.pal.muted)),
                  ),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: products.length + 1,
              itemBuilder: (context, index) {
                if (index == 0) return _chips(snapshot.data!);
                final p = products[index - 1];
                return AppListTile(
                  leading: ProductAvatar(name: p.name, imageUrl: p.imageUrl),
                  title: p.name,
                  subtitle: [
                    if (p.category != null) p.category!,
                    formatMoney(p.price),
                    if (p.unit != null && p.unit!.isNotEmpty) p.unit!,
                    if (oneCDepartmentNames[p.oneCDepartmentKey] != null) oneCDepartmentNames[p.oneCDepartmentKey]!,
                    if (!p.visibleToClients)
                      p.hiddenFromClientsUntil != null
                          ? 'скрыт от клиентов до ${formatDate(p.hiddenFromClientsUntil!)}'
                          : 'скрыт от клиентов',
                  ].join(' · '),
                  trailing: PopupMenuButton<String>(
                    onSelected: (value) async {
                      if (value == 'recipe') {
                        await Navigator.of(context).pushNamed('/products/recipe', arguments: p.id);
                        _refresh();
                      } else if (value == 'edit') {
                        await Navigator.of(context).pushNamed('/products/edit', arguments: p.id);
                        _refresh();
                      } else if (value == 'delete') {
                        _delete(p);
                      } else if (value == 'hide') {
                        _hideFromClients(p);
                      } else if (value == 'show') {
                        _setHidden(p, hidden: false);
                      }
                    },
                    itemBuilder: (context) => [
                      const PopupMenuItem(value: 'recipe', child: Text('Рецептура')),
                      const PopupMenuItem(value: 'edit', child: Text('Изменить')),
                      if (isAdmin && p.visibleToClients)
                        const PopupMenuItem(value: 'hide', child: Text('Скрыть от клиентов')),
                      if (isAdmin && !p.visibleToClients)
                        const PopupMenuItem(value: 'show', child: Text('Показать клиентам')),
                      if (isAdmin) const PopupMenuItem(value: 'delete', child: Text('Удалить')),
                    ],
                  ),
                  onTap: () async {
                    await Navigator.of(context).pushNamed('/products/edit', arguments: p.id);
                    _refresh();
                  },
                );
              },
            );
          },
        ),
      ),
    );
  }
}
