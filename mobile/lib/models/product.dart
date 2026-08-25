class Product {
  final int id;
  final String name;
  final String? category;
  final String? description;
  final double price;
  final String? unit;
  final double quantityInStock;
  final String? imageUrl;

  Product({
    required this.id,
    required this.name,
    this.category,
    this.description,
    required this.price,
    this.unit,
    required this.quantityInStock,
    this.imageUrl,
  });

  factory Product.fromJson(Map<String, dynamic> json) => Product(
        id: json['id'] as int,
        name: json['name'] as String,
        category: json['category'] as String?,
        description: json['description'] as String?,
        price: (json['price'] as num).toDouble(),
        unit: json['unit'] as String?,
        quantityInStock: (json['quantityInStock'] as num).toDouble(),
        imageUrl: json['imageUrl'] as String?,
      );

  /// Копия с другим imageUrl — используется, чтобы превратить относительный путь
  /// с сервера (загруженные файлы) в абсолютный URL для Image.network (см. ApiClient).
  Product withImageUrl(String url) => Product(
        id: id,
        name: name,
        category: category,
        description: description,
        price: price,
        unit: unit,
        quantityInStock: quantityInStock,
        imageUrl: url,
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
