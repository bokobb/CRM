class ProductionRequestSummary {
  final int id;
  final String title;
  final List<String> productNames;
  final double totalBatches;
  final String status;
  final String statusLabel;
  final String createdBy;
  final DateTime createdAt;

  ProductionRequestSummary({
    required this.id,
    required this.title,
    required this.productNames,
    required this.totalBatches,
    required this.status,
    required this.statusLabel,
    required this.createdBy,
    required this.createdAt,
  });

  factory ProductionRequestSummary.fromJson(Map<String, dynamic> json) => ProductionRequestSummary(
        id: json['id'] as int,
        title: json['title'] as String,
        productNames: (json['productNames'] as List).map((e) => e as String).toList(),
        totalBatches: (json['totalBatches'] as num).toDouble(),
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

class ProductionRequestItem {
  final int id;
  final int ingredientId;
  final String ingredientName;
  final String ingredientUnit;
  final double requiredQuantity;
  final double availableQuantity;
  final String? originalIngredientName;

  /// Сырья на складе меньше, чем нужно на эту заявку — показывать красным (см.
  /// production_request_detail_screen.dart), не дожидаясь ошибки от сервера при сборке.
  bool get isInsufficient => availableQuantity < requiredQuantity;

  ProductionRequestItem({
    required this.id,
    required this.ingredientId,
    required this.ingredientName,
    required this.ingredientUnit,
    required this.requiredQuantity,
    required this.availableQuantity,
    this.originalIngredientName,
  });

  factory ProductionRequestItem.fromJson(Map<String, dynamic> json) => ProductionRequestItem(
        id: json['id'] as int,
        ingredientId: json['ingredientId'] as int,
        ingredientName: json['ingredientName'] as String,
        ingredientUnit: json['ingredientUnit'] as String,
        requiredQuantity: (json['requiredQuantity'] as num).toDouble(),
        availableQuantity: (json['availableQuantity'] as num).toDouble(),
        originalIngredientName: json['originalIngredientName'] as String?,
      );
}

/// Одна позиция заявки: товар + сколько замесов — оприходуется независимо от остальных строк.
class ProductionRequestLine {
  final int id;
  final int productId;
  final String productName;
  final String? productUnit;
  final double batches;
  final double? plannedQuantity;
  final String? receivedBy;
  final DateTime? receivedAt;
  final double? receivedQuantity;

  bool get isReceived => receivedQuantity != null;

  ProductionRequestLine({
    required this.id,
    required this.productId,
    required this.productName,
    this.productUnit,
    required this.batches,
    this.plannedQuantity,
    this.receivedBy,
    this.receivedAt,
    this.receivedQuantity,
  });

  factory ProductionRequestLine.fromJson(Map<String, dynamic> json) => ProductionRequestLine(
        id: json['id'] as int,
        productId: json['productId'] as int,
        productName: json['productName'] as String,
        productUnit: json['productUnit'] as String?,
        batches: (json['batches'] as num).toDouble(),
        plannedQuantity: json['plannedQuantity'] != null ? (json['plannedQuantity'] as num).toDouble() : null,
        receivedBy: json['receivedBy'] as String?,
        receivedAt: json['receivedAt'] != null ? DateTime.parse(json['receivedAt'] as String) : null,
        receivedQuantity: json['receivedQuantity'] != null ? (json['receivedQuantity'] as num).toDouble() : null,
      );
}

class ProductionRequest {
  final int id;
  final String title;
  final String status;
  final String statusLabel;
  final String createdBy;
  final DateTime createdAt;
  final String? collectedBy;
  final DateTime? collectedAt;
  final String? oneCReportNumber;
  final String? oneCRequisitionNumber;
  final String? oneCSyncError;
  final List<ProductionRequestLine> lines;
  final List<ProductionRequestItem> items;

  double get totalBatches => lines.fold(0, (sum, l) => sum + l.batches);
  bool get allLinesReceived => lines.every((l) => l.isReceived);

  ProductionRequest({
    required this.id,
    required this.title,
    required this.status,
    required this.statusLabel,
    required this.createdBy,
    required this.createdAt,
    this.collectedBy,
    this.collectedAt,
    this.oneCReportNumber,
    this.oneCRequisitionNumber,
    this.oneCSyncError,
    required this.lines,
    required this.items,
  });

  factory ProductionRequest.fromJson(Map<String, dynamic> json) => ProductionRequest(
        id: json['id'] as int,
        title: json['title'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        collectedBy: json['collectedBy'] as String?,
        collectedAt: json['collectedAt'] != null ? DateTime.parse(json['collectedAt'] as String) : null,
        oneCReportNumber: json['oneCReportNumber'] as String?,
        oneCRequisitionNumber: json['oneCRequisitionNumber'] as String?,
        oneCSyncError: json['oneCSyncError'] as String?,
        lines: (json['lines'] as List).map((e) => ProductionRequestLine.fromJson(e as Map<String, dynamic>)).toList(),
        items: (json['items'] as List)
            .map((e) => ProductionRequestItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Товар с рецептурой — для выбора (с поиском) при создании заявки; yieldQuantity нужен, чтобы
/// пересчитывать замесы <-> штуки на лету без обращения к серверу.
class ProductionEligibleProduct {
  final int id;
  final String name;
  final String? unit;
  final String? group;
  final double yieldQuantity;

  ProductionEligibleProduct({required this.id, required this.name, this.unit, this.group, required this.yieldQuantity});

  factory ProductionEligibleProduct.fromJson(Map<String, dynamic> json) => ProductionEligibleProduct(
        id: json['id'] as int,
        name: json['name'] as String,
        unit: json['unit'] as String?,
        group: json['group'] as String?,
        yieldQuantity: (json['yieldQuantity'] as num).toDouble(),
      );
}

/// Одна строка дневного отчёта по оприходованию — сколько товара реально изготовили за день
/// (суммарно по всем заявкам, где были строки, оприходованные в этот день).
class DailyProductionReportItem {
  final String productName;
  final String unit;
  final double batches;
  final double norm;
  final double received;
  final double deviation;
  final int receivedCount;

  DailyProductionReportItem({
    required this.productName,
    required this.unit,
    required this.batches,
    required this.norm,
    required this.received,
    required this.deviation,
    required this.receivedCount,
  });

  factory DailyProductionReportItem.fromJson(Map<String, dynamic> json) => DailyProductionReportItem(
        productName: json['productName'] as String,
        unit: json['unit'] as String,
        batches: (json['batches'] as num).toDouble(),
        norm: (json['norm'] as num).toDouble(),
        received: (json['received'] as num).toDouble(),
        deviation: (json['deviation'] as num).toDouble(),
        receivedCount: json['receivedCount'] as int,
      );
}

class DailyProductionReport {
  final DateTime date;
  final List<DailyProductionReportItem> items;

  DailyProductionReport({required this.date, required this.items});

  factory DailyProductionReport.fromJson(Map<String, dynamic> json) => DailyProductionReport(
        date: DateTime.parse(json['date'] as String),
        items: (json['items'] as List)
            .map((e) => DailyProductionReportItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
