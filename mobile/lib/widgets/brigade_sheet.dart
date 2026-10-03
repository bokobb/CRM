import 'package:flutter/material.dart';

import '../models/employee.dart';
import '../models/production_cost.dart';
import '../services/api_client.dart';
import 'hidden_search.dart';
import 'tech_card_style.dart';

/// Выбор бригады заявки: все работающие сотрудники кнопками-переключателями. Кто в этот день уже
/// в другой бригаде — кнопка неактивна с номером той заявки (один человек — одна бригада за день).
/// Возвращает обновлённый расчёт заявки или null, если закрыли без сохранения.
Future<ProductionRequestCost?> showBrigadeSheet(
  BuildContext context, {
  required int requestId,
  required String requestTitle,
  required DateTime date,
  required List<int> current,
}) => showModalBottomSheet<ProductionRequestCost>(
  context: context,
  isScrollControlled: true,
  showDragHandle: true,
  backgroundColor: TK.paper,
  builder: (_) => _BrigadeSheet(requestId: requestId, requestTitle: requestTitle, date: date, current: current),
);

class _BrigadeSheet extends StatefulWidget {
  final int requestId;
  final String requestTitle;
  final DateTime date;
  final List<int> current;

  const _BrigadeSheet({required this.requestId, required this.requestTitle, required this.date, required this.current});

  @override
  State<_BrigadeSheet> createState() => _BrigadeSheetState();
}

class _BrigadeSheetState extends State<_BrigadeSheet> {
  final _api = ApiClient();
  late final Set<int> _selected = {...widget.current};
  late Future<(List<Employee>, Map<int, ProductionCostRequest>)> _future;
  bool _saving = false;
  String? _error;
  final _search = HiddenSearch();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(List<Employee>, Map<int, ProductionCostRequest>)> _load() async {
    final results = await Future.wait([_api.getEmployees(), _api.getProductionCost(widget.date)]);
    final employees = (results[0] as List<Employee>).where((e) => e.active || widget.current.contains(e.id)).toList();
    final day = results[1] as ProductionCost;
    final busy = <int, ProductionCostRequest>{
      for (final r in day.requests)
        if (r.id != widget.requestId)
          for (final m in r.brigade) m.employeeId: r,
    };
    return (employees, busy);
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final result = await _api.setBrigade(widget.requestId, _selected.toList());
      if (mounted) Navigator.of(context).pop(result);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
      child: FutureBuilder<(List<Employee>, Map<int, ProductionCostRequest>)>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const SizedBox(height: 160, child: Center(child: CircularProgressIndicator()));
          }
          if (snapshot.hasError) {
            return Padding(padding: const EdgeInsets.all(16), child: Text(snapshot.error.toString()));
          }
          final (employees, busy) = snapshot.data!;
          // Выбранные видны всегда, остальные — по поиску (часть ФИО, без учёта регистра).
          final shown = employees.where((e) => _selected.contains(e.id) || _search.matches(e.fullName)).toList();
          return Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Бригада', style: TK.h1.copyWith(fontSize: 18)),
                        const SizedBox(height: 2),
                        Text('№${widget.requestId} · ${widget.requestTitle}', style: TK.note),
                      ],
                    ),
                  ),
                  if (employees.length > 6) _search.button(setState, color: TK.accent),
                ],
              ),
              _search.field(setState),
              const SizedBox(height: 12),
              if (employees.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    'Сотрудников нет — добавьте их в меню «Сотрудники»',
                    style: TK.note,
                    textAlign: TextAlign.center,
                  ),
                )
              else
                ConstrainedBox(
                  constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.5),
                  child: SingleChildScrollView(
                    child: Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final e in shown)
                          _EmployeeChip(
                            name: e.fullName,
                            highlight: _search.query,
                            selected: _selected.contains(e.id),
                            busyIn: busy[e.id],
                            onTap: busy[e.id] != null
                                ? null
                                : () {
                                    setState(() {
                                      if (!_selected.remove(e.id)) _selected.add(e.id);
                                    });
                                    // Выбрали по поиску — набранное сбрасывается под следующего.
                                    if (_search.query.isNotEmpty) _search.clear(setState);
                                  },
                          ),
                      ],
                    ),
                  ),
                ),
              if (_error != null) ...[const SizedBox(height: 10), Text(_error!, style: const TextStyle(color: TK.bad))],
              const SizedBox(height: 16),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: TK.accent, minimumSize: const Size.fromHeight(46)),
                onPressed: _saving ? null : _save,
                child: Text(_saving ? 'Сохраняю…' : 'Сохранить · ${_selected.length} чел.'),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _EmployeeChip extends StatelessWidget {
  final String name;
  final String highlight;
  final bool selected;
  final ProductionCostRequest? busyIn;
  final VoidCallback? onTap;

  const _EmployeeChip({required this.name, required this.selected, this.highlight = '', this.busyIn, this.onTap});

  @override
  Widget build(BuildContext context) {
    final busy = busyIn != null;
    return Material(
      color: selected ? TK.accent : (busy ? TK.soft : TK.paper),
      shape: StadiumBorder(side: BorderSide(color: selected ? TK.accent : TK.line)),
      child: InkWell(
        customBorder: const StadiumBorder(),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (selected) ...[const Icon(Icons.check, size: 16, color: Colors.white), const SizedBox(width: 4)],
              Text.rich(
                TextSpan(children: highlightSpans(name, highlight)),
                style: TextStyle(
                  fontFamily: TK.body,
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: selected ? Colors.white : (busy ? TK.muted : TK.ink),
                ),
              ),
              if (busy) ...[
                const SizedBox(width: 6),
                Text('№${busyIn!.id}', style: TK.num.copyWith(fontSize: 11.5, color: TK.muted)),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
