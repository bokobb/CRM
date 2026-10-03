import 'dart:math';

import 'package:http/http.dart' as http;

/// HTTP-клиент, который защищает от задвоения при плохом интернете или долгом ответе 1С:
/// каждый изменяющий запрос (POST/PUT/DELETE) несёт заголовок `Idempotency-Key`, и тот же
/// ключ повторяется, если точно такой же запрос (метод + адрес + тело) ещё в пути или прошлая
/// попытка закончилась без ответа (обрыв, тайм-аут, 5xx от nginx). Backend (IdempotencyFilter)
/// по такому ключу не выполняет запрос второй раз, а отдаёт результат первого. После
/// внятного ответа (2xx/4xx) ключ забывается — осознанный повтор той же операции пройдёт.
class IdempotentClient extends http.BaseClient {
  final http.Client _inner = http.Client();
  final Map<String, _Attempt> _attempts = {};
  final Random _random = Random.secure();

  static const _keepUnknown = Duration(minutes: 30);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final method = request.method.toUpperCase();
    if (request is! http.Request || !(method == 'POST' || method == 'PUT' || method == 'DELETE')) {
      return _inner.send(request);
    }
    final now = DateTime.now();
    _attempts.removeWhere((_, a) => a.inFlight == 0 && now.difference(a.lastUsed) > _keepUnknown);

    final fingerprint = '$method ${request.url} ${request.body}';
    final attempt = _attempts.putIfAbsent(fingerprint, () => _Attempt(_newKey()));
    attempt.inFlight++;
    attempt.lastUsed = now;
    request.headers['Idempotency-Key'] = attempt.key;
    try {
      final response = await _inner.send(request);
      // 5xx — исход неизвестен (nginx мог не дождаться backend), ключ оставляем для повтора.
      if (response.statusCode < 500) attempt.settled = true;
      return response;
    } finally {
      attempt.inFlight--;
      attempt.lastUsed = DateTime.now();
      if (attempt.settled && attempt.inFlight == 0 && identical(_attempts[fingerprint], attempt)) {
        _attempts.remove(fingerprint);
      }
    }
  }

  String _newKey() => List.generate(16, (_) => _random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();

  @override
  void close() => _inner.close();
}

class _Attempt {
  final String key;
  int inFlight = 0;
  bool settled = false;
  DateTime lastUsed = DateTime.now();

  _Attempt(this.key);
}
