import 'dart:async';
import 'dart:io' show Platform;

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../navigation.dart';

const _batteryChannel = MethodChannel('com.confectionery.crm_mobile/battery');

/// Обёртка над Firebase Messaging: подписка на тему роли (`role_<ROLE>`, см. NotificationService
/// на бэкенде), всплывающий диалог при уведомлении, полученном пока приложение открыто,
/// и счётчик непрочитанных для бейджа в AppDrawer. В свёрнутом/закрытом состоянии системную
/// плашку показывает сама ОС — отдельный обработчик для этого не нужен.
class PushService {
  PushService._();

  static final ValueNotifier<int> unreadCount = ValueNotifier<int>(0);
  static StreamSubscription<RemoteMessage>? _sub;

  static void init() {
    _sub ??= FirebaseMessaging.onMessage.listen(_onForegroundMessage);
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
    final context = navigatorKey.currentContext;
    if (context == null) return;
    final orderId = int.tryParse(message.data['orderId'] ?? '');
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(message.notification?.title ?? 'Уведомление'),
        content: Text(message.notification?.body ?? ''),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('Закрыть')),
          if (orderId != null)
            FilledButton(
              onPressed: () {
                Navigator.pop(dialogContext);
                navigatorKey.currentState?.pushNamed('/orders/detail', arguments: orderId);
              },
              child: const Text('Открыть заказ'),
            ),
        ],
      ),
    );
  }
}
