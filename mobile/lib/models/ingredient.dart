class Ingredient {
  final int id;
  final String name;
  final String unit;
  final double quantityInStock;
  final double minThreshold;
  final bool lowStock;

  Ingredient({
    required this.id,
    required this.name,
    required this.unit,
    required this.quantityInStock,
    required this.minThreshold,
    required this.lowStock,
  });

  factory Ingredient.fromJson(Map<String, dynamic> json) => Ingredient(
        id: json['id'] as int,
        name: json['name'] as String,
        unit: json['unit'] as String,
        quantityInStock: (json['quantityInStock'] as num).toDouble(),
        minThreshold: (json['minThreshold'] as num).toDouble(),
        lowStock: json['lowStock'] as bool,
      );
}

/// kind: "INGREDIENT" (сырьё) или "PRODUCT" (готовая продукция) — itemId/itemName относятся к одному из них.
class StockMovement {
  final int id;
  final String kind;
  final int itemId;
  final String itemName;
  final String unit;
  final String type;
  final String typeLabel;
  final double quantity;
  final String? reason;
  final String createdBy;
  final DateTime createdAt;

  /// Номер документа 1С (оприходование/списание), null — ещё не выгружено.
  final String? oneCDocumentNumber;

  /// Ошибка выгрузки в 1С — показываем в списке, чтобы не потерялась.
  final String? oneCSyncError;

  bool get isProduct => kind == 'PRODUCT';

  StockMovement({
    required this.id,
    required this.kind,
    required this.itemId,
    required this.itemName,
    required this.unit,
    required this.type,
    required this.typeLabel,
    required this.quantity,
    this.reason,
    required this.createdBy,
    required this.createdAt,
    this.oneCDocumentNumber,
    this.oneCSyncError,
  });

  factory StockMovement.fromJson(Map<String, dynamic> json) => StockMovement(
        id: json['id'] as int,
        kind: json['kind'] as String,
        itemId: json['itemId'] as int,
        itemName: json['itemName'] as String,
        unit: json['unit'] as String,
        type: json['type'] as String,
        typeLabel: json['typeLabel'] as String,
        quantity: (json['quantity'] as num).toDouble(),
        reason: json['reason'] as String?,
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        oneCDocumentNumber: json['oneCDocumentNumber'] as String?,
        oneCSyncError: json['oneCSyncError'] as String?,
      );
}
