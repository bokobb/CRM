import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// Табель сотрудников за месяц. Ячейка дня: ✓ пришёл, ✗ не пришёл, В выходной, пусто.
/// Сами собой ставятся ✓ — по бригадам заявок на сырьё, и В — по воскресеньям. Нажатие на
/// ячейку меняет отметку по кругу (перекличка, в т. ч. в воскресенье). «Сохранить» — пустые
/// прошедшие дни становятся ✗. Excel — кнопка в шапке.
class TimesheetScreen extends StatefulWidget {
  const TimesheetScreen({super.key});

  @override
  State<TimesheetScreen> createState() => _TimesheetScreenState();
}

class _TimesheetScreenState extends State<TimesheetScreen> {
  final _api = ApiClient();
  DateTime _month = DateTime(DateTime.now().year, DateTime.now().month);
  Map<String, dynamic>? _sheet;
  Object? _error;
  bool _loading = true;
  bool _saving = false;
  bool _exporting = false;

  /// Изменения до сохранения: "employeeId|день" → статус (null — пусто).
  final Map<String, String?> _changes = {};

  static const _cycle = [null, 'P', 'A', 'O'];
  static const _nameWidth = 150.0;
  static const _cellWidth = 34.0;
  static const _rowHeight = 40.0;

  String get _monthParam => '${_month.year}-${_month.month.toString().padLeft(2, '0')}';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final sheet = await _api.getTimesheet(_monthParam);
      setState(() {
        _sheet = sheet;
        _changes.clear();
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  Future<void> _shiftMonth(int delta) async {
    if (_changes.isNotEmpty && !await _confirmDiscard()) return;
    _month = DateTime(_month.year, _month.month + delta);
    _load();
  }

  Future<bool> _confirmDiscard() async =>
      await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: const Text('Изменения не сохранены'),
          content: const Text('Отметки этого месяца не сохранены. Уйти без сохранения?'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Остаться')),
            TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('Уйти')),
          ],
        ),
      ) ??
      false;

  String? _status(Map<String, dynamic> row, int dayIndex) {
    final key = '${row['employeeId']}|$dayIndex';
    if (_changes.containsKey(key)) return _changes[key];
    return (row['cells'] as List)[dayIndex] as String?;
  }

  bool _isAuto(Map<String, dynamic> row, int dayIndex) {
    final key = '${row['employeeId']}|$dayIndex';
    if (_changes.containsKey(key)) return false;
    return (row['auto'] as List)[dayIndex] as bool? ?? false;
  }

  void _tap(Map<String, dynamic> row, int dayIndex) {
    final current = _status(row, dayIndex);
    final next = _cycle[(_cycle.indexOf(current) + 1) % _cycle.length];
    final key = '${row['employeeId']}|$dayIndex';
    final original = (row['cells'] as List)[dayIndex] as String?;
    setState(() {
      if (next == original && !((row['auto'] as List)[dayIndex] as bool? ?? false)) {
        _changes.remove(key);
      } else {
        _changes[key] = next;
      }
    });
  }

  Future<void> _save() async {
    final sheet = _sheet!;
    final days = sheet['days'] as List;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Сохранить табель?'),
        content: const Text('Пустые ячейки прошедших дней (до вчера) будут отмечены ✗ «не пришёл».'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Сохранить')),
        ],
      ),
    );
    if (ok != true) return;
    final cells = <Map<String, dynamic>>[];
    for (final r in sheet['rows'] as List) {
      final row = r as Map<String, dynamic>;
      for (var i = 0; i < days.length; i++) {
        cells.add({'employeeId': row['employeeId'], 'date': (days[i] as Map)['date'], 'status': _status(row, i)});
      }
    }
    setState(() => _saving = true);
    try {
      final saved = await _api.saveTimesheet(_monthParam, cells);
      setState(() {
        _sheet = saved;
        _changes.clear();
      });
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Табель сохранён')));
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не сохранилось: $e')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _exportExcel() async {
    if (_changes.isNotEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Сначала сохраните табель — в Excel попадёт сохранённое')));
      return;
    }
    setState(() => _exporting = true);
    try {
      final bytes = await _api.getTimesheetXlsx(_monthParam);
      final name = 'Табель_$_monthParam.xlsx';
      await Share.shareXFiles([
        XFile.fromData(bytes,
            mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', name: name),
      ], fileNameOverrides: [name]);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось выгрузить: $e')));
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _changes.isEmpty,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (await _confirmDiscard() && context.mounted) {
          _changes.clear();
          Navigator.of(context).pop();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Табель'),
          actions: [
            IconButton(
              tooltip: 'Скачать Excel',
              onPressed: _exporting || _sheet == null ? null : _exportExcel,
              icon: _exporting
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.table_view_outlined),
            ),
          ],
        ),
        floatingActionButton: _changes.isEmpty
            ? null
            : FloatingActionButton.extended(
                onPressed: _saving ? null : _save,
                icon: _saving
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.save_outlined),
                label: Text('Сохранить (${_changes.length})'),
              ),
        body: Column(
          children: [
            Row(
              children: [
                IconButton(onPressed: () => _shiftMonth(-1), icon: const Icon(Icons.chevron_left)),
                Expanded(
                  child: Text(
                    _sheet?['title'] as String? ?? '',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                IconButton(onPressed: () => _shiftMonth(1), icon: const Icon(Icons.chevron_right)),
              ],
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(12, 0, 12, 6),
              child: Text(
                '✓ пришёл · ✗ не пришёл · В выходной. Нажмите на ячейку, чтобы поменять. '
                'Бледная ✓ — из бригады заявки. При сохранении пустые дни до вчера станут ✗.',
                style: TextStyle(color: Colors.grey, fontSize: 12),
              ),
            ),
            const Divider(height: 1),
            Expanded(child: _body()),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) return ErrorView(error: _error.toString(), onRetry: _load);
    final sheet = _sheet!;
    final days = (sheet['days'] as List).cast<Map<String, dynamic>>();
    final rows = (sheet['rows'] as List).cast<Map<String, dynamic>>();
    final showMoney = sheet['totalPay'] != null;
    if (rows.isEmpty) {
      return const Center(child: Text('Сотрудников нет', style: TextStyle(color: Colors.grey)));
    }

    Widget header(String top, String bottom, {bool sunday = false, double width = _cellWidth}) => Container(
          width: width,
          height: 44,
          alignment: Alignment.center,
          color: sunday ? const Color(0xFFFBE3E0) : Colors.grey.shade100,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(top, style: TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5, color: sunday ? const Color(0xFFB23A2E) : null)),
              Text(bottom, style: TextStyle(fontSize: 10.5, color: sunday ? const Color(0xFFB23A2E) : Colors.grey)),
            ],
          ),
        );

    Widget cell(Map<String, dynamic> row, int i) {
      final s = _status(row, i);
      final auto = _isAuto(row, i);
      final sunday = days[i]['sunday'] as bool;
      final changed = _changes.containsKey('${row['employeeId']}|$i');
      final (text, color) = switch (s) {
        'P' => ('✓', auto ? const Color(0x992F7D4F) : const Color(0xFF2F7D4F)),
        'A' => ('✗', const Color(0xFFB23A2E)),
        'O' => ('В', Colors.grey),
        _ => ('', Colors.grey),
      };
      return InkWell(
        onTap: () => _tap(row, i),
        child: Container(
          width: _cellWidth,
          height: _rowHeight,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: changed ? const Color(0xFFFFF3C4) : sunday ? const Color(0xFFFDF1EF) : null,
            border: Border(
              right: BorderSide(color: Colors.grey.shade200),
              bottom: BorderSide(color: Colors.grey.shade200),
            ),
          ),
          child: Text(text, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: color)),
        ),
      );
    }

    int worked(Map<String, dynamic> row) =>
        [for (var i = 0; i < days.length; i++) _status(row, i)].where((s) => s == 'P').length;
    int absent(Map<String, dynamic> row) =>
        [for (var i = 0; i < days.length; i++) _status(row, i)].where((s) => s == 'A').length;

    Widget total(String text, {double width = 56, bool bold = false}) => Container(
          width: width,
          height: _rowHeight,
          alignment: Alignment.centerRight,
          padding: const EdgeInsets.symmetric(horizontal: 6),
          decoration: BoxDecoration(border: Border(bottom: BorderSide(color: Colors.grey.shade200))),
          child: Text(text, style: TextStyle(fontSize: 12.5, fontWeight: bold ? FontWeight.w600 : null)),
        );

    final names = Column(
      children: [
        Container(
          width: _nameWidth,
          height: 44,
          alignment: Alignment.centerLeft,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          color: Colors.grey.shade100,
          child: const Text('Сотрудник', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 12.5)),
        ),
        for (final row in rows)
          Container(
            width: _nameWidth,
            height: _rowHeight,
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            decoration: BoxDecoration(
              border: Border(
                bottom: BorderSide(color: Colors.grey.shade200),
                right: BorderSide(color: Colors.grey.shade300),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(row['name'] as String, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12.5)),
                Text(row['position'] as String? ?? row['group'] as String? ?? '',
                    maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 10.5, color: Colors.grey)),
              ],
            ),
          ),
      ],
    );

    final grid = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          for (final d in days) header('${d['day']}', d['weekday'] as String, sunday: d['sunday'] as bool),
          header('Отр.', 'дней', width: 56),
          header('Пр.', 'дней', width: 56),
          if (showMoney) header('Начислено', '₸', width: 104),
        ]),
        for (final row in rows)
          Row(children: [
            for (var i = 0; i < days.length; i++) cell(row, i),
            total('${worked(row)}', bold: true),
            total('${absent(row)}'),
            if (showMoney)
              total(_changes.isEmpty && row['pay'] != null ? formatMoney((row['pay'] as num).toDouble()) : '—', width: 104),
          ]),
      ],
    );

    return RefreshIndicator(
      onRefresh: _load,
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.only(bottom: 88),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                names,
                Expanded(child: SingleChildScrollView(scrollDirection: Axis.horizontal, child: grid)),
              ],
            ),
            if (showMoney)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  'Итого начислено: ${formatMoney((sheet['totalPay'] as num).toDouble())}'
                  '${_changes.isEmpty ? '' : ' (пересчитается после сохранения)'}',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
