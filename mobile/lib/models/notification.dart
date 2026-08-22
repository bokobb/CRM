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
