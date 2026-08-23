class AppNotification {
  final int id;
  final String recipient;
  final String message;
  final String channel;
  final int? orderId;
  final bool sent;
  final DateTime createdAt;

  AppNotification({
    required this.id,
    required this.recipient,
    required this.message,
    required this.channel,
    this.orderId,
    required this.sent,
    required this.createdAt,
  });

  factory AppNotification.fromJson(Map<String, dynamic> json) => AppNotification(
        id: json['id'] as int,
        recipient: json['recipient'] as String,
        message: json['message'] as String,
        channel: json['channel'] as String,
        orderId: json['orderId'] as int?,
        sent: json['sent'] as bool,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

/// Группа уведомлений одного заказа для дашборда: один заказ — одна строка,
/// вся история открывается по нажатию.
class OrderNotificationGroup {
  final int orderId;
  final String clientName;
  final String status;
  final String statusLabel;
  final DateTime lastNotifiedAt;
  final List<AppNotification> history;

  OrderNotificationGroup({
    required this.orderId,
    required this.clientName,
    required this.status,
    required this.statusLabel,
    required this.lastNotifiedAt,
    required this.history,
  });

  factory OrderNotificationGroup.fromJson(Map<String, dynamic> json) => OrderNotificationGroup(
        orderId: json['orderId'] as int,
        clientName: json['clientName'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        lastNotifiedAt: DateTime.parse(json['lastNotifiedAt'] as String),
        history: (json['history'] as List).map((e) => AppNotification.fromJson(e as Map<String, dynamic>)).toList(),
      );
}
