import 'package:flutter/material.dart';

import '../models/vacancy.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';

/// «Вакансии» — то, что видно на странице сайта /vacancies (ADMIN/ACCOUNTANT). Порядок на сайте —
/// перетаскиванием, «на сайте» — переключателем. Вакансию с откликами не удалить, только скрыть.
/// Сами отклики отрабатываются в «Отклики и заявки» (leads_screen.dart).
class VacanciesScreen extends StatefulWidget {
  const VacanciesScreen({super.key});

  @override
  State<VacanciesScreen> createState() => _VacanciesScreenState();
}

class _VacanciesScreenState extends State<VacanciesScreen> {
  final _api = ApiClient();
  List<Vacancy>? _vacancies;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final v = await _api.getVacancies();
      if (!mounted) return;
      setState(() {
        _vacancies = v;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
  }

  void _snack(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _editVacancy(Vacancy? v) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _VacancyForm(vacancy: v),
    );
    if (saved == true) _load();
  }

  Future<void> _toggle(Vacancy v, bool active) async {
    try {
      await _api.setVacancyActive(v.id, active);
      _load();
    } catch (e) {
      _snack('Не удалось: $e');
    }
  }

  Future<void> _reorder(int oldIndex, int newIndex) async {
    final list = [..._vacancies!];
    list.insert(newIndex, list.removeAt(oldIndex));
    setState(() => _vacancies = list);
    try {
      await _api.reorderVacancies([for (final v in list) v.id]);
    } catch (e) {
      _snack('Порядок не сохранился: $e');
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Вакансии')),
      drawer: const AppDrawer(currentRoute: '/vacancies'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/vacancies'),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _editVacancy(null),
        tooltip: 'Добавить вакансию',
        child: const Icon(Icons.add),
      ),
      body: _error != null
          ? ErrorView(error: _error!, onRetry: _load)
          : _vacancies == null
              ? const Center(child: CircularProgressIndicator())
              : _vacancyList(),
    );
  }

  Widget _empty(String text) => ListView(children: [
        Padding(
          padding: const EdgeInsets.all(32),
          child: Text(text, textAlign: TextAlign.center, style: TextStyle(color: context.pal.muted)),
        ),
      ]);

  Widget _vacancyList() {
    final list = _vacancies!;
    if (list.isEmpty) {
      return RefreshIndicator(
        onRefresh: _load,
        child: _empty('Вакансий пока нет. Добавьте кнопкой «+» — она сразу появится на сайте на странице «Вакансии»'),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ReorderableListView.builder(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 88),
        itemCount: list.length,
        onReorderItem: _reorder,
        header: Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
          child: Text('Порядок как на сайте — перетащите за ≡',
              style: TextStyle(color: context.pal.muted, fontSize: 12.5)),
        ),
        itemBuilder: (context, i) {
          final v = list[i];
          return Card(
            key: ValueKey(v.id),
            margin: const EdgeInsets.only(bottom: 8),
            child: ListTile(
              contentPadding: const EdgeInsets.fromLTRB(4, 4, 8, 4),
              leading: ReorderableDragStartListener(
                index: i,
                child: const Padding(padding: EdgeInsets.all(8), child: Icon(Icons.drag_handle)),
              ),
              title: Text(v.title,
                  style: TextStyle(color: v.active ? null : context.pal.muted, fontWeight: FontWeight.w600)),
              subtitle: Text(
                [v.schedule, v.salary ?? 'по договорённости', if (!v.active) 'скрыта'].join(' · '),
                style: TextStyle(color: context.pal.muted),
              ),
              trailing: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Switch(value: v.active, onChanged: (a) => _toggle(v, a)),
                  Text('на сайте', style: TextStyle(fontSize: 10.5, color: context.pal.muted)),
                ],
              ),
              onTap: () => _editVacancy(v),
            ),
          );
        },
      ),
    );
  }
}

class _VacancyForm extends StatefulWidget {
  final Vacancy? vacancy;

  const _VacancyForm({this.vacancy});

  @override
  State<_VacancyForm> createState() => _VacancyFormState();
}

class _VacancyFormState extends State<_VacancyForm> {
  final _api = ApiClient();
  late final _title = TextEditingController(text: widget.vacancy?.title ?? '');
  late final _summary = TextEditingController(text: widget.vacancy?.summary ?? '');
  late final _schedule = TextEditingController(text: widget.vacancy?.schedule ?? '');
  late final _experience = TextEditingController(text: widget.vacancy?.experience ?? '');
  late final _salary = TextEditingController(text: widget.vacancy?.salary ?? '');
  late final _duties = TextEditingController(text: widget.vacancy?.duties ?? '');
  late final _requirements = TextEditingController(text: widget.vacancy?.requirements ?? '');
  late final _conditions = TextEditingController(text: widget.vacancy?.conditions ?? '');
  late bool _active = widget.vacancy?.active ?? true;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_title, _summary, _schedule, _experience, _salary, _duties, _requirements, _conditions]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (_title.text.trim().isEmpty || _schedule.text.trim().isEmpty || _experience.text.trim().isEmpty) {
      setState(() => _error = 'Заполните название, график и опыт');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await _api.saveVacancy(widget.vacancy?.id, {
        'title': _title.text.trim(),
        'summary': _summary.text.trim(),
        'schedule': _schedule.text.trim(),
        'experience': _experience.text.trim(),
        'salary': _salary.text.trim(),
        'duties': _duties.text.trim(),
        'requirements': _requirements.text.trim(),
        'conditions': _conditions.text.trim(),
        'active': _active,
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Удалить вакансию?'),
        content: const Text('Она исчезнет с сайта. Если на неё уже были отклики, удалить нельзя — только скрыть.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await _api.deleteVacancy(widget.vacancy!.id);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() => _error = e.toString());
    }
  }

  Widget _field(TextEditingController c, String label, {int? max, String? hint, String? helper, bool multi = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TextField(
        controller: c,
        maxLength: max,
        minLines: multi ? 3 : 1,
        maxLines: multi ? 8 : 1,
        textCapitalization: TextCapitalization.sentences,
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          helperText: helper,
          border: const OutlineInputBorder(),
          counterText: '',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.vacancy == null ? 'Новая вакансия' : 'Вакансия', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 14),
            _field(_title, 'Название', max: 80, hint: 'Пекарь-кондитер'),
            _field(_summary, 'Строка под названием (необязательно)', max: 140, hint: 'Выпечка коржиков и кексов в цехе'),
            _field(_schedule, 'График', max: 60, hint: '5/2, 8:00–17:00'),
            _field(_experience, 'Опыт', max: 60, hint: 'от 1 года · не нужен, научим'),
            _field(_salary, 'Зарплата (необязательно)', max: 60, hint: 'от 250 000 ₸', helper: 'Пусто — на сайте «по договорённости»'),
            _field(_duties, 'Что делать', multi: true, helper: 'Один пункт — одна строка'),
            _field(_requirements, 'Кого ищем', multi: true, helper: 'Один пункт — одна строка'),
            _field(_conditions, 'Что предлагаем', multi: true, helper: 'Один пункт — одна строка'),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Показывать на сайте'),
              value: _active,
              onChanged: (v) => setState(() => _active = v),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 14),
            FilledButton(onPressed: _saving ? null : _save, child: Text(_saving ? 'Сохраняю…' : 'Сохранить')),
            if (widget.vacancy != null) TextButton(onPressed: _saving ? null : _delete, child: const Text('Удалить')),
          ],
        ),
      ),
    );
  }
}
