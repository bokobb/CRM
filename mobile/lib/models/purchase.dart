class Purchase {
  final int id;
  final int ingredientId;
  final String ingredientName;
  final String ingredientUnit;
  final double quantity;
  final String supplier;
  final double? totalPrice;
  final DateTime purchaseDate;
  final String createdBy;
  final DateTime createdAt;
  final String? imageUrl;

  Purchase({
    required this.id,
    required this.ingredientId,
    required this.ingredientName,
    required this.ingredientUnit,
    required this.quantity,
    required this.supplier,
    this.totalPrice,
    required this.purchaseDate,
    required this.createdBy,
    required this.createdAt,
    this.imageUrl,
  });

  factory Purchase.fromJson(Map<String, dynamic> json) => Purchase(
        id: json['id'] as int,
        ingredientId: json['ingredientId'] as int,
        ingredientName: json['ingredientName'] as String,
        ingredientUnit: json['ingredientUnit'] as String,
        quantity: (json['quantity'] as num).toDouble(),
        supplier: json['supplier'] as String,
        totalPrice: json['totalPrice'] != null ? (json['totalPrice'] as num).toDouble() : null,
        purchaseDate: DateTime.parse(json['purchaseDate'] as String),
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        imageUrl: json['imageUrl'] as String?,
      );

  /// Копия с другим imageUrl — используется, чтобы превратить относительный путь
  /// с сервера (загруженные файлы) в абсолютный URL для Image.network (см. ApiClient).
  Purchase withImageUrl(String url) => Purchase(
        id: id,
        ingredientId: ingredientId,
        ingredientName: ingredientName,
        ingredientUnit: ingredientUnit,
        quantity: quantity,
        supplier: supplier,
        totalPrice: totalPrice,
        purchaseDate: purchaseDate,
        createdBy: createdBy,
        createdAt: createdAt,
        imageUrl: url,
      );
}
