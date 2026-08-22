import 'dart:convert';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/client.dart';
import '../models/dashboard.dart';
import '../models/ingredient.dart';
import '../models/notification.dart';
import '../models/order.dart';
import '../models/product.dart';

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

  Map<String, String> get _jsonHeaders => {'Content-Type': 'application/json'};

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
    throw ApiException(message);
  }

  // ---- Dashboard ----
  Future<Dashboard> getDashboard() async {
    final res = await http.get(await _uri('/dashboard'));
    return Dashboard.fromJson(_decode(res) as Map<String, dynamic>);
  }

  // ---- Clients ----
  Future<List<Client>> getClients() async {
    final res = await http.get(await _uri('/clients'));
    return (_decode(res) as List).map((e) => Client.fromJson(e)).toList();
  }

  Future<Client> getClient(int id) async {
    final res = await http.get(await _uri('/clients/$id'));
    return Client.fromJson(_decode(res));
  }

  Future<Client> createClient(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/clients'), headers: _jsonHeaders, body: jsonEncode(body));
    return Client.fromJson(_decode(res));
  }

  Future<Client> updateClient(int id, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/clients/$id'), headers: _jsonHeaders, body: jsonEncode(body));
    return Client.fromJson(_decode(res));
  }

  Future<void> deleteClient(int id) async {
    final res = await http.delete(await _uri('/clients/$id'));
    _decode(res);
  }

  // ---- Products ----
  Future<List<Product>> getProducts() async {
    final res = await http.get(await _uri('/products'));
    return (_decode(res) as List).map((e) => Product.fromJson(e)).toList();
  }

  Future<Product> getProduct(int id) async {
    final res = await http.get(await _uri('/products/$id'));
    return Product.fromJson(_decode(res));
  }

  Future<Product> createProduct(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/products'), headers: _jsonHeaders, body: jsonEncode(body));
    return Product.fromJson(_decode(res));
  }

  Future<Product> updateProduct(int id, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/products/$id'), headers: _jsonHeaders, body: jsonEncode(body));
    return Product.fromJson(_decode(res));
  }

  Future<void> deleteProduct(int id) async {
    final res = await http.delete(await _uri('/products/$id'));
    _decode(res);
  }

  Future<Recipe> getRecipe(int productId) async {
    final res = await http.get(await _uri('/products/$productId/recipe'));
    return Recipe.fromJson(_decode(res));
  }

  Future<Recipe> saveRecipe(int productId, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/products/$productId/recipe'), headers: _jsonHeaders, body: jsonEncode(body));
    return Recipe.fromJson(_decode(res));
  }

  // ---- Ingredients ----
  Future<List<Ingredient>> getIngredients() async {
    final res = await http.get(await _uri('/ingredients'));
    return (_decode(res) as List).map((e) => Ingredient.fromJson(e)).toList();
  }

  Future<Ingredient> getIngredient(int id) async {
    final res = await http.get(await _uri('/ingredients/$id'));
    return Ingredient.fromJson(_decode(res));
  }

  Future<Ingredient> createIngredient(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/ingredients'), headers: _jsonHeaders, body: jsonEncode(body));
    return Ingredient.fromJson(_decode(res));
  }

  Future<Ingredient> updateIngredient(int id, Map<String, dynamic> body) async {
    final res = await http.put(await _uri('/ingredients/$id'), headers: _jsonHeaders, body: jsonEncode(body));
    return Ingredient.fromJson(_decode(res));
  }

  Future<void> deleteIngredient(int id) async {
    final res = await http.delete(await _uri('/ingredients/$id'));
    _decode(res);
  }

  // ---- Orders ----
  Future<List<OrderSummary>> getOrders({String? status}) async {
    final res = await http.get(await _uri('/orders', status != null ? {'status': status} : null));
    return (_decode(res) as List).map((e) => OrderSummary.fromJson(e)).toList();
  }

  Future<Order> getOrder(int id) async {
    final res = await http.get(await _uri('/orders/$id'));
    return Order.fromJson(_decode(res));
  }

  Future<Order> createOrder(Map<String, dynamic> body) async {
    final res = await http.post(await _uri('/orders'), headers: _jsonHeaders, body: jsonEncode(body));
    return Order.fromJson(_decode(res));
  }

  Future<Order> confirmOrder(int id) async {
    final res = await http.post(await _uri('/orders/$id/confirm'));
    return Order.fromJson(_decode(res));
  }

  Future<Order> cancelOrder(int id) async {
    final res = await http.post(await _uri('/orders/$id/cancel'));
    return Order.fromJson(_decode(res));
  }

  Future<Order> deliverOrder(int id) async {
    final res = await http.post(await _uri('/orders/$id/deliver'));
    return Order.fromJson(_decode(res));
  }

  // ---- Production tasks ----
  Future<List<ProductionTask>> getProductionTasks({String? status}) async {
    final res = await http.get(await _uri('/production-tasks', status != null ? {'status': status} : null));
    return (_decode(res) as List).map((e) => ProductionTask.fromJson(e)).toList();
  }

  Future<ProductionTask> startTask(int id, {String? assignedTo}) async {
    final res = await http.post(await _uri(
        '/production-tasks/$id/start', assignedTo != null && assignedTo.isNotEmpty ? {'assignedTo': assignedTo} : null));
    return ProductionTask.fromJson(_decode(res));
  }

  Future<ProductionTask> completeTask(int id) async {
    final res = await http.post(await _uri('/production-tasks/$id/complete'));
    return ProductionTask.fromJson(_decode(res));
  }

  // ---- Notifications ----
  Future<List<AppNotification>> getNotifications() async {
    final res = await http.get(await _uri('/notifications'));
    return (_decode(res) as List).map((e) => AppNotification.fromJson(e)).toList();
  }
}
