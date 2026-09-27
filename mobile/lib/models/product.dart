/// Подразделения (корпуса) в 1С — GUID-ы из справочника "Подразделения организаций", те же,
/// что и в вебе (см. OneCDepartments.NAMES на бэкенде) — захардкожены, справочник у клиента
/// не меняется.
const Map<String, String> oneCDepartmentNames = {
  '8e8ccd44-7380-11f1-8dca-5254007c5efd': 'Производство Корпус А',
  '778f254e-66ef-11f1-82c6-5254007c5efd': 'Производство Корпус B',
  'b502dd92-7380-11f1-8dca-5254007c5efd': 'Производство Корпус С',
  'e72d5a7c-7380-11f1-8dca-5254007c5efd': 'Производство Корпус D',
};

class Product {
  final int id;
  final String name;
  final String? category;
  final String? group;
  final String? description;
  final double price;
  final String? unit;
  final double quantityInStock;
  final String? imageUrl;

  /// Полуфабрикат (например, "Пряник" — испечён без упаковки, сам идёт сырьём в рецепт
  /// другого товара) — скрыт из витрины заказов и "Остатков", остаток зеркалится в сырьё
  /// того же названия (см. Product.semiFinished на бэкенде).
  final bool semiFinished;

  /// GUID подразделения (корпуса) в 1С — см. [oneCDepartmentNames]. Null, если не задано
  /// (используется запасной вариант из конфигурации на бэкенде, см. OneCProductionSyncService).
  final String? oneCDepartmentKey;

  /// Вес одной штуки, г — только для товаров, где норму при оприходовании нужно сверять по
  /// общей массе группы, а не по штукам (см. Product.netWeightGrams на бэкенде). Null — товар
  /// в такой сверке не участвует.
  final double? netWeightGrams;

  /// Эталон группы для пересчёта отклонения массы в штуки (см. Product.massReferenceProduct на
  /// бэкенде) — максимум один товар в группе, с заданным [netWeightGrams].
  final bool massReferenceProduct;

  /// Скрыт админом от клиентов (витрина, разбор заказов из WhatsApp) — сотрудники видят и могут
  /// продать вручную (см. Product.hiddenFromClients на бэкенде). [hiddenFromClientsUntil] — по
  /// какую дату включительно; null — пока не вернут вручную.
  final bool hiddenFromClients;
  final DateTime? hiddenFromClientsUntil;

  /// Видят ли товар клиенты сегодня (считает backend с учётом даты «скрыт до»).
  final bool visibleToClients;

  Product({
    required this.id,
    required this.name,
    this.category,
    this.group,
    this.description,
    required this.price,
    this.unit,
    required this.quantityInStock,
    this.imageUrl,
    this.semiFinished = false,
    this.oneCDepartmentKey,
    this.netWeightGrams,
    this.massReferenceProduct = false,
    this.hiddenFromClients = false,
    this.hiddenFromClientsUntil,
    this.visibleToClients = true,
  });

  factory Product.fromJson(Map<String, dynamic> json) => Product(
        id: json['id'] as int,
        name: json['name'] as String,
        category: json['category'] as String?,
        group: json['group'] as String?,
        description: json['description'] as String?,
        price: (json['price'] as num).toDouble(),
        unit: json['unit'] as String?,
        quantityInStock: (json['quantityInStock'] as num).toDouble(),
        imageUrl: json['imageUrl'] as String?,
        semiFinished: json['semiFinished'] as bool? ?? false,
        oneCDepartmentKey: json['oneCDepartmentKey'] as String?,
        netWeightGrams: (json['netWeightGrams'] as num?)?.toDouble(),
        massReferenceProduct: json['massReferenceProduct'] as bool? ?? false,
        hiddenFromClients: json['hiddenFromClients'] as bool? ?? false,
        hiddenFromClientsUntil: json['hiddenFromClientsUntil'] == null
            ? null
            : DateTime.parse(json['hiddenFromClientsUntil'] as String),
        visibleToClients: json['visibleToClients'] as bool? ?? true,
      );

  /// Копия с другим imageUrl — используется, чтобы превратить относительный путь
  /// с сервера (загруженные файлы) в абсолютный URL для Image.network (см. ApiClient).
  Product withImageUrl(String url) => Product(
        id: id,
        name: name,
        category: category,
        group: group,
        description: description,
        price: price,
        unit: unit,
        quantityInStock: quantityInStock,
        imageUrl: url,
        semiFinished: semiFinished,
        oneCDepartmentKey: oneCDepartmentKey,
        netWeightGrams: netWeightGrams,
        massReferenceProduct: massReferenceProduct,
        hiddenFromClients: hiddenFromClients,
        hiddenFromClientsUntil: hiddenFromClientsUntil,
        visibleToClients: visibleToClients,
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
