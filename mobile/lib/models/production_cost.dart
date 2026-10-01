// Себестоимость производства за день (экран «Производство») — заявки на сырьё × техкарты.
// См. backend ProductionCostDto.

double _d(dynamic v) => (v as num?)?.toDouble() ?? 0;
double? _dn(dynamic v) => (v as num?)?.toDouble();

class ProductionCost {
  final DateTime date;

  /// Сырьё всех заявок дня: норма по техкартам и фактически выданное.
  final double normCost;
  final double issuedCost;

  /// Зарплата бригад; null — роль не видит зарплату (технолог).
  final double? laborCost;

  /// Накладные цеха (оклады группы «Цех»), разнесённые по заявкам дня, и дневной фонд; null — роль не видит.
  final double? overheadCost;
  final double? overheadFund;

  /// Только готовая продукция (без полуфабрикатов — они войдут в товар в день упаковки).
  final double finishedCost;
  final double finishedSaleValue;
  final double semiFinishedCost;
  final List<ProductionCostRequest> requests;
  final List<ProductionCostDay> history;
  final List<String> unpricedIngredients;

  ProductionCost({
    required this.date,
    required this.normCost,
    required this.issuedCost,
    this.laborCost,
    this.overheadCost,
    this.overheadFund,
    required this.finishedCost,
    required this.finishedSaleValue,
    required this.semiFinishedCost,
    required this.requests,
    required this.history,
    required this.unpricedIngredients,
  });

  factory ProductionCost.fromJson(Map<String, dynamic> json) {
    final t = json['totals'] as Map<String, dynamic>;
    return ProductionCost(
      date: DateTime.parse(json['date'] as String),
      normCost: _d(t['normCost']),
      issuedCost: _d(t['issuedCost']),
      laborCost: _dn(t['laborCost']),
      overheadCost: _dn(t['overheadCost']),
      overheadFund: _dn(t['overheadFund']),
      finishedCost: _d(t['finishedCost']),
      finishedSaleValue: _d(t['finishedSaleValue']),
      semiFinishedCost: _d(t['semiFinishedCost']),
      requests: [for (final r in json['requests'] as List) ProductionCostRequest.fromJson(r as Map<String, dynamic>)],
      history: [for (final h in json['history'] as List) ProductionCostDay.fromJson(h as Map<String, dynamic>)],
      unpricedIngredients: [for (final s in json['unpricedIngredients'] as List) s as String],
    );
  }
}

class ProductionCostRequest {
  final int id;
  final String title;
  final String status;
  final String statusLabel;
  final double normCost;
  final double issuedCost;
  final double? laborCost;
  final double? overheadCost;
  final List<BrigadeMember> brigade;
  final List<ProductionCostLine> lines;

  ProductionCostRequest({
    required this.id,
    required this.title,
    required this.status,
    required this.statusLabel,
    required this.normCost,
    required this.issuedCost,
    this.laborCost,
    this.overheadCost,
    required this.brigade,
    required this.lines,
  });

  factory ProductionCostRequest.fromJson(Map<String, dynamic> json) => ProductionCostRequest(
        id: json['id'] as int,
        title: json['title'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        normCost: _d(json['normCost']),
        issuedCost: _d(json['issuedCost']),
        laborCost: _dn(json['laborCost']),
        overheadCost: _dn(json['overheadCost']),
        brigade: [for (final m in json['brigade'] as List? ?? []) BrigadeMember.fromJson(m as Map<String, dynamic>)],
        lines: [for (final l in json['lines'] as List) ProductionCostLine.fromJson(l as Map<String, dynamic>)],
      );
}

class ProductionCostLine {
  final int productId;
  final String name;
  final String? unit;
  final bool semiFinished;
  final double batches;

  final double plannedQuantity;

  /// Оприходовано, а пока не оприходовано — план ([received] = false).
  final double quantity;
  final bool received;
  final double normCost;

  /// Фактически выданное сырьё заявки, разнесённое по товарам пропорционально норме.
  final double rawCost;

  /// Доля зарплаты бригады; null — роль не видит зарплату.
  final double? laborCost;

  /// Доля накладных цеха; null — роль не видит.
  final double? overheadCost;

  /// Сырьё (факт) + зарплата + накладные цеха.
  final double cost;
  final double? unitCost;

  /// План: норма сырья по техкарте + зарплата, на плановое количество.
  final double? planUnitCost;
  final double? salePrice;

  ProductionCostLine({
    required this.productId,
    required this.name,
    this.unit,
    required this.semiFinished,
    required this.batches,
    required this.plannedQuantity,
    required this.quantity,
    required this.received,
    required this.normCost,
    required this.rawCost,
    this.laborCost,
    this.overheadCost,
    required this.cost,
    this.unitCost,
    this.planUnitCost,
    this.salePrice,
  });

  factory ProductionCostLine.fromJson(Map<String, dynamic> json) => ProductionCostLine(
        productId: json['productId'] as int,
        name: json['name'] as String,
        unit: json['unit'] as String?,
        semiFinished: json['semiFinished'] as bool? ?? false,
        batches: _d(json['batches']),
        plannedQuantity: _d(json['plannedQuantity']),
        quantity: _d(json['quantity']),
        received: json['received'] as bool? ?? false,
        normCost: _d(json['normCost']),
        rawCost: _d(json['rawCost']),
        laborCost: _dn(json['laborCost']),
        overheadCost: _dn(json['overheadCost']),
        cost: _d(json['cost']),
        unitCost: _dn(json['unitCost']),
        planUnitCost: _dn(json['planUnitCost']),
        salePrice: _dn(json['salePrice']),
      );
}

class ProductionCostDay {
  final DateTime date;
  final double finishedCost;
  final double finishedSaleValue;
  final double normCost;
  final double issuedCost;

  ProductionCostDay({
    required this.date,
    required this.finishedCost,
    required this.finishedSaleValue,
    required this.normCost,
    required this.issuedCost,
  });

  factory ProductionCostDay.fromJson(Map<String, dynamic> json) => ProductionCostDay(
        date: DateTime.parse(json['date'] as String),
        finishedCost: _d(json['finishedCost']),
        finishedSaleValue: _d(json['finishedSaleValue']),
        normCost: _d(json['normCost']),
        issuedCost: _d(json['issuedCost']),
      );
}

class BrigadeMember {
  final int employeeId;
  final String fullName;

  /// null — роль не видит ставки.
  final double? dailyRate;

  BrigadeMember({required this.employeeId, required this.fullName, this.dailyRate});

  factory BrigadeMember.fromJson(Map<String, dynamic> json) => BrigadeMember(
        employeeId: json['employeeId'] as int,
        fullName: json['fullName'] as String,
        dailyRate: _dn(json['dailyRate']),
      );
}

/// Экран заявки из «Производства»: строки + сырьё норма/выдано с ценами.
class ProductionRequestCost {
  final DateTime date;
  final ProductionCostRequest request;
  final List<ProductionCostIngredient> ingredients;
  final List<String> unpricedIngredients;

  ProductionRequestCost({
    required this.date,
    required this.request,
    required this.ingredients,
    required this.unpricedIngredients,
  });

  factory ProductionRequestCost.fromJson(Map<String, dynamic> json) => ProductionRequestCost(
        date: DateTime.parse(json['date'] as String),
        request: ProductionCostRequest.fromJson(json['request'] as Map<String, dynamic>),
        ingredients: [for (final i in json['ingredients'] as List) ProductionCostIngredient.fromJson(i as Map<String, dynamic>)],
        unpricedIngredients: [for (final s in json['unpricedIngredients'] as List) s as String],
      );
}

class ProductionCostIngredient {
  final int ingredientId;
  final String name;
  final String unit;
  final double normQuantity;
  final double issuedQuantity;
  final double? unitPrice;
  final DateTime? priceDate;

  /// PURCHASE — последняя закупка, SEMI_FINISHED — по рецептуре полуфабриката, null — нет цены.
  final String? priceSource;
  final double? normCost;
  final double? issuedCost;

  /// Было в заявке до замены (выдали другое сырьё вместо этого).
  final String? originalName;

  ProductionCostIngredient({
    required this.ingredientId,
    required this.name,
    required this.unit,
    required this.normQuantity,
    required this.issuedQuantity,
    this.unitPrice,
    this.priceDate,
    this.priceSource,
    this.normCost,
    this.issuedCost,
    this.originalName,
  });

  factory ProductionCostIngredient.fromJson(Map<String, dynamic> json) => ProductionCostIngredient(
        ingredientId: json['ingredientId'] as int,
        name: json['name'] as String,
        unit: json['unit'] as String? ?? '',
        normQuantity: _d(json['normQuantity']),
        issuedQuantity: _d(json['issuedQuantity']),
        unitPrice: _dn(json['unitPrice']),
        priceDate: json['priceDate'] == null ? null : DateTime.parse(json['priceDate'] as String),
        priceSource: json['priceSource'] as String?,
        normCost: _dn(json['normCost']),
        issuedCost: _dn(json['issuedCost']),
        originalName: json['originalName'] as String?,
      );
}
