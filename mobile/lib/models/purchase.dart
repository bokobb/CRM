class PurchaseItem {
  final int id;
  final int ingredientId;
  final String ingredientName;
  final String ingredientUnit;
  final double quantity;
  final double? totalPrice;

  PurchaseItem({
    required this.id,
    required this.ingredientId,
    required this.ingredientName,
    required this.ingredientUnit,
    required this.quantity,
    this.totalPrice,
  });

  factory PurchaseItem.fromJson(Map<String, dynamic> json) => PurchaseItem(
        id: json['id'] as int,
        ingredientId: json['ingredientId'] as int,
        ingredientName: json['ingredientName'] as String,
        ingredientUnit: json['ingredientUnit'] as String,
        quantity: (json['quantity'] as num).toDouble(),
        totalPrice: json['totalPrice'] != null ? (json['totalPrice'] as num).toDouble() : null,
      );
}

/// Закупка у одного контрагента — может привезти сразу несколько товаров за раз (см. items).
class Purchase {
  final int id;
  final int counterpartyId;
  final String counterpartyName;
  final DateTime purchaseDate;
  final String createdBy;
  final DateTime createdAt;
  final String? imageUrl;
  final String? oneCDocumentNumber;
  final String? oneCSyncError;
  final List<PurchaseItem> items;

  double get totalSum => items.fold(0, (sum, i) => sum + (i.totalPrice ?? 0));

  Purchase({
    required this.id,
    required this.counterpartyId,
    required this.counterpartyName,
    required this.purchaseDate,
    required this.createdBy,
    required this.createdAt,
    this.imageUrl,
    this.oneCDocumentNumber,
    this.oneCSyncError,
    required this.items,
  });

  factory Purchase.fromJson(Map<String, dynamic> json) => Purchase(
        id: json['id'] as int,
        counterpartyId: json['counterpartyId'] as int,
        counterpartyName: json['counterpartyName'] as String,
        purchaseDate: DateTime.parse(json['purchaseDate'] as String),
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        imageUrl: json['imageUrl'] as String?,
        oneCDocumentNumber: json['oneCDocumentNumber'] as String?,
        oneCSyncError: json['oneCSyncError'] as String?,
        items: (json['items'] as List).map((e) => PurchaseItem.fromJson(e as Map<String, dynamic>)).toList(),
      );

  /// Копия с другим imageUrl — используется, чтобы превратить относительный путь
  /// с сервера (загруженные файлы) в абсолютный URL для Image.network (см. ApiClient).
  Purchase withImageUrl(String url) => Purchase(
        id: id,
        counterpartyId: counterpartyId,
        counterpartyName: counterpartyName,
        purchaseDate: purchaseDate,
        createdBy: createdBy,
        createdAt: createdAt,
        imageUrl: url,
        oneCDocumentNumber: oneCDocumentNumber,
        oneCSyncError: oneCSyncError,
        items: items,
      );
}
