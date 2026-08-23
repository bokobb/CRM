class ClientPrice {
  final int id;
  final int productId;
  final String productName;
  final double basePrice;
  final double price;
  final DateTime updatedAt;

  ClientPrice({
    required this.id,
    required this.productId,
    required this.productName,
    required this.basePrice,
    required this.price,
    required this.updatedAt,
  });

  factory ClientPrice.fromJson(Map<String, dynamic> json) => ClientPrice(
        id: json['id'] as int,
        productId: json['productId'] as int,
        productName: json['productName'] as String,
        basePrice: (json['basePrice'] as num).toDouble(),
        price: (json['price'] as num).toDouble(),
        updatedAt: DateTime.parse(json['updatedAt'] as String),
      );
}
