// Себестоимость производства за день (экран «Производство») — заявки на сырьё × техкарты.
// См. backend ProductionCostDto.

double _d(dynamic v) => (v as num?)?.toDouble() ?? 0;
double? _dn(dynamic v) => (v as num?)?.toDouble();

class ProductionCost {
  final DateTime date;

  /// Сырьё всех заявок дня: норма по техкартам и фактически выданное.
  final double normCost;
  final double issuedCost;

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
  final List<ProductionCostLine> lines;

  ProductionCostRequest({
    required this.id,
    required this.title,
    required this.status,
    required this.statusLabel,
    required this.normCost,
    required this.issuedCost,
    required this.lines,
  });

  factory ProductionCostRequest.fromJson(Map<String, dynamic> json) => ProductionCostRequest(
        id: json['id'] as int,
        title: json['title'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        normCost: _d(json['normCost']),
        issuedCost: _d(json['issuedCost']),
        lines: [for (final l in json['lines'] as List) ProductionCostLine.fromJson(l as Map<String, dynamic>)],
      );
}

class ProductionCostLine {
  final int productId;
  final String name;
  final String? unit;
  final bool semiFinished;
  final double batches;

  /// Оприходовано, а пока не оприходовано — план ([received] = false).
  final double quantity;
  final bool received;
  final double normCost;

  /// Фактически выданное сырьё заявки, разнесённое по товарам пропорционально норме.
  final double cost;
  final double? unitCost;
  final double? salePrice;

  ProductionCostLine({
    required this.productId,
    required this.name,
    this.unit,
    required this.semiFinished,
    required this.batches,
    required this.quantity,
    required this.received,
    required this.normCost,
    required this.cost,
    this.unitCost,
    this.salePrice,
  });

  factory ProductionCostLine.fromJson(Map<String, dynamic> json) => ProductionCostLine(
        productId: json['productId'] as int,
        name: json['name'] as String,
        unit: json['unit'] as String?,
        semiFinished: json['semiFinished'] as bool? ?? false,
        batches: _d(json['batches']),
        quantity: _d(json['quantity']),
        received: json['received'] as bool? ?? false,
        normCost: _d(json['normCost']),
        cost: _d(json['cost']),
        unitCost: _dn(json['unitCost']),
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
