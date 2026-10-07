import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../navigation.dart';
import '../services/api_client.dart';

/// «Назад» (кнопка или жест от края экрана) на экране, под которым ничего нет. Разделы меню
/// открываются с заменой экрана (pushReplacementNamed), поэтому раньше такой «Назад» сразу закрывал
/// приложение. Теперь: из раздела — на главный экран роли, на главном — выход только по второму
/// «Назад» в течение 2 секунд. Открытое боковое меню закрывается как обычно (у маршрута тогда
/// canPop = true — см. ModalRoute.canPop).
class RootBackGuard extends StatefulWidget {
  final Widget child;

  const RootBackGuard({super.key, required this.child});

  @override
  State<RootBackGuard> createState() => _RootBackGuardState();
}

class _RootBackGuardState extends State<RootBackGuard> {
  static const _exitWindow = Duration(seconds: 2);
  DateTime? _lastBack;

  void _onBlockedBack(ModalRoute<dynamic> route) {
    final home = homeRouteForRole(ApiClient.currentSession?.role);
    if (route.settings.name != home) {
      Navigator.of(context).pushReplacementNamed(home);
      return;
    }
    final now = DateTime.now();
    if (_lastBack != null && now.difference(_lastBack!) < _exitWindow) {
      SystemNavigator.pop();
      return;
    }
    _lastBack = now;
    ScaffoldMessenger.maybeOf(context)
      ?..hideCurrentSnackBar()
      ..showSnackBar(const SnackBar(
        content: Text('Нажмите «Назад» ещё раз, чтобы выйти'),
        duration: _exitWindow,
      ));
  }

  @override
  Widget build(BuildContext context) {
    final route = ModalRoute.of(context);
    final guarded = route != null &&
        !route.canPop &&
        route.settings.name != '/login' &&
        ApiClient.currentSession != null;
    return PopScope(
      canPop: !guarded,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && route != null) _onBlockedBack(route);
      },
      child: widget.child,
    );
  }
}
