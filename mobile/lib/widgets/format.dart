import 'package:intl/intl.dart';

final _numberFormat = NumberFormat.decimalPattern('ru');
final _dateFormat = DateFormat('dd.MM.yyyy', 'ru');
final _dateTimeFormat = DateFormat('dd.MM.yyyy HH:mm', 'ru');

String formatMoney(double value) => '${_numberFormat.format(value)} ₽';

String formatQty(double value) {
  if (value == value.roundToDouble()) return value.toInt().toString();
  return _numberFormat.format(value);
}

String formatDate(DateTime date) => _dateFormat.format(date);

String formatDateTime(DateTime date) => _dateTimeFormat.format(date);
