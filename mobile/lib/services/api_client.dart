import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/auth.dart';
import '../models/client.dart';
import '../models/client_price.dart';
import '../models/company_profile.dart';
import '../models/counterparty.dart';
import '../models/dashboard.dart';
import '../models/ingredient.dart';
import '../models/notification.dart';
import '../models/order.dart';
import '../models/product.dart';
import '../models/product_return.dart';
import '../models/production_request.dart';
import '../models/purchase.dart';
import '../models/user.dart';
import '../models/whatsapp_inbox.dart';
import '../models/whatsapp_session.dart';
import '../navigation.dart';
import 'push_service.dart';

/// По расширению файла — backend принимает только jpeg/png/webp/gif (см. FileStorageService).
/// jpg по умолчанию, т.к. это формат камеры на Android/iOS — если расширение незнакомое,
/// разумнее всего предположить именно фото с камеры, а не молча слать application/octet-stream.
String _guessImageContentType(String filename) {
  final ext = filename.toLowerCase().split('.').last;
  switch (ext) {
    case 'png':
      return 'image/png';
    case 'webp':
      return 'image/webp';
    case 'gif':
      return 'image/gif';
    case 'jpg':
    case 'jpeg':
    default:
      return 'image/jpeg';
  }
}

/// Ошибка бэкенда (Client 4xx) с человекочитаемым сообщением из ApiError.message.
class ApiException implements Exception {
  final String message;
  ApiException(this.message);
  @override
  String toString() => message;
}

/// Накладная, приложенная к документу заказа в 1С (см. ApiClient.getInvoiceFile).
class InvoiceFile {
  final Uint8List bytes;
  final String filename;
  final String mimeType;
  InvoiceFile({required this.bytes, required this.filename, required this.mimeType});
}

/// Результат "Общего оприходования" в 1С (см. ApiClient.postDailyReceiptsOneC).
class OneCReceiptResult {
  final int movementsCount;
  final String? documentNumber;
  final List<String> unresolved;
  OneCReceiptResult({required this.movementsCount, required this.documentNumber, required this.unresolved});

  factory OneCReceiptResult.fromJson(Map<String, dynamic> json) => OneCReceiptResult(
        movementsCount: json['movementsCount'] as int,
        documentNumber: json['documentNumber'] as String?,
        unresolved: (json['unresolved'] as List).cast<String>(),
      );
}

/// Тонкий HTTP-клиент к REST API кондитерской CRM (см. com.confectionery.crm.api.*
/// на бэкенде). Базовый URL настраивается в приложении и хранится в SharedPreferences —
/// по умолчанию подставляется адрес, подходящий для Android-эмулятора/веба.
class ApiClient {
  static const _baseUrlKey = 'api_base_url';
  static const _tokenKey = 'auth_token';
  static const _usernameKey = 'auth_username';
  static const _fullNameKey = 'auth_full_name';
  static const _roleKey = 'auth_role';
  static const _roleLabelKey = 'auth_role_label';
  static const _rememberedUsernameKey = 'remembered_username';

  static AuthSession? _session;

  /// Текущая сессия (уже загруженная в память) — читается синхронно, например в AppDrawer.
  static AuthSession? get currentSession => _session;

  /// Восстанавливает сессию из SharedPreferences. Вызывается один раз при старте приложения,
  /// до runApp(), чтобы CrmApp мог сразу решить, показывать логин или дашборд.
  static Future<void> loadSession() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(_tokenKey);
    if (token == null) {
      _session = null;
      return;
    }
    _session = AuthSession(
      token: token,
      username: prefs.getString(_usernameKey) ?? '',
      fullName: prefs.getString(_fullNameKey) ?? '',
      role: prefs.getString(_roleKey) ?? '',
      roleLabel: prefs.getString(_roleLabelKey) ?? '',
    );
  }

  static Future<void> _saveSession(AuthSession session) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_tokenKey, session.token);
    await prefs.setString(_usernameKey, session.username);
    await prefs.setString(_fullNameKey, session.fullName);
    await prefs.setString(_roleKey, session.role);
    await prefs.setString(_roleLabelKey, session.roleLabel);
    _session = session;
  }

  /// Адрес по умолчанию для СВЕЖЕЙ установки (пока никто не менял Base URL в Настройках) —
  /// реальный боевой backend на Yandex Cloud (см. память проекта yandex_cloud_deployment),
  /// доступен из любой сети по HTTPS. Временный адрес через nip.io — как только заведётся
  /// домен yug-sladosti.kz, сменить здесь на него (и на веб-сервере — Nginx server_name уже
  /// содержит оба варианта, так что смена этой константы не потребует правок на сервере).
  /// Один и тот же адрес для всех платформ (Android/iOS/web) — localhost:8080 был лишь
  /// заглушкой со времён, когда веб/iOS ещё не собирались всерьёз (обнаружено 2026-09-22 при
  /// первой сборке под веб — запросы падали в ERR_CONNECTION_REFUSED на localhost телефона).
  static String defaultBaseUrl() => 'https://94.131.90.178.nip.io/api';

  static Future<String> getBaseUrl() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_baseUrlKey) ?? defaultBaseUrl();
  }

  static Future<void> setBaseUrl(String url) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_baseUrlKey, url);
  }

  Future<Uri> _uri(String path, [Map<String, String>? query]) async {
    final base = await getBaseUrl();
    return Uri.parse('$base$path').replace(queryParameters: query);
  }

  /// Схема+хост backend'а (без /api) — нужна, чтобы превратить относительный путь
  /// загруженного фото товара (например, "/product-images/x.jpg") в абсолютный URL.
  Future<String> _origin() async {
    final uri = Uri.parse(await getBaseUrl());
    return '${uri.scheme}://${uri.authority}';
  }

  Future<Product> _withAbsoluteImage(Product p) async {
    final url = p.imageUrl;
    if (url == null || url.isEmpty || url.startsWith('http')) return p;
    return p.withImageUrl('${await _origin()}$url');
  }

  Future<List<Product>> _withAbsoluteImages(List<Product> list) async {
    final origin = await _origin();
    return list.map((p) {
      final url = p.imageUrl;
      if (url == null || url.isEmpty || url.startsWith('http')) return p;
      return p.withImageUrl('$origin$url');
    }).toList();
  }

  /// То же самое, но для фото товаров внутри позиций заказа (Order.items[].productImageUrl).
  Future<Order> _withAbsoluteOrderImages(Order order) async {
    if (order.items.every(
        (i) => i.productImageUrl == null || i.productImageUrl!.isEmpty || i.productImageUrl!.startsWith('http'))) {
      return order;
    }
    final origin = await _origin();
    final items = order.items.map((i) {
      final url = i.productImageUrl;
      if (url == null || url.isEmpty || url.startsWith('http')) return i;
      return i.withImageUrl('$origin$url');
    }).toList();
    return order.withItems(items);
  }

  Future<Map<String, String>> _authHeaders() async {
    final headers = {'Content-Type': 'application/json'};
    final token = _session?.token;
    if (token != null) headers['Authorization'] = 'Bearer $token';
    return headers;
  }

  dynamic _decode(http.Response res) {
    if (res.statusCode >= 200 && res.statusCode < 300) {
      if (res.body.isEmpty) return null;
      return jsonDecode(utf8.decode(res.bodyBytes));
    }
    String message = 'Ошибка сервера (${res.statusCode})';
    try {
      final body = jsonDecode(utf8.decode(res.bodyBytes));
      if (body is Map && body['message'] != null) {
        message = body['message'] as String;
      }
    } catch (_) {
      // тело не JSON — оставляем сообщение по умолчанию
    }
    if (res.statusCode == 401 && _session != null) {
      // Токен истёк/стал невалидным во время сессии — сбрасываем её и уводим на логин.
      _session = null;
      SharedPreferences.getInstance().then(
        (p) => Future.wait([
          p.remove(_tokenKey),
          p.remove(_usernameKey),
          p.remove(_fullNameKey),
          p.remove(_roleKey),
          p.remove(_roleLabelKey),
        ]),
      );
      navigatorKey.currentState?.pushNamedAndRemoveUntil(
        '/login',
        (route) => false,
      );
      message = 'Сессия истекла, войдите заново';
    }
    throw ApiException(message);
  }

  // ---- Auth ----
  Future<AuthSession> login(String username, String password) async {
    final res = await http.post(
      await _uri('/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'username': username, 'password': password}),
    );
    final session = AuthSession.fromJson(_decode(res) as Map<String, dynamic>);
    await _saveSession(session);
    await PushService.subscribeToRole(session.role);
    return session;
  }

  /// "Запомнить меня" на экране логина — переживает [logout] (в отличие от [_usernameKey]
  /// выше, который часть текущей сессии и чистится при выходе). Пароль намеренно НЕ хранится —
  /// только логин, чтобы не держать пароль в открытом виде на устройстве.
  static Future<void> saveRememberedUsername(String? username) async {
    final prefs = await SharedPreferences.getInstance();
    if (username == null || username.isEmpty) {
      await prefs.remove(_rememberedUsernameKey);
    } else {
      await prefs.setString(_rememberedUsernameKey, username);
    }
  }

  static Future<String?> getRememberedUsername() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_rememberedUsernameKey);
  }

  Future<void> logout() async {
    final prefs = await SharedPreferences.getInstance();
    if (_session != null) await PushService.unsubscribeFromRole(_session!.role);
    await prefs.remove(_tokenKey);
    await prefs.remove(_usernameKey);
    await prefs.remove(_fullNameKey);
    await prefs.remove(_roleKey);
    await prefs.remove(_roleLabelKey);
    _session = null;
  }

  // ---- Dashboard ----
  Future<Dashboard> getDashboard({DateTime? from, DateTime? to}) async {
    final res = await http.get(
      await _uri('/dashboard', _rangeQuery(from, to)),
      headers: await _authHeaders(),
    );
    return Dashboard.fromJson(_decode(res) as Map<String, dynamic>);
  }

  /// Дашборд для роли WAREHOUSE_RAW — про заявки на сырьё, не про заказы клиентов.
  Future<ProductionDashboard> getProductionDashboard({DateTime? from, DateTime? to}) async {
    final res = await http.get(
      await _uri('/dashboard/production', _rangeQuery(from, to)),
      headers: await _authHeaders(),
    );
    return ProductionDashboard.fromJson(_decode(res) as Map<String, dynamic>);
  }

  static String _isoDate(DateTime d) => d.toIso8601String().split('T').first;

  static Map<String, String>? _rangeQuery(DateTime? from, DateTime? to) {
    if (from == null || to == null) return null;
    return {'from': _isoDate(from), 'to': _isoDate(to)};
  }

  // ---- Clients ----
  Future<List<Client>> getClients() async {
    final res = await http.get(
      await _uri('/clients'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => Client.fromJson(e)).toList();
  }

  Future<Client> getClient(int id) async {
    final res = await http.get(
      await _uri('/clients/$id'),
      headers: await _authHeaders(),
    );
    return Client.fromJson(_decode(res));
  }

  Future<Client> createClient(Map<String, dynamic> body) async {
    final res = await http.post(
      await _uri('/clients'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return Client.fromJson(_decode(res));
  }

  Future<Client> updateClient(int id, Map<String, dynamic> body) async {
    final res = await http.put(
      await _uri('/clients/$id'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return Client.fromJson(_decode(res));
  }

  Future<void> deleteClient(int id) async {
    final res = await http.delete(
      await _uri('/clients/$id'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  // ---- Индивидуальные цены клиента ----
  Future<List<ClientPrice>> getClientPrices(int clientId) async {
    final res = await http.get(
      await _uri('/clients/$clientId/prices'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => ClientPrice.fromJson(e)).toList();
  }

  Future<ClientPrice> setClientPrice(
    int clientId,
    int productId,
    double price,
  ) async {
    final res = await http.put(
      await _uri('/clients/$clientId/prices/$productId'),
      headers: await _authHeaders(),
      body: jsonEncode({'price': price}),
    );
    return ClientPrice.fromJson(_decode(res));
  }

  Future<void> deleteClientPrice(int clientId, int productId) async {
    final res = await http.delete(
      await _uri('/clients/$clientId/prices/$productId'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  // ---- Products ----
  /// [includeSemiFinished]: false (по умолчанию) — для витрины заказа, полуфабрикаты вроде
  /// "Пряника" не заказывают напрямую; true — для экранов управления товарами/рецептами, там
  /// полуфабрикат нужно видеть и редактировать (см. ProductApiController на бэкенде).
  Future<List<Product>> getProducts({bool includeSemiFinished = false}) async {
    final res = await http.get(
      await _uri('/products', includeSemiFinished ? {'includeSemiFinished': 'true'} : null),
      headers: await _authHeaders(),
    );
    final list = (_decode(res) as List).map((e) => Product.fromJson(e)).toList();
    return _withAbsoluteImages(list);
  }

  Future<Product> getProduct(int id) async {
    final res = await http.get(
      await _uri('/products/$id'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteImage(Product.fromJson(_decode(res)));
  }

  Future<Product> createProduct(Map<String, dynamic> body) async {
    final res = await http.post(
      await _uri('/products'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return _withAbsoluteImage(Product.fromJson(_decode(res)));
  }

  Future<Product> updateProduct(int id, Map<String, dynamic> body) async {
    final res = await http.put(
      await _uri('/products/$id'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return _withAbsoluteImage(Product.fromJson(_decode(res)));
  }

  /// Загружает фото товара с телефона/компьютера (см. FileStorageService на бэкенде)
  /// и возвращает товар с уже абсолютным imageUrl. Без явного content-type пакет http
  /// шлёт "application/octet-stream" — backend такое отклоняет как неизвестный формат
  /// (проверяет именно content-type, не расширение файла), поэтому определяем его сами.
  Future<Product> uploadProductImage(int id,
      {required List<int> bytes, required String filename, String? contentType}) async {
    final request = http.MultipartRequest('POST', await _uri('/products/$id/image'));
    final token = _session?.token;
    if (token != null) request.headers['Authorization'] = 'Bearer $token';
    request.files.add(http.MultipartFile.fromBytes(
      'file',
      bytes,
      filename: filename,
      contentType: MediaType.parse(contentType ?? _guessImageContentType(filename)),
    ));
    final streamed = await request.send();
    final res = await http.Response.fromStream(streamed);
    return _withAbsoluteImage(Product.fromJson(_decode(res)));
  }

  Future<void> deleteProduct(int id) async {
    final res = await http.delete(
      await _uri('/products/$id'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  Future<Recipe> getRecipe(int productId) async {
    final res = await http.get(
      await _uri('/products/$productId/recipe'),
      headers: await _authHeaders(),
    );
    return Recipe.fromJson(_decode(res));
  }

  Future<Recipe> saveRecipe(int productId, Map<String, dynamic> body) async {
    final res = await http.put(
      await _uri('/products/$productId/recipe'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return Recipe.fromJson(_decode(res));
  }

  // ---- Ingredients ----
  Future<List<Ingredient>> getIngredients() async {
    final res = await http.get(
      await _uri('/ingredients'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => Ingredient.fromJson(e)).toList();
  }

  Future<Ingredient> getIngredient(int id) async {
    final res = await http.get(
      await _uri('/ingredients/$id'),
      headers: await _authHeaders(),
    );
    return Ingredient.fromJson(_decode(res));
  }

  Future<Ingredient> createIngredient(Map<String, dynamic> body) async {
    final res = await http.post(
      await _uri('/ingredients'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return Ingredient.fromJson(_decode(res));
  }

  Future<Ingredient> updateIngredient(int id, Map<String, dynamic> body) async {
    final res = await http.put(
      await _uri('/ingredients/$id'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return Ingredient.fromJson(_decode(res));
  }

  Future<void> deleteIngredient(int id) async {
    final res = await http.delete(
      await _uri('/ingredients/$id'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  // ---- Stock movements ----
  /// Только ручные движения возвращает сервер — то, что списывается/приходуется автоматически
  /// заявками на производство, сюда не попадает (см. StockMovement.manual на бэкенде).
  Future<List<StockMovement>> getStockMovements({String? type, DateTime? from, DateTime? to, String? q}) async {
    final query = {
      if (type != null) 'type': type,
      if (from != null) 'from': _isoDate(from),
      if (to != null) 'to': _isoDate(to),
      if (q != null && q.isNotEmpty) 'q': q,
    };
    final res = await http.get(
      await _uri('/stock/movements', query.isEmpty ? null : query),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List)
        .map((e) => StockMovement.fromJson(e))
        .toList();
  }

  Future<StockMovement> receiveStock(
    int ingredientId,
    double quantity,
    String? reason,
  ) async {
    final res = await http.post(
      await _uri('/stock/receipts'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'ingredientId': ingredientId,
        'quantity': quantity,
        'reason': reason,
      }),
    );
    return StockMovement.fromJson(_decode(res));
  }

  Future<StockMovement> writeOffStock(
    int ingredientId,
    double quantity,
    String? reason,
  ) async {
    final res = await http.post(
      await _uri('/stock/writeoffs'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'ingredientId': ingredientId,
        'quantity': quantity,
        'reason': reason,
      }),
    );
    return StockMovement.fromJson(_decode(res));
  }

  Future<StockMovement> receiveProductStock(
    int productId,
    double quantity,
    String? reason,
  ) async {
    final res = await http.post(
      await _uri('/stock/receipts'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'productId': productId,
        'quantity': quantity,
        'reason': reason,
      }),
    );
    return StockMovement.fromJson(_decode(res));
  }

  Future<StockMovement> writeOffProductStock(
    int productId,
    double quantity,
    String? reason,
  ) async {
    final res = await http.post(
      await _uri('/stock/writeoffs'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'productId': productId,
        'quantity': quantity,
        'reason': reason,
      }),
    );
    return StockMovement.fromJson(_decode(res));
  }

  /// Удаляет ошибочно созданное ручное поступление/списание — остаток на складе
  /// пересчитывается обратно (см. StockService.deleteMovement на бэкенде).
  Future<void> deleteStockMovement(int id) async {
    final res = await http.delete(
      await _uri('/stock/movements/$id'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  /// Ручной запуск синхронизации остатков с 1С (обычно идёт по расписанию каждые 5 минут на
  /// бэкенде) — только для ADMIN, см. OneCStockSyncService/StockMovementApiController.
  Future<({int productsUpdated, int ingredientsUpdated, List<String> unmatched})> syncOneC() async {
    final res = await http.post(
      await _uri('/stock/sync-1c'),
      headers: await _authHeaders(),
    );
    final json = _decode(res) as Map<String, dynamic>;
    return (
      productsUpdated: json['productsUpdated'] as int,
      ingredientsUpdated: json['ingredientsUpdated'] as int,
      unmatched: (json['unmatched'] as List).cast<String>(),
    );
  }

  /// Определяет подразделение (корпус) каждого товара по истории реальных документов в 1С —
  /// НЕ трогает рецепты. Только ADMIN, см. OneCDepartmentImportService/ProductApiController.
  Future<({int updated, int skippedNoHistory, int skippedNoOneCMatch})> importDepartmentsFromOneC() async {
    final res = await http.post(await _uri('/products/import-departments-1c'), headers: await _authHeaders());
    final json = _decode(res) as Map<String, dynamic>;
    return (
      updated: json['updated'] as int,
      skippedNoHistory: json['skippedNoHistory'] as int,
      skippedNoOneCMatch: json['skippedNoOneCMatch'] as int,
    );
  }

  /// Заполняет рецепт (состав сырья) и GUID спецификации ОДНОГО товара из 1С — не трогает
  /// остальные товары, в отличие от полного импорта. Только ADMIN, см.
  /// OneCRecipeImportService.importOne/ProductApiController. Бросает ApiException с понятной
  /// причиной, если товар не сопоставлен с 1С по названию или у него там нет спецификации.
  Future<Recipe> importRecipeForProduct(int productId) async {
    final res = await http.post(await _uri('/products/$productId/import-recipe-1c'), headers: await _authHeaders());
    return Recipe.fromJson(_decode(res));
  }

  // ---- Production requests (заявки на сырьё) ----
  Future<List<ProductionRequestSummary>> getProductionRequests() async {
    final res = await http.get(
      await _uri('/production-requests'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => ProductionRequestSummary.fromJson(e)).toList();
  }

  Future<ProductionRequest> getProductionRequest(int id) async {
    final res = await http.get(
      await _uri('/production-requests/$id'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Товары, у которых уже есть рецептура — только из них можно создать заявку.
  Future<List<ProductionEligibleProduct>> getEligibleProductsForRequest() async {
    final res = await http.get(
      await _uri('/production-requests/eligible-products'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => ProductionEligibleProduct.fromJson(e)).toList();
  }

  /// Позиции сразу под несколько товаров — например, из одного теста сегодня решили сделать
  /// несколько разных вариантов (у каждого свои замесы). Фактический выход всегда гуляет,
  /// поэтому планируем в замесах, а не в штуках — факт вводится отдельно при оприходовании.
  /// Название заявки не передаётся — сервер сам разбивает позиции по группе товара
  /// (Product.group) и создаёт отдельную заявку на каждую группу, поэтому в ответ приходит список.
  /// [documentDate] — дата документа для 1С (см. ApiClient._isoDate); не задана — сегодня.
  Future<List<ProductionRequest>> createProductionRequest(List<(int, double)> items, {DateTime? documentDate}) async {
    final res = await http.post(
      await _uri('/production-requests'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'items': items.map((e) => {'productId': e.$1, 'batches': e.$2}).toList(),
        if (documentDate != null) 'documentDate': _isoDate(documentDate),
      }),
    );
    return (_decode(res) as List).map((e) => ProductionRequest.fromJson(e)).toList();
  }

  /// Редактирование ещё не собранной заявки — позиции и (опционально) дата документа, название
  /// заявки не меняется.
  Future<ProductionRequest> updateProductionRequest(int id, List<(int, double)> items, {DateTime? documentDate}) async {
    final res = await http.put(
      await _uri('/production-requests/$id'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'items': items.map((e) => {'productId': e.$1, 'batches': e.$2}).toList(),
        if (documentDate != null) 'documentDate': _isoDate(documentDate),
      }),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Отмена заявки в ЛЮБОМ статусе, кроме уже отменённой — откатывает складские остатки, если
  /// сырьё уже было списано/продукция оприходована, и помечает связанные документы в 1С на
  /// удаление (см. ProductionRequestService.cancel на бэкенде). Раньше откат остатков в любом
  /// статусе был отдельным DELETE-запросом — теперь объединено с отменой. Только ADMIN.
  Future<ProductionRequest> cancelProductionRequest(int id) async {
    final res = await http.post(
      await _uri('/production-requests/$id/cancel'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// itemIds должны покрывать ВСЕ позиции заявки — иначе сервер отклонит сборку.
  /// Замена сырья в позиции заявки — например, нужного не хватило на складе. ingredientId не
  /// задан = сырьё не меняется, правится только quantity (в этом случае quantity обязателен).
  /// quantity не задан при заданном ingredientId = оставить прежнее количество (см.
  /// ProductionRequestService.substituteItem на бэкенде).
  Future<ProductionRequest> substituteProductionRequestItem(
    int requestId,
    int itemId, {
    int? ingredientId,
    double? quantity,
  }) async {
    final res = await http.post(
      await _uri('/production-requests/$requestId/items/$itemId/substitute'),
      headers: await _authHeaders(),
      body: jsonEncode({
        if (ingredientId != null) 'ingredientId': ingredientId,
        if (quantity != null) 'quantity': quantity,
      }),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Добавление совсем новой позиции сырья, которой не было в заявке — например, во время
  /// производства понадобилось что-то, чего не было в исходном рецепте (см.
  /// ProductionRequestService.addItem на бэкенде). Если сырьё уже есть в заявке — сервер просто
  /// увеличит его количество, не создавая вторую строку.
  Future<ProductionRequest> addProductionRequestItem(int requestId, int ingredientId, double quantity) async {
    final res = await http.post(
      await _uri('/production-requests/$requestId/items/add'),
      headers: await _authHeaders(),
      body: jsonEncode({'ingredientId': ingredientId, 'quantity': quantity}),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Удаление позиции сырья из заявки (см. ProductionRequestService.removeItem на бэкенде).
  Future<ProductionRequest> deleteProductionRequestItem(int requestId, int itemId) async {
    final res = await http.delete(
      await _uri('/production-requests/$requestId/items/$itemId'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Повторная ручная синхронизация заявки с 1С — на случай, если автоматическая (при
  /// создании/редактировании/замене сырья/оприходовании) не сработала (см.
  /// ProductionRequestService.resyncOneC на бэкенде).
  Future<ProductionRequest> syncProductionRequestOneC(int id) async {
    final res = await http.post(
      await _uri('/production-requests/$id/sync-1c'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// "Завершить" — вручную проводит требование-накладную (списание сырья) в 1С, когда состав
  /// заявки точно больше не изменится (см. ProductionRequestService.completeRequisition на
  /// бэкенде). Отчёт производства эта кнопка не трогает — он проводится сам при полном
  /// оприходовании.
  Future<ProductionRequest> completeProductionRequestOneC(int id) async {
    final res = await http.post(
      await _uri('/production-requests/$id/complete-1c'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// "Отменить" (после "Завершить") — распроводит требование-накладную в 1С и снова разрешает
  /// править состав сырья (см. ProductionRequestService.cancelCompletion на бэкенде).
  Future<ProductionRequest> cancelProductionRequestCompletion(int id) async {
    final res = await http.post(
      await _uri('/production-requests/$id/cancel-completion'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  Future<ProductionRequest> collectProductionRequest(int id, List<int> itemIds) async {
    final res = await http.post(
      await _uri('/production-requests/$id/collect'),
      headers: await _authHeaders(),
      body: jsonEncode({'itemIds': itemIds}),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Оприходовать ОДНУ строку заявки — у каждого товара в заявке свой фактический выход.
  Future<ProductionRequest> receiveProductionRequestLine(int requestId, int lineId, double quantity) async {
    final res = await http.post(
      await _uri('/production-requests/$requestId/lines/$lineId/receive'),
      headers: await _authHeaders(),
      body: jsonEncode({'quantity': quantity}),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Дневной отчёт "сколько сегодня изготовили" — date не задан = сегодня.
  Future<DailyProductionReport> getDailyProductionReport({DateTime? date}) async {
    final res = await http.get(
      await _uri('/production-requests/report', date != null ? {'date': _isoDate(date)} : null),
      headers: await _authHeaders(),
    );
    return DailyProductionReport.fromJson(_decode(res) as Map<String, dynamic>);
  }

  /// Сырые байты готового PDF отчёта с сервера (не JSON — _decode тут не подходит).
  Future<List<int>> getDailyProductionReportPdf({DateTime? date}) async {
    final res = await http.get(
      await _uri('/production-requests/report/pdf', date != null ? {'date': _isoDate(date)} : null),
      headers: await _authHeaders(),
    );
    if (res.statusCode >= 200 && res.statusCode < 300) return res.bodyBytes;
    throw ApiException('Не удалось получить PDF-отчёт (${res.statusCode})');
  }

  /// "Общее оприходование" — собирает все ещё не выгруженные в 1С РУЧНЫЕ поступления товара
  /// за день (форма "Поступления", без учёта заявок на производство — те выгружаются сами)
  /// в один документ и сразу проводит его на 06:00.
  Future<OneCReceiptResult> postDailyReceiptsOneC({DateTime? date}) async {
    final res = await http.post(
      await _uri('/stock/receipts/post-1c', date != null ? {'date': _isoDate(date)} : null),
      headers: await _authHeaders(),
    );
    return OneCReceiptResult.fromJson(_decode(res) as Map<String, dynamic>);
  }

  // ---- Purchases (закупки сырья) ----
  Future<Purchase> _withAbsolutePurchaseImage(Purchase p) async {
    final url = p.imageUrl;
    if (url == null || url.isEmpty || url.startsWith('http')) return p;
    return p.withImageUrl('${await _origin()}$url');
  }

  Future<List<Purchase>> _withAbsolutePurchaseImages(List<Purchase> list) async {
    final origin = await _origin();
    return list.map((p) {
      final url = p.imageUrl;
      if (url == null || url.isEmpty || url.startsWith('http')) return p;
      return p.withImageUrl('$origin$url');
    }).toList();
  }

  /// Справочник контрагентов — для выбора поставщика в форме закупки (см. purchases_screen.dart)
  /// и для экрана "Настройки → Контрагенты" (см. counterparties_screen.dart). Удаление и разовая
  /// загрузка из 1С по-прежнему только на вебе (/counterparties).
  Future<List<Counterparty>> getCounterparties() async {
    final res = await http.get(
      await _uri('/counterparties'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => Counterparty.fromJson(e)).toList();
  }

  /// Создаёт контрагента — бэкенд сразу же пытается создать соответствующий элемент в 1С (см.
  /// CounterpartyService.create), поэтому результат может прийти с oneCLinked=false/oneCSyncError
  /// заполненным, если 1С недоступна — сам контрагент при этом уже создан в CRM.
  Future<Counterparty> createCounterparty(String name) async {
    final res = await http.post(
      await _uri('/counterparties'),
      headers: await _authHeaders(),
      body: jsonEncode({'name': name}),
    );
    return Counterparty.fromJson(_decode(res));
  }

  Future<List<Purchase>> getPurchases() async {
    final res = await http.get(
      await _uri('/purchases'),
      headers: await _authHeaders(),
    );
    final list = (_decode(res) as List).map((e) => Purchase.fromJson(e)).toList();
    return _withAbsolutePurchaseImages(list);
  }

  /// [items]: (ingredientId, quantity, totalPrice) — контрагент может привезти сразу несколько
  /// товаров за раз, поэтому одна закупка = один контрагент + список товаров.
  Future<Purchase> createPurchase({
    required int counterpartyId,
    required List<(int, double, double?)> items,
    DateTime? purchaseDate,
  }) async {
    final res = await http.post(
      await _uri('/purchases'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'counterpartyId': counterpartyId,
        'items': items
            .map((e) => {'ingredientId': e.$1, 'quantity': e.$2, 'totalPrice': e.$3})
            .toList(),
        'purchaseDate': purchaseDate?.toIso8601String().split('T').first,
      }),
    );
    return Purchase.fromJson(_decode(res));
  }

  /// Повторная ручная синхронизация закупки с 1С — на случай, если автоматическая (сразу
  /// при создании) не сработала (см. PurchaseService.resyncOneC на бэкенде).
  Future<Purchase> syncPurchaseOneC(int id) async {
    final res = await http.post(
      await _uri('/purchases/$id/sync-1c'),
      headers: await _authHeaders(),
    );
    return _withAbsolutePurchaseImage(Purchase.fromJson(_decode(res)));
  }

  /// Фото накладной поставщика — прикладывается отдельным запросом сразу после создания
  /// закупки (тот же паттерн, что uploadProductImage). Content-type определяем сами — иначе
  /// http-пакет шлёт "application/octet-stream", backend такое отклоняет.
  Future<Purchase> uploadPurchaseImage(int id,
      {required List<int> bytes, required String filename, String? contentType}) async {
    final request = http.MultipartRequest('POST', await _uri('/purchases/$id/image'));
    final token = _session?.token;
    if (token != null) request.headers['Authorization'] = 'Bearer $token';
    request.files.add(http.MultipartFile.fromBytes(
      'file',
      bytes,
      filename: filename,
      contentType: MediaType.parse(contentType ?? _guessImageContentType(filename)),
    ));
    final streamed = await request.send();
    final res = await http.Response.fromStream(streamed);
    return _withAbsolutePurchaseImage(Purchase.fromJson(_decode(res)));
  }

  // ---- Возвраты испорченного товара ----
  Future<List<ProductReturn>> _getReturns(Map<String, String>? query) async {
    final res = await http.get(await _uri('/returns', query), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => ProductReturn.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<ProductReturn>> getReturns({DateTime? from, DateTime? to}) => _getReturns(_rangeQuery(from, to));

  /// Возвраты, закреплённые за заказом — для расчёта "Заказ − Возврат = Итого".
  Future<List<ProductReturn>> getOrderReturns(int orderId) => _getReturns({'orderId': '$orderId'});

  /// Ещё не закреплённые ни за одним заказом возвраты клиента — выбор в карточке заказа.
  Future<List<ProductReturn>> getUnattachedReturns(int clientId) =>
      _getReturns({'clientId': '$clientId', 'unattached': 'true'});

  static String _returnBody(
    int clientId,
    List<(int, double, double?)> items,
    DateTime? returnDate,
    String? reason, [
    int? orderId,
  ]) => jsonEncode({
    'clientId': clientId,
    'items': items.map((e) => {'productId': e.$1, 'quantity': e.$2, 'price': e.$3}).toList(),
    'returnDate': returnDate == null ? null : _isoDate(returnDate),
    'reason': reason,
    'orderId': orderId,
  });

  /// [items]: (productId, quantity, price) — price null означает "цена клиента" (решает сервер).
  /// [orderId] — создание с карточки заказа: возврат сразу закрепляется за ним.
  Future<ProductReturn> createReturn({
    required int clientId,
    required List<(int, double, double?)> items,
    DateTime? returnDate,
    String? reason,
    int? orderId,
  }) async {
    final res = await http.post(
      await _uri('/returns'),
      headers: await _authHeaders(),
      body: _returnBody(clientId, items, returnDate, reason, orderId),
    );
    return ProductReturn.fromJson(_decode(res));
  }

  /// Правка возврата — сервер распроводит документ в 1С, перезаписывает и проводит заново.
  Future<ProductReturn> updateReturn(
    int id, {
    required int clientId,
    required List<(int, double, double?)> items,
    DateTime? returnDate,
    String? reason,
  }) async {
    final res = await http.put(
      await _uri('/returns/$id'),
      headers: await _authHeaders(),
      body: _returnBody(clientId, items, returnDate, reason),
    );
    return ProductReturn.fromJson(_decode(res));
  }

  /// Удаление — сервер сначала распроводит документ в 1С и помечает его на удаление.
  Future<void> deleteReturn(int id) async {
    final res = await http.delete(await _uri('/returns/$id'), headers: await _authHeaders());
    _decode(res);
  }

  Future<ProductReturn> attachReturn(int returnId, int orderId) async {
    final res = await http.put(await _uri('/returns/$returnId/order/$orderId'), headers: await _authHeaders());
    return ProductReturn.fromJson(_decode(res));
  }

  Future<ProductReturn> detachReturn(int returnId) async {
    final res = await http.delete(await _uri('/returns/$returnId/order'), headers: await _authHeaders());
    return ProductReturn.fromJson(_decode(res));
  }

  /// Повторная выгрузка возврата в 1С — если автоматическая при создании не прошла.
  Future<ProductReturn> syncReturnOneC(int id) async {
    final res = await http.post(await _uri('/returns/$id/sync-1c'), headers: await _authHeaders());
    return ProductReturn.fromJson(_decode(res));
  }

  // ---- Orders ----
  /// from/to не заданы — все даты; экран заказов по умолчанию передаёт сегодняшний день.
  Future<List<OrderSummary>> getOrders({
    String? status,
    int? clientId,
    DateTime? from,
    DateTime? to,
  }) async {
    final query = {
      if (status != null) 'status': status,
      if (clientId != null) 'clientId': '$clientId',
      if (from != null && to != null) 'from': _isoDate(from),
      if (from != null && to != null) 'to': _isoDate(to),
    };
    final res = await http.get(
      await _uri('/orders', query.isEmpty ? null : query),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => OrderSummary.fromJson(e)).toList();
  }

  Future<Order> getOrder(int id) async {
    final res = await http.get(
      await _uri('/orders/$id'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  /// Накладная, приложенная к документу заказа прямо в 1С — null, если её там ещё нет
  /// (заказ не выгружен, 1С недоступна или файл не приложили); тогда экран заказа сам
  /// строит свою локальную PDF-накладную вместо этого.
  Future<InvoiceFile?> getInvoiceFile(int orderId) async {
    final res = await http.get(
      await _uri('/orders/$orderId/invoice'),
      headers: await _authHeaders(),
    );
    if (res.statusCode == 404) return null;
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw ApiException('Не удалось получить накладную (${res.statusCode})');
    }
    final disposition = res.headers['content-disposition'] ?? '';
    final match = RegExp(r'filename="([^"]+)"').firstMatch(disposition);
    return InvoiceFile(
      bytes: res.bodyBytes,
      filename: match?.group(1) ?? 'nakladnaya_$orderId.pdf',
      mimeType: res.headers['content-type'] ?? 'application/octet-stream',
    );
  }

  Future<Order> createOrder(Map<String, dynamic> body) async {
    final res = await http.post(
      await _uri('/orders'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<Order> confirmOrder(int id) async {
    final res = await http.post(
      await _uri('/orders/$id/confirm'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  /// Вернуть заказ READY -> NEW ("В процессе сборки"), чтобы редактировать состав тем же
  /// чек-листом, что и при первичной сборке (см. OrderService.reopenForEditing на бэкенде).
  Future<Order> reopenOrder(int id) async {
    final res = await http.post(
      await _uri('/orders/$id/reopen'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<Order> cancelOrder(int id) async {
    final res = await http.post(
      await _uri('/orders/$id/cancel'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<Order> shipOrder(int id, String vehicleNumber) async {
    final res = await http.post(
      await _uri('/orders/$id/ship'),
      headers: await _authHeaders(),
      body: jsonEncode({'vehicleNumber': vehicleNumber}),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<Order> completeOrder(int id) async {
    final res = await http.post(
      await _uri('/orders/$id/complete'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<List<OrderSummary>> getDebts() async {
    final res = await http.get(
      await _uri('/orders/debts'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => OrderSummary.fromJson(e)).toList();
  }

  Future<Order> setOrderDebt(int id, DateTime dueDate) async {
    final res = await http.post(
      await _uri('/orders/$id/debt'),
      headers: await _authHeaders(),
      body: jsonEncode({'dueDate': _isoDate(dueDate)}),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<Order> markOrderDebtPaid(int id) async {
    final res = await http.post(
      await _uri('/orders/$id/debt/paid'),
      headers: await _authHeaders(),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  /// quantities: itemId -> новое количество (0 или меньше — убрать позицию из заказа).
  /// [productIdByItemId] — новый товар для позиции (только для тех itemId, где ИИ
  /// распознал товар неверно), остальные позиции меняют только количество.
  /// [newItems] — новые позиции для добавления в заказ (productId, quantity), без itemId.
  Future<Order> updateOrderItems(
    int id,
    Map<int, double> quantities, {
    Map<int, int>? productIdByItemId,
    List<(int, double)>? newItems,
  }) async {
    final res = await http.put(
      await _uri('/orders/$id/items'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'items': [
          ...quantities.entries.map((e) => {
                'itemId': e.key,
                'quantity': e.value,
                if (productIdByItemId?[e.key] != null) 'productId': productIdByItemId![e.key],
              }),
          ...?newItems?.map((n) => {'productId': n.$1, 'quantity': n.$2}),
        ],
      }),
    );
    return _withAbsoluteOrderImages(Order.fromJson(_decode(res)));
  }

  Future<num> getClientMonthlySales(int clientId) async {
    final res = await http.get(
      await _uri('/clients/$clientId/monthly-sales'),
      headers: await _authHeaders(),
    );
    return _decode(res) as num;
  }

  // ---- WhatsApp: заказы, которые ИИ не смог распознать полностью ----
  Future<List<WhatsAppInboundMessage>> getWhatsAppInbox() async {
    final res = await http.get(
      await _uri('/whatsapp-inbox'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => WhatsAppInboundMessage.fromJson(e)).toList();
  }

  /// [resolutions]: id позиции -> id выбранного товара, только для нераспознанных позиций.
  Future<void> resolveWhatsAppInbox(int messageId, Map<int, int> resolutions) async {
    final body = resolutions.map((itemId, productId) => MapEntry(itemId.toString(), productId));
    final res = await http.post(
      await _uri('/whatsapp-inbox/$messageId/resolve'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    _decode(res);
  }

  Future<void> ignoreWhatsAppInbox(int messageId) async {
    final res = await http.post(
      await _uri('/whatsapp-inbox/$messageId/ignore'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  // ---- WhatsApp-сессия (Настройки -> WhatsApp-сессия) ----

  Future<WhatsAppSession> getWhatsAppSessionStatus() async {
    final res = await http.get(
      await _uri('/whatsapp/session/status'),
      headers: await _authHeaders(),
    );
    return WhatsAppSession.fromJson(_decode(res));
  }

  /// QR-код как PNG-байты — доступен только пока статус сессии 'qr'. Не через _decode (не JSON).
  Future<Uint8List> getWhatsAppQrCode() async {
    final res = await http.get(
      await _uri('/whatsapp/session/qr'),
      headers: await _authHeaders(),
    );
    if (res.statusCode != 200) {
      throw Exception('QR-код сейчас недоступен');
    }
    return res.bodyBytes;
  }

  Future<void> logoutWhatsAppSession() async {
    final res = await http.post(
      await _uri('/whatsapp/session/logout'),
      headers: await _authHeaders(),
    );
    _decode(res);
  }

  // ---- Production tasks ----
  Future<List<ProductionTask>> getProductionTasks({String? status}) async {
    final res = await http.get(
      await _uri(
        '/production-tasks',
        status != null ? {'status': status} : null,
      ),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List)
        .map((e) => ProductionTask.fromJson(e))
        .toList();
  }

  Future<ProductionTask> startTask(int id, {String? assignedTo}) async {
    final res = await http.post(
      await _uri(
        '/production-tasks/$id/start',
        assignedTo != null && assignedTo.isNotEmpty
            ? {'assignedTo': assignedTo}
            : null,
      ),
      headers: await _authHeaders(),
    );
    return ProductionTask.fromJson(_decode(res));
  }

  Future<ProductionTask> completeTask(int id) async {
    final res = await http.post(
      await _uri('/production-tasks/$id/complete'),
      headers: await _authHeaders(),
    );
    return ProductionTask.fromJson(_decode(res));
  }

  // ---- Notifications ----
  Future<List<AppNotification>> getNotifications() async {
    final res = await http.get(
      await _uri('/notifications'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List)
        .map((e) => AppNotification.fromJson(e))
        .toList();
  }

  /// Уведомления, сгруппированные по заказу (то же, что использует дашборд), без лимита в 8 штук.
  /// from/to не заданы — все даты; экран уведомлений по умолчанию передаёт сегодняшний день.
  Future<(List<OrderNotificationGroup>, List<AppNotification>)>
  getNotificationsGrouped({DateTime? from, DateTime? to}) async {
    final res = await http.get(
      await _uri('/notifications/grouped', _rangeQuery(from, to)),
      headers: await _authHeaders(),
    );
    final json = _decode(res) as Map<String, dynamic>;
    final groups = (json['orderGroups'] as List)
        .map((e) => OrderNotificationGroup.fromJson(e as Map<String, dynamic>))
        .toList();
    final general = (json['general'] as List)
        .map((e) => AppNotification.fromJson(e as Map<String, dynamic>))
        .toList();
    return (groups, general);
  }

  // ---- Company profile ----
  Future<CompanyProfile> getCompanyProfile() async {
    final res = await http.get(
      await _uri('/company-profile'),
      headers: await _authHeaders(),
    );
    return CompanyProfile.fromJson(_decode(res));
  }

  Future<CompanyProfile> updateCompanyProfile(CompanyProfile profile) async {
    final res = await http.put(
      await _uri('/company-profile'),
      headers: await _authHeaders(),
      body: jsonEncode(profile.toJson()),
    );
    return CompanyProfile.fromJson(_decode(res));
  }

  // ---- Users ----
  Future<List<AppUser>> getUsers() async {
    final res = await http.get(
      await _uri('/users'),
      headers: await _authHeaders(),
    );
    return (_decode(res) as List).map((e) => AppUser.fromJson(e)).toList();
  }

  Future<AppUser> getUser(int id) async {
    final res = await http.get(
      await _uri('/users/$id'),
      headers: await _authHeaders(),
    );
    return AppUser.fromJson(_decode(res));
  }

  Future<AppUser> createUser(Map<String, dynamic> body) async {
    final res = await http.post(
      await _uri('/users'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return AppUser.fromJson(_decode(res));
  }

  Future<AppUser> updateUser(int id, Map<String, dynamic> body) async {
    final res = await http.put(
      await _uri('/users/$id'),
      headers: await _authHeaders(),
      body: jsonEncode(body),
    );
    return AppUser.fromJson(_decode(res));
  }
}
