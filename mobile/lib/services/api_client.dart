import 'dart:convert';
import 'dart:io' show Platform;
import 'dart:typed_data';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/auth.dart';
import '../models/client.dart';
import '../models/client_price.dart';
import '../models/company_profile.dart';
import '../models/dashboard.dart';
import '../models/ingredient.dart';
import '../models/notification.dart';
import '../models/order.dart';
import '../models/product.dart';
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

  static String defaultBaseUrl() {
    if (kIsWeb) return 'http://localhost:8080/api';
    try {
      if (Platform.isAndroid) return 'http://192.168.0.8:8080/api';
    } catch (_) {
      // Platform недоступен вне мобильных/десктоп таргетов — считаем это web-подобным случаем.
    }
    return 'http://localhost:8080/api';
  }

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
  Future<List<Product>> getProducts() async {
    final res = await http.get(
      await _uri('/products'),
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
  Future<List<ProductionRequest>> createProductionRequest(List<(int, double)> items) async {
    final res = await http.post(
      await _uri('/production-requests'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'items': items.map((e) => {'productId': e.$1, 'batches': e.$2}).toList(),
      }),
    );
    return (_decode(res) as List).map((e) => ProductionRequest.fromJson(e)).toList();
  }

  /// Редактирование ещё не собранной заявки — только позиции, название заявки не меняется.
  Future<ProductionRequest> updateProductionRequest(int id, List<(int, double)> items) async {
    final res = await http.put(
      await _uri('/production-requests/$id'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'items': items.map((e) => {'productId': e.$1, 'batches': e.$2}).toList(),
      }),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// Отмена ещё не собранной заявки — доступна только пока сырьё со склада не списано (статус «Новая»).
  Future<ProductionRequest> cancelProductionRequest(int id) async {
    final res = await http.post(
      await _uri('/production-requests/$id/cancel'),
      headers: await _authHeaders(),
    );
    return ProductionRequest.fromJson(_decode(res));
  }

  /// itemIds должны покрывать ВСЕ позиции заявки — иначе сервер отклонит сборку.
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

  Future<List<Purchase>> getPurchases() async {
    final res = await http.get(
      await _uri('/purchases'),
      headers: await _authHeaders(),
    );
    final list = (_decode(res) as List).map((e) => Purchase.fromJson(e)).toList();
    return _withAbsolutePurchaseImages(list);
  }

  Future<Purchase> createPurchase({
    required int ingredientId,
    required double quantity,
    required String supplier,
    double? totalPrice,
    DateTime? purchaseDate,
  }) async {
    final res = await http.post(
      await _uri('/purchases'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'ingredientId': ingredientId,
        'quantity': quantity,
        'supplier': supplier,
        'totalPrice': totalPrice,
        'purchaseDate': purchaseDate?.toIso8601String().split('T').first,
      }),
    );
    return Purchase.fromJson(_decode(res));
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
  Future<Order> updateOrderItems(
    int id,
    Map<int, double> quantities, {
    Map<int, int>? productIdByItemId,
  }) async {
    final res = await http.put(
      await _uri('/orders/$id/items'),
      headers: await _authHeaders(),
      body: jsonEncode({
        'items': quantities.entries
            .map((e) => {
                  'itemId': e.key,
                  'quantity': e.value,
                  if (productIdByItemId?[e.key] != null) 'productId': productIdByItemId![e.key],
                })
            .toList(),
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
