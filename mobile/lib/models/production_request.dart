class ProductionRequestSummary {
  final int id;
  final String title;
  final List<String> productNames;
  final double totalBatches;
  final String status;
  final String statusLabel;
  final String createdBy;
  final DateTime createdAt;

  /// Дата документа для 1С, выбранная при создании (см. ProductionRequest.documentDate на
  /// бэкенде) — то, что реально попадает в 1С, в отличие от createdAt (когда запись создана
  /// в CRM — это может быть другой день, если заявку оформили "на завтра"/"задним числом").
  final DateTime documentDate;

  /// Хотя бы одна строка оприходована меньше нормы — карточка в списке красная (см.
  /// production_requests_screen.dart).
  final bool hasShortfall;

  /// "Пряник — 10 шт, Торт — 5 шт" — что именно не дооприходовано; null, если хватает.
  final String? shortfallText;

  /// Требование-накладная проведена в 1С кнопкой "Завершить" — карточка в списке серая для
  /// WAREHOUSE_RAW, см. production_requests_screen.dart.
  final bool requisitionCompleted;

  /// Состав менялся после выдачи работникам, а разницу ещё не выдали/не вернули — подсказка
  /// в списке, само окно с разницей — по долгому нажатию (production_composition_sheet.dart).
  final bool compositionDiffPending;

  /// «Медовик 3 → 4» — замесы изменены после выдачи; null — нет.
  final String? compositionDiffBatches;

  ProductionRequestSummary({
    required this.id,
    required this.title,
    required this.productNames,
    required this.totalBatches,
    required this.status,
    required this.statusLabel,
    required this.createdBy,
    required this.createdAt,
    required this.documentDate,
    this.hasShortfall = false,
    this.shortfallText,
    this.requisitionCompleted = false,
    this.compositionDiffPending = false,
    this.compositionDiffBatches,
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
        documentDate: DateTime.parse(json['documentDate'] as String),
        hasShortfall: json['hasShortfall'] as bool? ?? false,
        shortfallText: json['shortfallText'] as String?,
        requisitionCompleted: json['requisitionCompleted'] as bool? ?? false,
        compositionDiffPending: json['compositionDiffPending'] as bool? ?? false,
        compositionDiffBatches: json['compositionDiffBatches'] as String?,
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

  /// Красным в UI вместо зелёного — построчная норма, НО если товар участвует в сверке по общей
  /// массе (см. ProductionMassSummary) и по массе как раз хватает — построчная "недостаточно"
  /// гасится (ложная тревога из-за перепутанных пропорций между фасовками при нарезке).
  /// Считается на бэкенде (см. ProductionRequestDto.Line.from), не дублируем логику на клиенте.
  final bool insufficient;

  bool get isReceived => receivedQuantity != null;

  /// Сколько не хватает до нормы — 0, если хватает/план не задан/ещё не оприходовано. Безопасно
  /// опирается на plannedQuantity/receivedQuantity: [insufficient] может быть true, только если
  /// строка сама по себе не дотянула до своей нормы (см. ProductionRequestDto.Line.from на
  /// бэкенде — сверка по массе только гасит false-positive, но не создаёт его на пустом месте).
  double get shortfall => insufficient && plannedQuantity != null && receivedQuantity != null
      ? plannedQuantity! - receivedQuantity!
      : 0;

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
    required this.insufficient,
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
        insufficient: json['insufficient'] as bool,
      );
}

/// Сверка нормы по общей массе вместо штук — для товаров вроде Чак-чака, где несколько фасовок
/// режутся из одного замеса (см. Product.netWeightGrams/ProductionRequestService.massSummary на
/// бэкенде): если у товара задан вес 1 шт, штуки в позиции переводятся в массу и норма/факт
/// сравниваются суммарно по группе, а не построчно — так рабочие могут случайно перепутать
/// пропорции между фасовками при нарезке, не показывая ложную недостачу по одной из них.
class ProductionMassSummary {
  final double plannedKg;
  final double actualKg;
  final double deviationKg;
  final bool insufficient;

  /// Все позиции с заданным весом уже оприходованы — окончательный вывод "хватает/не хватает"
  /// показывается только тогда, иначе факт ещё неполный.
  final bool allWeightedReceived;

  /// Название самого крупного по весу варианта в группе (например, "Чак Чак Б") — единица
  /// пересчёта отклонения массы в штуки, см. [deviationPiecesAbs].
  final String referenceProductName;

  /// Модуль отклонения, пересчитанный в штуки [referenceProductName] — "не хватает N шт.
  /// Чак Чак Б", а не просто "N кг сырья". Условная величина для ориентира, не точный расчёт.
  final double deviationPiecesAbs;

  ProductionMassSummary({
    required this.plannedKg,
    required this.actualKg,
    required this.deviationKg,
    required this.insufficient,
    required this.allWeightedReceived,
    required this.referenceProductName,
    required this.deviationPiecesAbs,
  });

  factory ProductionMassSummary.fromJson(Map<String, dynamic> json) => ProductionMassSummary(
        plannedKg: (json['plannedKg'] as num).toDouble(),
        actualKg: (json['actualKg'] as num).toDouble(),
        deviationKg: (json['deviationKg'] as num).toDouble(),
        insufficient: json['insufficient'] as bool,
        allWeightedReceived: json['allWeightedReceived'] as bool,
        referenceProductName: json['referenceProductName'] as String,
        deviationPiecesAbs: (json['deviationPiecesAbs'] as num).toDouble(),
      );
}

class ProductionRequest {
  final int id;
  final String title;
  final String status;
  final String statusLabel;
  final String createdBy;
  final DateTime createdAt;

  /// Дата документа для 1С (см. ProductionRequest.documentDate на бэкенде) — по умолчанию
  /// сегодня, выбирается при создании/редактировании заявки; время в 1С всегда фиксированное 06:00.
  final DateTime documentDate;
  final String? collectedBy;
  final DateTime? collectedAt;
  final String? oneCReportNumber;
  final String? oneCRequisitionNumber;
  final String? oneCSyncError;

  /// Кнопка "Завершить" нажата и требование-накладная проведена в 1С — редактировать/удалять/
  /// добавлять позиции нельзя, пока не нажали "Отменить" (см.
  /// production_request_detail_screen.dart).
  final bool requisitionCompleted;
  final List<ProductionRequestLine> lines;
  final List<ProductionRequestItem> items;
  final ProductionMassSummary? massSummary;

  double get totalBatches => lines.fold(0, (sum, l) => sum + l.batches);
  bool get allLinesReceived => lines.every((l) => l.isReceived);

  ProductionRequest({
    required this.id,
    required this.title,
    required this.status,
    required this.statusLabel,
    required this.createdBy,
    required this.createdAt,
    required this.documentDate,
    this.collectedBy,
    this.collectedAt,
    this.oneCReportNumber,
    this.oneCRequisitionNumber,
    this.oneCSyncError,
    this.requisitionCompleted = false,
    required this.lines,
    required this.items,
    this.massSummary,
  });

  factory ProductionRequest.fromJson(Map<String, dynamic> json) => ProductionRequest(
        id: json['id'] as int,
        title: json['title'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdBy: json['createdBy'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        documentDate: DateTime.parse(json['documentDate'] as String),
        collectedBy: json['collectedBy'] as String?,
        collectedAt: json['collectedAt'] != null ? DateTime.parse(json['collectedAt'] as String) : null,
        oneCReportNumber: json['oneCReportNumber'] as String?,
        oneCRequisitionNumber: json['oneCRequisitionNumber'] as String?,
        oneCSyncError: json['oneCSyncError'] as String?,
        requisitionCompleted: json['requisitionCompleted'] as bool? ?? false,
        lines: (json['lines'] as List).map((e) => ProductionRequestLine.fromJson(e as Map<String, dynamic>)).toList(),
        items: (json['items'] as List)
            .map((e) => ProductionRequestItem.fromJson(e as Map<String, dynamic>))
            .toList(),
        massSummary: json['massSummary'] != null
            ? ProductionMassSummary.fromJson(json['massSummary'] as Map<String, dynamic>)
            : null,
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

/// Выдано работникам против текущего состава заявки — см. ProductionCompositionDiffDto.
class ProductionCompositionDiff {
  final int requestId;
  final String title;
  final String statusLabel;

  /// null — сырьё ещё не выдавали, разницы нет.
  final DateTime? issuedAt;
  final String? issuedBy;
  final List<CompositionDiffRow> rows;
  final List<CompositionRevision> history;

  /// Вторая таблица окна: сырьё против нормы по рецептуре.
  final List<CompositionNormRow> normRows;

  /// Замесы, изменённые после выдачи.
  final List<CompositionBatchChange> batchChanges;

  /// Текущие замесы по товарам.
  final List<CompositionLine> lines;

  ProductionCompositionDiff({
    required this.requestId,
    required this.title,
    required this.statusLabel,
    this.issuedAt,
    this.issuedBy,
    required this.rows,
    required this.history,
    this.normRows = const [],
    this.batchChanges = const [],
    this.lines = const [],
  });

  bool get hasDiff => rows.any((r) => r.diff != 0);

  factory ProductionCompositionDiff.fromJson(Map<String, dynamic> json) => ProductionCompositionDiff(
        requestId: json['requestId'] as int,
        title: json['title'] as String,
        statusLabel: json['statusLabel'] as String,
        issuedAt: json['issuedAt'] == null ? null : DateTime.parse(json['issuedAt'] as String),
        issuedBy: json['issuedBy'] as String?,
        rows: (json['rows'] as List).map((e) => CompositionDiffRow.fromJson(e)).toList(),
        history: (json['history'] as List).map((e) => CompositionRevision.fromJson(e)).toList(),
        normRows: (json['normRows'] as List? ?? []).map((e) => CompositionNormRow.fromJson(e)).toList(),
        batchChanges: (json['batchChanges'] as List? ?? [])
            .map((e) => CompositionBatchChange(
                  productName: e['productName'] as String,
                  issued: (e['issued'] as num).toDouble(),
                  current: (e['current'] as num).toDouble(),
                ))
            .toList(),
        lines: (json['lines'] as List? ?? []).map((e) => CompositionLine.fromJson(e)).toList(),
      );
}

/// Сырьё заявки против нормы по рецептуре (рецептура × замесы).
class CompositionNormRow {
  final String ingredientName;
  final String? unit;
  final double norm;
  final double current;

  /// current − norm: плюс — сверх нормы, минус — меньше нормы.
  final double diff;

  /// «вместо …», «нет в рецептуре», «удалено из заявки».
  final String? note;

  CompositionNormRow({
    required this.ingredientName,
    this.unit,
    required this.norm,
    required this.current,
    required this.diff,
    this.note,
  });

  factory CompositionNormRow.fromJson(Map<String, dynamic> json) => CompositionNormRow(
        ingredientName: json['ingredientName'] as String,
        unit: json['unit'] as String?,
        norm: (json['norm'] as num).toDouble(),
        current: (json['current'] as num).toDouble(),
        diff: (json['diff'] as num).toDouble(),
        note: json['note'] as String?,
      );
}

class CompositionBatchChange {
  final String productName;
  final double issued;
  final double current;

  CompositionBatchChange({required this.productName, required this.issued, required this.current});
}

class CompositionLine {
  final String productName;
  final double batches;

  CompositionLine({required this.productName, required this.batches});

  factory CompositionLine.fromJson(Map<String, dynamic> json) => CompositionLine(
        productName: json['productName'] as String,
        batches: (json['batches'] as num).toDouble(),
      );
}

class CompositionDiffRow {
  final String ingredientName;
  final String? unit;
  final double issued;
  final double current;

  /// current − issued: плюс — не хватает, довыдать; минус — лишнее, вернуть.
  final double diff;

  CompositionDiffRow({
    required this.ingredientName,
    this.unit,
    required this.issued,
    required this.current,
    required this.diff,
  });

  factory CompositionDiffRow.fromJson(Map<String, dynamic> json) => CompositionDiffRow(
        ingredientName: json['ingredientName'] as String,
        unit: json['unit'] as String?,
        issued: (json['issued'] as num).toDouble(),
        current: (json['current'] as num).toDouble(),
        diff: (json['diff'] as num).toDouble(),
      );
}

class CompositionRevision {
  final DateTime createdAt;
  final String createdBy;
  final String? note;

  /// Этот состав выдан на руки (сборка или «Рассчитались») — от последнего такого считается разница.
  final bool issued;
  final List<CompositionRevisionItem> items;

  /// Замесы на момент снимка; у снимков до 05.10 пусто.
  final List<CompositionLine> lines;

  CompositionRevision({
    required this.createdAt,
    required this.createdBy,
    this.note,
    required this.issued,
    required this.items,
    this.lines = const [],
  });

  factory CompositionRevision.fromJson(Map<String, dynamic> json) => CompositionRevision(
        createdAt: DateTime.parse(json['createdAt'] as String),
        createdBy: json['createdBy'] as String,
        note: json['note'] as String?,
        issued: json['issued'] as bool,
        items: (json['items'] as List)
            .map((e) => CompositionRevisionItem(
                  ingredientName: e['ingredientName'] as String,
                  unit: e['unit'] as String?,
                  quantity: (e['quantity'] as num).toDouble(),
                ))
            .toList(),
        lines: (json['lines'] as List? ?? []).map((e) => CompositionLine.fromJson(e)).toList(),
      );
}

class CompositionRevisionItem {
  final String ingredientName;
  final String? unit;
  final double quantity;

  CompositionRevisionItem({required this.ingredientName, this.unit, required this.quantity});
}
