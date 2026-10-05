import 'package:flutter/material.dart';

import 'format.dart';
import '../theme/app_theme.dart';

bool _isSameDay(DateTime a, DateTime b) => a.year == b.year && a.month == b.month && a.day == b.day;

DateTimeRange todayRange() {
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  return DateTimeRange(start: today, end: today);
}

/// Панель фильтра по диапазону дат: «Сегодня · Неделя · Месяц · Период» (по умолчанию сегодня).
/// Неделя — последние 7 дней, месяц — с 1-го числа, период — произвольный диапазон из календаря.
/// Используется на дашборде, в заказах и уведомлениях.
class DateFilterBar extends StatelessWidget {
  final DateTimeRange range;
  final ValueChanged<DateTimeRange> onChanged;

  const DateFilterBar({super.key, required this.range, required this.onChanged});

  static DateTimeRange _week() {
    final t = todayRange().start;
    return DateTimeRange(start: t.subtract(const Duration(days: 6)), end: t);
  }

  static DateTimeRange _month() {
    final t = todayRange().start;
    return DateTimeRange(start: DateTime(t.year, t.month, 1), end: t);
  }

  bool _is(DateTimeRange r) => _isSameDay(range.start, r.start) && _isSameDay(range.end, r.end);

  int get _selected {
    if (_is(todayRange())) return 0;
    if (_is(_week())) return 1;
    if (_is(_month())) return 2;
    return 3;
  }

  String get _label {
    if (_isSameDay(range.start, range.end)) return formatDate(range.start);
    return '${formatDate(range.start)} — ${formatDate(range.end)}';
  }

  Future<void> _pick(BuildContext context) async {
    final now = DateTime.now();
    final picked = await showDateRangePicker(
      context: context,
      initialDateRange: range,
      firstDate: DateTime(2020),
      lastDate: now.add(const Duration(days: 365)),
    );
    if (picked != null) onChanged(picked);
  }

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    final selected = _selected;
    Widget segment(int i, String text, VoidCallback onTap) {
      final on = i == selected;
      return Expanded(
        child: Material(
          color: on ? p.card : Colors.transparent,
          elevation: on && !p.isDark ? 0.6 : 0,
          shadowColor: Colors.black26,
          borderRadius: BorderRadius.circular(9),
          child: InkWell(
            borderRadius: BorderRadius.circular(9),
            onTap: onTap,
            child: SizedBox(
              height: 36,
              child: Center(
                child: Text(
                  text,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: on ? FontWeight.w600 : FontWeight.w400,
                    color: on ? p.ink : p.muted,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            padding: const EdgeInsets.all(4),
            decoration: BoxDecoration(color: p.seg, borderRadius: BorderRadius.circular(12)),
            child: Row(
              children: [
                segment(0, 'Сегодня', () => onChanged(todayRange())),
                segment(1, 'Неделя', () => onChanged(_week())),
                segment(2, 'Месяц', () => onChanged(_month())),
                segment(3, 'Период', () => _pick(context)),
              ],
            ),
          ),
          if (selected != 0)
            Padding(
              padding: const EdgeInsets.only(top: 6, left: 4),
              child: Text(_label, style: TextStyle(fontSize: 12.5, color: p.muted)),
            ),
        ],
      ),
    );
  }
}
