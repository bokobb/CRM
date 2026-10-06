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

  /// Упаковка для отгрузки — по ней считается «Объём и масса» заказа (свайп заказа вправо):
  /// вес одной единицы (шт/кг) брутто, кг; сколько единиц в коробке; объём коробки, м³.
  final double? unitWeightKg;
  final double? unitsPerBox;
  final double? boxVolumeM3;

  /// Вторая упаковка (например, «по 24» у «Коржики (12/24 шт)») — выбирает клиент при отгрузке.
  final double? unitsPerBox2;
  final double? boxVolume2M3;

  /// Код номенклатуры в 1С (сверяется с 1С раз в сутки) — колонка «Код» в «Объём и масса».
  final String? oneCCode;

  /// Штуки в коробке из названия: «(15 шт)» → [15], «(12/24 шт)» → [12, 24] — как на бэкенде
  /// (ProductPacking), когда в карточке не заполнено.
  static List<double> unitsFromName(String name) {
    final m = RegExp(r'\((\d+)(?:\s*/\s*(\d+))?\s*шт\.?\)').firstMatch(name);
    if (m == null) return [];
    return [double.parse(m.group(1)!), if (m.group(2) != null) double.parse(m.group(2)!)];
  }

  /// Варианты «штук в коробке» для выбора упаковки (0, 1 или 2).
  List<double> get packingUnits {
    final fromName = unitsFromName(name);
    final first = (unitsPerBox ?? 0) > 0 ? unitsPerBox : (fromName.isNotEmpty ? fromName[0] : null);
    final second = (unitsPerBox2 ?? 0) > 0 ? unitsPerBox2 : (fromName.length > 1 ? fromName[1] : null);
    return [if (first != null) first, if (second != null) second];
  }

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
    this.unitWeightKg,
    this.unitsPerBox,
    this.boxVolumeM3,
    this.unitsPerBox2,
    this.boxVolume2M3,
    this.oneCCode,
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
        unitWeightKg: (json['unitWeightKg'] as num?)?.toDouble(),
        unitsPerBox: (json['unitsPerBox'] as num?)?.toDouble(),
        boxVolumeM3: (json['boxVolumeM3'] as num?)?.toDouble(),
        unitsPerBox2: (json['unitsPerBox2'] as num?)?.toDouble(),
        boxVolume2M3: (json['boxVolume2M3'] as num?)?.toDouble(),
        oneCCode: json['oneCCode'] as String?,
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
        unitWeightKg: unitWeightKg,
        unitsPerBox: unitsPerBox,
        boxVolumeM3: boxVolumeM3,
        unitsPerBox2: unitsPerBox2,
        boxVolume2M3: boxVolume2M3,
        oneCCode: oneCCode,
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
