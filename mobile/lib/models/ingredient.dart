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
