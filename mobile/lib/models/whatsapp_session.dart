/// Статус подключения WhatsApp-шлюза — connecting/qr/open/closed/unavailable.
class WhatsAppSession {
  final String status;
  final String? phone;

  WhatsAppSession({required this.status, this.phone});

  factory WhatsAppSession.fromJson(Map<String, dynamic> json) => WhatsAppSession(
        status: json['status'] as String,
        phone: json['phone'] as String?,
      );
}
