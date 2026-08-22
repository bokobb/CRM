class Product {
  final int id;
  final String name;
  final String? category;
  final String? description;
  final double price;

  Product({
    required this.id,
    required this.name,
    this.category,
    this.description,
    required this.price,
  });

  factory Product.fromJson(Map<String, dynamic> json) => Product(
        id: json['id'] as int,
        name: json['name'] as String,
        category: json['category'] as String?,
        description: json['description'] as String?,
        price: (json['price'] as num).toDouble(),
      );
}

class RecipeItem {
  final int ingredientId;
  final String ingredientName;
  final String unit;
  final double quantity;

  RecipeItem({
    required this.ingredientId,
    required this.ingredientName,
    required this.unit,
    required this.quantity,
  });

  factory RecipeItem.fromJson(Map<String, dynamic> json) => RecipeItem(
        ingredientId: json['ingredientId'] as int,
        ingredientName: json['ingredientName'] as String,
        unit: json['unit'] as String,
        quantity: (json['quantity'] as num).toDouble(),
      );
}

class Recipe {
  final double yieldQuantity;
  final List<RecipeItem> items;

  Recipe({required this.yieldQuantity, required this.items});

  factory Recipe.fromJson(Map<String, dynamic> json) => Recipe(
        yieldQuantity: (json['yieldQuantity'] as num).toDouble(),
        items: (json['items'] as List)
            .map((e) => RecipeItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
