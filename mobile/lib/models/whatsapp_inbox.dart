class WhatsAppInboundItem {
  final int id;
  final String rawProductText;
  final double quantity;
  final int? productId;
  final String? productName;

  WhatsAppInboundItem({
    required this.id,
    required this.rawProductText,
    required this.quantity,
    this.productId,
    this.productName,
  });

  bool get isMatched => productId != null;

  factory WhatsAppInboundItem.fromJson(Map<String, dynamic> json) => WhatsAppInboundItem(
        id: json['id'] as int,
        rawProductText: json['rawProductText'] as String,
        quantity: (json['quantity'] as num).toDouble(),
        productId: json['productId'] as int?,
        productName: json['productName'] as String?,
      );
}

class WhatsAppInboundMessage {
  final int id;
  final int clientId;
  final String clientName;
  final String fromPhone;
  final String rawText;
  final String status;
  final DateTime createdAt;
  final List<WhatsAppInboundItem> items;

  WhatsAppInboundMessage({
    required this.id,
    required this.clientId,
    required this.clientName,
    required this.fromPhone,
    required this.rawText,
    required this.status,
    required this.createdAt,
    required this.items,
  });

  factory WhatsAppInboundMessage.fromJson(Map<String, dynamic> json) => WhatsAppInboundMessage(
        id: json['id'] as int,
        clientId: json['clientId'] as int,
        clientName: json['clientName'] as String,
        fromPhone: json['fromPhone'] as String,
        rawText: json['rawText'] as String,
        status: json['status'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        items: (json['items'] as List)
            .map((e) => WhatsAppInboundItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
