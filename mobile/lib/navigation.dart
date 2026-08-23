import 'package:flutter/material.dart';

/// Глобальный ключ навигатора — нужен, чтобы ApiClient мог сам перекинуть на экран логина
/// при истечении/невалидности токена (401), не размазывая эту логику по каждому экрану.
final navigatorKey = GlobalKey<NavigatorState>();
