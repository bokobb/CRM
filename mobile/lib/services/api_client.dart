import 'dart:convert';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/auth.dart';
import '../models/client.dart';
import '../models/client_price.dart';
import '../models/dashboard.dart';
import '../models/ingredient.dart';
import '../models/notification.dart';
import '../models/order.dart';
import '../models/product.dart';
import '../navigation.dart';

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
      if (Platform.isAndroid) return 'http://10.0.2.2:8080/api';
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
      SharedPreferences.getInstance().then((p) => Future.wait([
            p.remove(_tokenKey),
            p.remove(_usernameKey),
            p.remove(_fullNameKey),
            p.remove(_roleKey),
            p.remove(_roleLabelKey),
          ]));
      navigatorKey.currentState?.pushNamedAndRemoveUntil('/login', (route) => false);
      message = 'Сессия истекла, войдите заново';
    }
    throw ApiException(message);
  }

  // ---- Auth ----
  Future<AuthSession> login(String username, String password) async {
    final res = await http.post(await _uri('/auth/login'),
        headers: {'Content-Type': 'application/json'}, body: jsonEncode({'username': username, 'password': password}));
    final session = AuthSession.fromJson(_decode(res) as Map<String, dynamic>);
    await _saveSession(session);
    return session;
  }

  Future<void> logout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
    await prefs.remove(_usernameKey);
    await prefs.remove(_fullNameKey);
    await prefs.remove(_roleKey);
    await prefs.remove(_roleLabelKey);
    _session = null;
  }

  // ---- Dashboard ----
  Future<Dashboard> getDashboard() async {
    final res = await http.get(await _uri('/dashboard'), headers: await _authHeaders());
    return Dashboard.fromJson(_decode(res) as Map<String, dynamic>);
  }

  // ---- Clients ----
  Future<List<Client>> getClients() async {
    final res = await http.get(await _uri('/clients'), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => Client.fromJson(e)).toList();
  }

  Future<Client> getClient(int id) async {
    final res = await http.get(await _uri('/clients/$id'), headers: await _authHeaders());
    return Client.fromJson(_decode(res));
  }

  Future<Client> createClient(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/clients'), headers: await _authHeaders(), body: jsonEncode(body));
    return Client.fromJson(_decode(res));
  }

  Future<Client> updateClient(int id, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/clients/$id'), headers: await _authHeaders(), body: jsonEncode(body));
    return Client.fromJson(_decode(res));
  }

  Future<void> deleteClient(int id) async {
    final res = await http.delete(await _uri('/clients/$id'), headers: await _authHeaders());
    _decode(res);
  }

  // ---- Индивидуальные цены клиента ----
  Future<List<ClientPrice>> getClientPrices(int clientId) async {
    final res = await http.get(await _uri('/clients/$clientId/prices'), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => ClientPrice.fromJson(e)).toList();
  }

  Future<ClientPrice> setClientPrice(int clientId, int productId, double price) async {
    final res = await http.put(await _uri('/clients/$clientId/prices/$productId'),
        headers: await _authHeaders(), body: jsonEncode({'price': price}));
    return ClientPrice.fromJson(_decode(res));
  }

  Future<void> deleteClientPrice(int clientId, int productId) async {
    final res = await http.delete(await _uri('/clients/$clientId/prices/$productId'), headers: await _authHeaders());
    _decode(res);
  }

  // ---- Products ----
  Future<List<Product>> getProducts() async {
    final res = await http.get(await _uri('/products'), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => Product.fromJson(e)).toList();
  }

  Future<Product> getProduct(int id) async {
    final res = await http.get(await _uri('/products/$id'), headers: await _authHeaders());
    return Product.fromJson(_decode(res));
  }

  Future<Product> createProduct(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/products'), headers: await _authHeaders(), body: jsonEncode(body));
    return Product.fromJson(_decode(res));
  }

  Future<Product> updateProduct(int id, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/products/$id'), headers: await _authHeaders(), body: jsonEncode(body));
    return Product.fromJson(_decode(res));
  }

  Future<void> deleteProduct(int id) async {
    final res = await http.delete(await _uri('/products/$id'), headers: await _authHeaders());
    _decode(res);
  }

  Future<Recipe> getRecipe(int productId) async {
    final res = await http.get(await _uri('/products/$productId/recipe'), headers: await _authHeaders());
    return Recipe.fromJson(_decode(res));
  }

  Future<Recipe> saveRecipe(int productId, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/products/$productId/recipe'), headers: await _authHeaders(), body: jsonEncode(body));
    return Recipe.fromJson(_decode(res));
  }

  // ---- Ingredients ----
  Future<List<Ingredient>> getIngredients() async {
    final res = await http.get(await _uri('/ingredients'), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => Ingredient.fromJson(e)).toList();
  }

  Future<Ingredient> getIngredient(int id) async {
    final res = await http.get(await _uri('/ingredients/$id'), headers: await _authHeaders());
    return Ingredient.fromJson(_decode(res));
  }

  Future<Ingredient> createIngredient(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/ingredients'), headers: await _authHeaders(), body: jsonEncode(body));
    return Ingredient.fromJson(_decode(res));
  }

  Future<Ingredient> updateIngredient(int id, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/ingredients/$id'), headers: await _authHeaders(), body: jsonEncode(body));
    return Ingredient.fromJson(_decode(res));
  }

  Future<void> deleteIngredient(int id) async {
    final res = await http.delete(await _uri('/ingredients/$id'), headers: await _authHeaders());
    _decode(res);
  }

  // ---- Orders ----
  Future<List<OrderSummary>> getOrders({String? status}) async {
    final res =
        await http.get(await _uri('/orders', status != null ? {'status': status} : null), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => OrderSummary.fromJson(e)).toList();
  }

  Future<Order> getOrder(int id) async {
    final res = await http.get(await _uri('/orders/$id'), headers: await _authHeaders());
    return Order.fromJson(_decode(res));
  }

  Future<Order> createOrder(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/orders'), headers: await _authHeaders(), body: jsonEncode(body));
    return Order.fromJson(_decode(res));
  }

  Future<Order> confirmOrder(int id) async {
    final res = await http.post(await _uri('/orders/$id/confirm'), headers: await _authHeaders());
    return Order.fromJson(_decode(res));
  }

  Future<Order> cancelOrder(int id) async {
    final res = await http.post(await _uri('/orders/$id/cancel'), headers: await _authHeaders());
    return Order.fromJson(_decode(res));
  }

  Future<Order> shipOrder(int id, String vehicleNumber) async {
    final res = await http.post(await _uri('/orders/$id/ship'),
        headers: await _authHeaders(), body: jsonEncode({'vehicleNumber': vehicleNumber}));
    return Order.fromJson(_decode(res));
  }

  // ---- Production tasks ----
  Future<List<ProductionTask>> getProductionTasks({String? status}) async {
    final res = await http.get(await _uri('/production-tasks', status != null ? {'status': status} : null),
        headers: await _authHeaders());
    return (_decode(res) as List).map((e) => ProductionTask.fromJson(e)).toList();
  }

  Future<ProductionTask> startTask(int id, {String? assignedTo}) async {
    final res = await http.post(
        await _uri('/production-tasks/$id/start', assignedTo != null && assignedTo.isNotEmpty ? {'assignedTo': assignedTo} : null),
        headers: await _authHeaders());
    return ProductionTask.fromJson(_decode(res));
  }

  Future<ProductionTask> completeTask(int id) async {
    final res = await http.post(await _uri('/production-tasks/$id/complete'), headers: await _authHeaders());
    return ProductionTask.fromJson(_decode(res));
  }

  // ---- Notifications ----
  Future<List<AppNotification>> getNotifications() async {
    final res = await http.get(await _uri('/notifications'), headers: await _authHeaders());
    return (_decode(res) as List).map((e) => AppNotification.fromJson(e)).toList();
  }
}
