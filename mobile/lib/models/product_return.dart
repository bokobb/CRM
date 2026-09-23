class ProductReturnItem {
  final int id;
  final int productId;
  final String productName;
  final String? productUnit;
  final double quantity;
  final double price;

  double get sum => quantity * price;

  ProductReturnItem({
    required this.id,
    required this.productId,
    required this.productName,
    this.productUnit,
    required this.quantity,
    required this.price,
  });

  factory ProductReturnItem.fromJson(Map<String, dynamic> json) => ProductReturnItem(
    id: json['id'] as int,
    productId: json['productId'] as int,
    productName: json['productName'] as String,
    productUnit: json['productUnit'] as String?,
    quantity: (json['quantity'] as num).toDouble(),
    price: (json['price'] as num).toDouble(),
  );
}

/// Возврат испорченного / просроченного товара от клиента (см. ProductReturn на бэкенде).
/// Создаётся с нуля, потом может быть закреплён за заказом того же клиента ([orderId]).
class ProductReturn {
  final int id;
  final int clientId;
  final String clientName;
  final int? orderId;
  final DateTime returnDate;
  final String? reason;
  final String createdBy;
  final DateTime createdAt;
  final double totalSum;
  final String? oneCDocumentNumber;
  final String? oneCSyncError;
  final List<ProductReturnItem> items;

  ProductReturn({
    required this.id,
    required this.clientId,
    required this.clientName,
    this.orderId,
    required this.returnDate,
    this.reason,
    required this.createdBy,
    required this.createdAt,
    required this.totalSum,
    this.oneCDocumentNumber,
    this.oneCSyncError,
    required this.items,
  });

  factory ProductReturn.fromJson(Map<String, dynamic> json) => ProductReturn(
    id: json['id'] as int,
    clientId: json['clientId'] as int,
    clientName: json['clientName'] as String,
    orderId: json['orderId'] as int?,
    returnDate: DateTime.parse(json['returnDate'] as String),
    reason: json['reason'] as String?,
    createdBy: json['createdBy'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    totalSum: (json['totalSum'] as num).toDouble(),
    oneCDocumentNumber: json['oneCDocumentNumber'] as String?,
    oneCSyncError: json['oneCSyncError'] as String?,
    items: (json['items'] as List).map((e) => ProductReturnItem.fromJson(e as Map<String, dynamic>)).toList(),
  );
}
