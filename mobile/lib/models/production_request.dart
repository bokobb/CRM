class ProductionRequestSummary {
  final int id;
  final String productName;
  final double batches;
  final String status;
  final String statusLabel;
  final String createdBy;
  final DateTime createdAt;

  ProductionRequestSummary({
    required this.id,
    required this.productName,
    required this.batches,
    required this.status,
    required this.statusLabel,
    required this.createdBy,
    required this.createdAt,
  });

  factory ProductionRequestSummary.fromJson(Map<String, dynamic> json) => ProductionRequestSummary(
        id: json['id'] as int,
        productName: json['productName'] as String,
        batches: (json['batches'] as num).toDouble(),
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

class ProductionRequestItem {
  final int id;
  final int ingredientId;
  final String ingredientName;
  final String ingredientUnit;
  final double requiredQuantity;

  ProductionRequestItem({
    required this.id,
    required this.ingredientId,
    required this.ingredientName,
    required this.ingredientUnit,
    required this.requiredQuantity,
  });

  factory ProductionRequestItem.fromJson(Map<String, dynamic> json) => ProductionRequestItem(
        id: json['id'] as int,
        ingredientId: json['ingredientId'] as int,
        ingredientName: json['ingredientName'] as String,
        ingredientUnit: json['ingredientUnit'] as String,
        requiredQuantity: (json['requiredQuantity'] as num).toDouble(),
      );
}

class ProductionRequest {
  final int id;
  final int productId;
  final String productName;
  final String? productUnit;
  final double batches;
  final String status;
  final String statusLabel;
  final String createdBy;
  final DateTime createdAt;
  final String? collectedBy;
  final DateTime? collectedAt;
  final String? receivedBy;
  final DateTime? receivedAt;
  final double? receivedQuantity;
  final List<ProductionRequestItem> items;

  ProductionRequest({
    required this.id,
    required this.productId,
    required this.productName,
    this.productUnit,
    required this.batches,
    required this.status,
    required this.statusLabel,
    required this.createdBy,
    required this.createdAt,
    this.collectedBy,
    this.collectedAt,
    this.receivedBy,
    this.receivedAt,
    this.receivedQuantity,
    required this.items,
  });

  factory ProductionRequest.fromJson(Map<String, dynamic> json) => ProductionRequest(
        id: json['id'] as int,
        productId: json['productId'] as int,
        productName: json['productName'] as String,
        productUnit: json['productUnit'] as String?,
        batches: (json['batches'] as num).toDouble(),
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        collectedBy: json['collectedBy'] as String?,
        collectedAt: json['collectedAt'] != null ? DateTime.parse(json['collectedAt'] as String) : null,
        receivedBy: json['receivedBy'] as String?,
        receivedAt: json['receivedAt'] != null ? DateTime.parse(json['receivedAt'] as String) : null,
        receivedQuantity: json['receivedQuantity'] != null ? (json['receivedQuantity'] as num).toDouble() : null,
        items: (json['items'] as List)
            .map((e) => ProductionRequestItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
