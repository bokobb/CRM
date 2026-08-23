import 'package:flutter/material.dart';

import 'format.dart';

bool _isSameDay(DateTime a, DateTime b) => a.year == b.year && a.month == b.month && a.day == b.day;

DateTimeRange todayRange() {
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  return DateTimeRange(start: today, end: today);
}

/// Панель фильтра по диапазону дат: по умолчанию "Сегодня" (одна и та же дата с обеих сторон),
/// можно выбрать произвольный диапазон. Используется на дашборде, в заказах и уведомлениях.
class DateFilterBar extends StatelessWidget {
  final DateTimeRange range;
  final ValueChanged<DateTimeRange> onChanged;

  const DateFilterBar({super.key, required this.range, required this.onChanged});

  bool get _isToday => _isSameDay(range.start, range.end) && _isSameDay(range.start, DateTime.now());
  bool get _isSingleDay => _isSameDay(range.start, range.end);

  String get _label {
    if (_isToday) return 'Сегодня, ${formatDate(range.start)}';
    if (_isSingleDay) return formatDate(range.start);
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
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      child: Row(
        children: [
          Expanded(
            child: InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: () => _pick(context),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
                child: Row(
                  children: [
                    const Icon(Icons.date_range_outlined, size: 16, color: Colors.grey),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _label,
                        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (!_isToday)
            TextButton(
              onPressed: () => onChanged(todayRange()),
              child: const Text('Сегодня'),
            ),
        ],
      ),
    );
  }
}
