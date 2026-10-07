import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;

import 'web/page_reload_stub.dart' if (dart.library.js_interop) 'web/page_reload_web.dart';

/// Веб-версия (iPhone, «На экран Домой»): iOS не перезапускает открытое веб-приложение, а
/// «размораживает» его — новая сборка не доходила неделями (с 30.09 по 07.10 iPhone ни разу не
/// скачал main.dart.js). Поэтому при запуске запоминаем Last-Modified/ETag сборки, а при каждом
/// возвращении в приложение спрашиваем сервер снова; сборка сменилась — перезагружаем страницу.
class WebUpdateChecker with WidgetsBindingObserver {
  WebUpdateChecker._();

  static final _instance = WebUpdateChecker._();
  String? _stamp;
  bool _checking = false;

  static void start() {
    if (!kIsWeb) return;
    WidgetsBinding.instance.addObserver(_instance);
    _instance._check();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check() async {
    if (_checking) return;
    _checking = true;
    try {
      final res = await http
          .head(Uri.base.resolve('main.dart.js'), headers: const {'Cache-Control': 'no-cache'})
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return;
      final stamp = res.headers['etag'] ?? res.headers['last-modified'];
      if (stamp == null) return;
      if (_stamp == null) {
        _stamp = stamp;
      } else if (_stamp != stamp) {
        reloadPage();
      }
    } catch (_) {
      // нет сети — проверим при следующем возвращении
    } finally {
      _checking = false;
    }
  }
}
