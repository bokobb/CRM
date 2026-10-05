import 'package:flutter/material.dart';

import '../theme/app_layout.dart';
import '../theme/app_theme.dart';

/// «Настройки › Оформление»: режим (авто / день / ночь), дневная и ночная тема. Выбор сразу
/// применяется ко всему приложению и хранится на телефоне (см. ThemeController).
class ThemeSettingsScreen extends StatelessWidget {
  const ThemeSettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([ThemeController.listenable, LayoutController.listenable]),
      builder: (context, _) {
        final p = context.pal;
        Widget header(String text) => Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
              child: Text(
                text.toUpperCase(),
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.8, color: p.muted),
              ),
            );
        return Scaffold(
          appBar: AppBar(title: const Text('Оформление')),
          body: ListView(
            padding: const EdgeInsets.only(bottom: 24),
            children: [
              header('Режим'),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: SegmentedButton<ThemeMode>(
                  showSelectedIcon: false,
                  segments: const [
                    ButtonSegment(value: ThemeMode.system, label: Text('Авто'), icon: Icon(Icons.brightness_auto_outlined)),
                    ButtonSegment(value: ThemeMode.light, label: Text('День'), icon: Icon(Icons.light_mode_outlined)),
                    ButtonSegment(value: ThemeMode.dark, label: Text('Ночь'), icon: Icon(Icons.dark_mode_outlined)),
                  ],
                  selected: {ThemeController.mode.value},
                  onSelectionChanged: (s) => ThemeController.setMode(s.first),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                child: Text(
                  'Авто — как в настройках телефона. Солнце/луна в шапке дашборда и «Ночная тема» в меню '
                  'переключают между выбранными ниже темами.',
                  style: TextStyle(fontSize: 12.5, color: p.muted),
                ),
              ),
              header('Компоновка'),
              _ChoiceGroup<DashboardView>(
                title: 'Дашборд (стартовая страница, листается свайпом)',
                value: LayoutController.dashboard.value,
                onChanged: LayoutController.setDashboard,
                options: const [
                  (DashboardView.standard, 'Обычный', 'Продажа, заказы, последние заказы'),
                  (DashboardView.chart, 'График', 'Продажи за неделю по дням и «Требует внимания»'),
                  (DashboardView.feed, 'Лента дня', 'Все события дня по времени'),
                  (DashboardView.raw, 'Сырьё', 'Заявки на сырьё и низкие остатки'),
                  (DashboardView.rawChart, 'Сырьё · график', 'Замесы по дням и «Требует внимания» по сырью'),
                  (DashboardView.sales, 'Продажи (склад ГП)', 'Страница продаж на дашборде склада ГП'),
                ],
              ),
              _ChoiceGroup<OrdersView>(
                title: 'Заказы',
                value: LayoutController.orders.value,
                onChanged: LayoutController.setOrders,
                options: const [
                  (OrdersView.list, 'Список', 'Карточки заказов'),
                  (OrdersView.table, 'Таблица', 'Плотные строки и «Итого» — для офиса'),
                  (OrdersView.board, 'Доска по статусам', 'Колонки «Сборка → К отгрузке → Отгружен»'),
                ],
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                child: Card(
                  margin: EdgeInsets.zero,
                  child: SwitchListTile(
                    title: const Text('Нижнее меню'),
                    subtitle: const Text('Главные разделы — вкладками внизу экрана'),
                    value: LayoutController.bottomNav.value,
                    onChanged: LayoutController.setBottomNav,
                  ),
                ),
              ),
              header('Дневная тема'),
              for (final t in AppPalette.lightThemes)
                _ThemeTile(
                  palette: t,
                  selected: ThemeController.day.value.id == t.id,
                  onTap: () async {
                    await ThemeController.setDay(t);
                    if (ThemeController.mode.value == ThemeMode.dark) await ThemeController.setMode(ThemeMode.light);
                  },
                ),
              header('Ночная тема'),
              for (final t in AppPalette.darkThemes)
                _ThemeTile(
                  palette: t,
                  selected: ThemeController.night.value.id == t.id,
                  onTap: () async {
                    await ThemeController.setNight(t);
                    if (ThemeController.mode.value == ThemeMode.light) await ThemeController.setMode(ThemeMode.dark);
                  },
                ),
            ],
          ),
        );
      },
    );
  }
}

/// Строка выбора темы: миниатюра в её собственных цветах (фон, карточка, текст, акцент) и название.
class _ThemeTile extends StatelessWidget {
  final AppPalette palette;
  final bool selected;
  final VoidCallback onTap;

  const _ThemeTile({required this.palette, required this.selected, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final t = palette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Material(
        color: p.card,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: selected ? p.accent : p.line, width: selected ? 2 : 1),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Container(
                  width: 92,
                  height: 60,
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: t.bg,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: p.line),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (t.header != null) ...[
                        Container(height: 8, decoration: BoxDecoration(color: t.header, borderRadius: BorderRadius.circular(3))),
                        const SizedBox(height: 4),
                      ],
                      Expanded(
                        child: Container(
                          padding: const EdgeInsets.all(6),
                          decoration: BoxDecoration(
                            color: t.card,
                            borderRadius: BorderRadius.circular(6),
                            border: t.bordered ? Border.all(color: t.line) : null,
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Container(width: 44, height: 5, color: t.ink),
                              const SizedBox(height: 4),
                              Container(width: 30, height: 4, color: t.muted),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 5),
                      Container(
                        height: 9,
                        decoration: BoxDecoration(color: t.accent, borderRadius: BorderRadius.circular(3)),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Text(t.name, style: const TextStyle(fontSize: 15.5, fontWeight: FontWeight.w500)),
                ),
                Icon(
                  selected ? Icons.radio_button_checked : Icons.radio_button_off,
                  color: selected ? p.accent : p.muted,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Группа вариантов одной компоновки — карточка с радиокнопками.
class _ChoiceGroup<T> extends StatelessWidget {
  final String title;
  final T value;
  final ValueChanged<T> onChanged;
  final List<(T, String, String)> options;

  const _ChoiceGroup({required this.title, required this.value, required this.onChanged, required this.options});

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Card(
        margin: EdgeInsets.zero,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            ),
            RadioGroup<T>(
              groupValue: value,
              onChanged: (v) {
                if (v != null) onChanged(v);
              },
              child: Column(
                children: [
                  for (final o in options)
                    RadioListTile<T>(
                      value: o.$1,
                      title: Text(o.$2),
                      subtitle: Text(o.$3, style: TextStyle(fontSize: 12.5, color: p.muted)),
                      activeColor: p.accent,
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
