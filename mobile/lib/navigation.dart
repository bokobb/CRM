import 'package:flutter/material.dart';

/// Глобальный ключ навигатора — нужен, чтобы ApiClient мог сам перекинуть на экран логина
/// при истечении/невалидности токена (401), не размазывая эту логику по каждому экрану.
final navigatorKey = GlobalKey<NavigatorState>();

/// Первый экран после входа — для каждой роли. Дашборд ('/') сам выбирает вид по роли
/// (см. DashboardScreen): продажи — для руководства, производство — для склада сырья,
/// сборка/отгрузка — для склада ГП.
String homeRouteForRole(String? role) {
  switch (role) {
    case 'TECHNOLOGIST':
      // Технолог распределяет бригады — его рабочий экран «Производство».
      return '/production';
    default:
      return '/';
  }
}
