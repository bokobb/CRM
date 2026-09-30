// Техкарта товара (экран «Производство»): рецептура из базы + этапы, ККТ, качество, хранение.
// См. backend TechCardApiController.

String? _str(dynamic v) {
  final s = v as String?;
  return s == null || s.isEmpty ? null : s;
}

class TechCardSummary {
  final int productId;
  final String name;
  final String? group;
  final String? unit;
  final String? imageUrl;
  final bool semiFinished;
  final double yieldQuantity;
  final int ingredientCount;
  final int stageCount;
  final int ccpCount;

  TechCardSummary({
    required this.productId,
    required this.name,
    this.group,
    this.unit,
    this.imageUrl,
    required this.semiFinished,
    required this.yieldQuantity,
    required this.ingredientCount,
    required this.stageCount,
    required this.ccpCount,
  });

  factory TechCardSummary.fromJson(Map<String, dynamic> json) => TechCardSummary(
        productId: json['productId'] as int,
        name: json['name'] as String,
        group: _str(json['group']),
        unit: _str(json['unit']),
        imageUrl: _str(json['imageUrl']),
        semiFinished: json['semiFinished'] as bool? ?? false,
        yieldQuantity: (json['yieldQuantity'] as num).toDouble(),
        ingredientCount: json['ingredientCount'] as int,
        stageCount: json['stageCount'] as int,
        ccpCount: json['ccpCount'] as int,
      );
}

class TechCardItem {
  final int ingredientId;
  final String name;
  final String unit;

  /// Количество на один замес (yieldQuantity товара).
  final double quantity;

  /// ₸ за единицу сырья по последней закупке; null — закупок с ценой не было.
  final double? unitPrice;
  final DateTime? priceDate;

  TechCardItem({
    required this.ingredientId,
    required this.name,
    required this.unit,
    required this.quantity,
    this.unitPrice,
    this.priceDate,
  });

  factory TechCardItem.fromJson(Map<String, dynamic> json) => TechCardItem(
        ingredientId: json['ingredientId'] as int,
        name: json['name'] as String,
        unit: json['unit'] as String? ?? '',
        quantity: (json['quantity'] as num).toDouble(),
        unitPrice: (json['unitPrice'] as num?)?.toDouble(),
        priceDate: json['priceDate'] == null ? null : DateTime.parse(json['priceDate'] as String),
      );
}

class TechCardStage {
  final String title;
  final String? description;
  final String? temperature;
  final String? duration;
  final bool ccp;

  TechCardStage({required this.title, this.description, this.temperature, this.duration, this.ccp = false});

  factory TechCardStage.fromJson(Map<String, dynamic> json) => TechCardStage(
        title: json['title'] as String,
        description: _str(json['description']),
        temperature: _str(json['temperature']),
        duration: _str(json['duration']),
        ccp: json['ccp'] as bool? ?? false,
      );

  Map<String, dynamic> toJson() => {
        'title': title,
        'description': description,
        'temperature': temperature,
        'duration': duration,
        'ccp': ccp,
      };
}

class TechCard {
  final int productId;
  final String name;
  final String? group;
  final String? unit;
  final String? imageUrl;
  final double? netWeightGrams;
  final double salePrice;
  final double yieldQuantity;
  final List<TechCardItem> items;
  final String? shelfLife;
  final String? storageConditions;
  final String? packaging;
  final String? qualityLook;
  final String? qualityColor;
  final String? qualityCrumb;
  final String? qualityTaste;
  final List<TechCardStage> stages;

  TechCard({
    required this.productId,
    required this.name,
    this.group,
    this.unit,
    this.imageUrl,
    this.netWeightGrams,
    required this.salePrice,
    required this.yieldQuantity,
    required this.items,
    this.shelfLife,
    this.storageConditions,
    this.packaging,
    this.qualityLook,
    this.qualityColor,
    this.qualityCrumb,
    this.qualityTaste,
    required this.stages,
  });

  factory TechCard.fromJson(Map<String, dynamic> json) => TechCard(
        productId: json['productId'] as int,
        name: json['name'] as String,
        group: _str(json['group']),
        unit: _str(json['unit']),
        imageUrl: _str(json['imageUrl']),
        netWeightGrams: (json['netWeightGrams'] as num?)?.toDouble(),
        salePrice: (json['salePrice'] as num?)?.toDouble() ?? 0,
        yieldQuantity: (json['yieldQuantity'] as num).toDouble(),
        items: (json['items'] as List).map((e) => TechCardItem.fromJson(e as Map<String, dynamic>)).toList(),
        shelfLife: _str(json['shelfLife']),
        storageConditions: _str(json['storageConditions']),
        packaging: _str(json['packaging']),
        qualityLook: _str(json['qualityLook']),
        qualityColor: _str(json['qualityColor']),
        qualityCrumb: _str(json['qualityCrumb']),
        qualityTaste: _str(json['qualityTaste']),
        stages: (json['stages'] as List).map((e) => TechCardStage.fromJson(e as Map<String, dynamic>)).toList(),
      );
}
