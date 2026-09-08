import 'dart:async';

import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:http/http.dart' as http;

/// [HttpFileService] из flutter_cache_manager не ставит тайм-аут на сетевой запрос — если
/// соединение "подвисает" (недоступный хост, обрыв сети до сервера), Future от
/// `_httpClient.send(req)` не завершается никогда. Один из 10 общих слотов очереди загрузки
/// картинок (см. FileService.concurrentFetches) навсегда занят таким запросом, из-за чего
/// СПИННЕР КРУТИТСЯ БЕСКОНЕЧНО не только у этой картинки, но и у следующих в очереди —
/// в отличие от обычной ошибки (404 и т.п.), которую CachedNetworkImage сразу превращает в
/// errorWidget. Оборачиваем запрос в .timeout(), чтобы зависший запрос падал с ошибкой.
class _TimeoutHttpFileService extends HttpFileService {
  _TimeoutHttpFileService() : super(httpClient: http.Client());

  @override
  Future<FileServiceResponse> get(String url, {Map<String, String>? headers}) {
    return super.get(url, headers: headers).timeout(const Duration(seconds: 12));
  }
}

/// Общий на всё приложение кеш-менеджер для CachedNetworkImage (товары, заказы и т.п.) — тот
/// же диск-кеш, что и дефолтный, только с тайм-аутом на загрузку (см. выше).
class TimeoutCacheManager extends CacheManager with ImageCacheManager {
  static const key = 'crmImageCache';
  static final TimeoutCacheManager _instance = TimeoutCacheManager._();

  factory TimeoutCacheManager() => _instance;

  TimeoutCacheManager._() : super(Config(key, fileService: _TimeoutHttpFileService()));
}
