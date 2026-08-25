import 'dart:async';
import 'dart:io' show Platform;

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../navigation.dart';

const _batteryChannel = MethodChannel('com.confectionery.crm_mobile/battery');

const _ordersChannel = AndroidNotificationChannel(
  'orders_channel',
  'Заказы',
  description: 'Уведомления о новых и изменившихся заказах',
  importance: Importance.high,
);

/// Обёртка над Firebase Messaging: подписка на тему роли (`role_<ROLE>`, см. NotificationService
/// на бэкенде) и показ уведомлений. Пока приложение свёрнуто/закрыто, системную плашку
/// показывает сама ОС по данным из FCM — отдельный код для этого не нужен. Но пока приложение
/// ОТКРЫТО, FCM по умолчанию НЕ показывает системное уведомление (это ограничение самого
/// Firebase, не наш баг) — раньше здесь был AlertDialog поверх экрана, но это ощущалось как
/// "какое-то неудобное окно", а не привычный push. Теперь вместо диалога показываем настоящее
/// системное уведомление через flutter_local_notifications — одинаковое поведение и звук,
/// открыто приложение или нет.
class PushService {
  PushService._();

  static final ValueNotifier<int> unreadCount = ValueNotifier<int>(0);
  static final FlutterLocalNotificationsPlugin _localNotifications = FlutterLocalNotificationsPlugin();
  static StreamSubscription<RemoteMessage>? _onMessageSub;
  static StreamSubscription<RemoteMessage>? _onOpenedSub;
  static bool _initialized = false;

  static void init() {
    if (_initialized) return;
    _initialized = true;
    _initLocalNotifications();
    _onMessageSub ??= FirebaseMessaging.onMessage.listen(_onForegroundMessage);
    // Пользователь тапнул системное уведомление, пока приложение было свёрнуто/закрыто.
    _onOpenedSub ??= FirebaseMessaging.onMessageOpenedApp.listen(_openOrderFromMessage);
  }

  static Future<void> _initLocalNotifications() async {
    if (kIsWeb || !Platform.isAndroid) return;
    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');
    await _localNotifications.initialize(
      const InitializationSettings(android: androidSettings),
      onDidReceiveNotificationResponse: (response) {
        final orderId = int.tryParse(response.payload ?? '');
        _openOrder(orderId);
      },
    );
    await _localNotifications
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(_ordersChannel);
  }

  static Future<void> subscribeToRole(String role) async {
    try {
      await FirebaseMessaging.instance.requestPermission();
      await FirebaseMessaging.instance.subscribeToTopic('role_$role');
      await _requestBatteryExemption();
    } catch (_) {
      // Firebase не подключён — просто не будет push, остальное приложение работает как обычно.
    }
  }

  /// На Samsung и некоторых других прошивках агрессивная батарейная оптимизация может
  /// "усыплять" приложение в фоне и блокировать доставку push. Показываем системный диалог
  /// с просьбой исключить приложение из этой оптимизации (пользователь может отказаться).
  static Future<void> _requestBatteryExemption() async {
    if (kIsWeb || !Platform.isAndroid) return;
    try {
      await _batteryChannel.invokeMethod('requestIgnoreBatteryOptimizations');
    } catch (_) {}
  }

  static Future<void> unsubscribeFromRole(String role) async {
    try {
      await FirebaseMessaging.instance.unsubscribeFromTopic('role_$role');
    } catch (_) {}
  }

  static void markRead() => unreadCount.value = 0;

  static void _onForegroundMessage(RemoteMessage message) {
    unreadCount.value++;
    final title = message.notification?.title ?? 'Юг Сладости';
    final body = message.notification?.body ?? '';
    final orderId = message.data['orderId'];

    if (kIsWeb || !Platform.isAndroid) {
      // На платформах без локальных уведомлений (web/десктоп) оставляем компактный in-app показ.
      final context = navigatorKey.currentContext;
      if (context == null) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('$title: $body'),
          action: orderId != null
              ? SnackBarAction(label: 'Открыть', onPressed: () => _openOrder(int.tryParse(orderId)))
              : null,
        ),
      );
      return;
    }

    _localNotifications.show(
      message.hashCode,
      title,
      body,
      NotificationDetails(
        android: AndroidNotificationDetails(
          _ordersChannel.id,
          _ordersChannel.name,
          channelDescription: _ordersChannel.description,
          importance: Importance.high,
          priority: Priority.high,
        ),
      ),
      payload: orderId,
    );
  }

  static void _openOrderFromMessage(RemoteMessage message) {
    _openOrder(int.tryParse(message.data['orderId'] ?? ''));
  }

  static void _openOrder(int? orderId) {
    if (orderId == null) return;
    navigatorKey.currentState?.pushNamed('/orders/detail', arguments: orderId);
  }
}
