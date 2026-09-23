const List<String> orderStatuses = [
  'NEW',
  'CONFIRMED',
  'IN_PRODUCTION',
  'READY',
  'DELIVERED',
  'COMPLETED',
  'CANCELLED',
];

const Map<String, String> orderStatusLabels = {
  'NEW': 'В процессе сборки',
  'CONFIRMED': 'Подтверждён',
  'IN_PRODUCTION': 'В производстве',
  'READY': 'Готов к отгрузке',
  'DELIVERED': 'Отгружен',
  'COMPLETED': 'Завершён',
  'CANCELLED': 'Отменён',
};

class OrderItem {
  final int id;
  final int productId;
  final String productName;
  final String? productImageUrl;
  final String? productUnit;
  final double quantity;
  final double price;
  final double availableQuantity;

  OrderItem({
    required this.id,
    required this.productId,
    required this.productName,
    this.productImageUrl,
    this.productUnit,
    required this.quantity,
    required this.price,
    this.availableQuantity = 0,
  });

  /// Товара на складе меньше, чем заказано — строка красным в чек-листе сборки (см.
  /// production_request.dart / ProductionRequestItem.isInsufficient — тот же приём).
  bool get isInsufficient => availableQuantity < quantity;

  factory OrderItem.fromJson(Map<String, dynamic> json) => OrderItem(
    id: json['id'] as int,
    productId: json['productId'] as int,
    productName: json['productName'] as String,
    productImageUrl: json['productImageUrl'] as String?,
    productUnit: json['productUnit'] as String?,
    quantity: (json['quantity'] as num).toDouble(),
    price: (json['price'] as num).toDouble(),
    availableQuantity: (json['availableQuantity'] as num?)?.toDouble() ?? 0,
  );

  /// Копия с абсолютным productImageUrl — сервер отдаёт относительный путь для загруженных
  /// файлов, ApiClient превращает его в полный URL (см. ApiClient._withAbsoluteOrderImages).
  OrderItem withImageUrl(String url) => OrderItem(
    id: id,
    productId: productId,
    productName: productName,
    productImageUrl: url,
    productUnit: productUnit,
    quantity: quantity,
    price: price,
    availableQuantity: availableQuantity,
  );
}

class ProductionTask {
  final int id;
  final int orderId;
  final int productId;
  final String productName;
  final double quantity;
  final String status;
  final String statusLabel;
  final String? assignedTo;
  final DateTime createdAt;
  final DateTime? completedAt;

  ProductionTask({
    required this.id,
    required this.orderId,
    required this.productId,
    required this.productName,
    required this.quantity,
    required this.status,
    required this.statusLabel,
    this.assignedTo,
    required this.createdAt,
    this.completedAt,
  });

  factory ProductionTask.fromJson(Map<String, dynamic> json) => ProductionTask(
    id: json['id'] as int,
    orderId: json['orderId'] as int,
    productId: json['productId'] as int,
    productName: json['productName'] as String,
    quantity: (json['quantity'] as num).toDouble(),
    status: json['status'] as String,
    statusLabel: json['statusLabel'] as String,
    assignedTo: json['assignedTo'] as String?,
    createdAt: DateTime.parse(json['createdAt'] as String),
    completedAt: json['completedAt'] != null
        ? DateTime.parse(json['completedAt'] as String)
        : null,
  );
}

class OrderSummary {
  final int id;
  final int clientId;
  final String clientName;
  final String status;
  final String statusLabel;
  final DateTime createdAt;
  final DateTime? dueDate;
  final double totalPrice;
  final String? vehicleNumber;
  final DateTime? shippedAt;
  final DateTime? debtDueDate;
  final bool debtPaid;
  final bool fromWhatsapp;

  /// Сумма закреплённых возвратов (см. ProductReturn) и заказ за их вычетом.
  final double returnTotal;
  final double netTotal;
  final int returnsCount;

  bool get hasUnpaidDebt => debtDueDate != null && !debtPaid;

  OrderSummary({
    required this.id,
    required this.clientId,
    required this.clientName,
    required this.status,
    required this.statusLabel,
    required this.createdAt,
    this.dueDate,
    required this.totalPrice,
    this.vehicleNumber,
    this.shippedAt,
    this.debtDueDate,
    this.debtPaid = false,
    this.fromWhatsapp = false,
    this.returnTotal = 0,
    required this.netTotal,
    this.returnsCount = 0,
  });

  factory OrderSummary.fromJson(Map<String, dynamic> json) => OrderSummary(
    id: json['id'] as int,
    clientId: json['clientId'] as int,
    clientName: json['clientName'] as String,
    status: json['status'] as String,
    statusLabel: json['statusLabel'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    dueDate: json['dueDate'] != null
        ? DateTime.parse(json['dueDate'] as String)
        : null,
    totalPrice: (json['totalPrice'] as num).toDouble(),
    vehicleNumber: json['vehicleNumber'] as String?,
    shippedAt: json['shippedAt'] != null
        ? DateTime.parse(json['shippedAt'] as String)
        : null,
    debtDueDate: json['debtDueDate'] != null
        ? DateTime.parse(json['debtDueDate'] as String)
        : null,
    debtPaid: json['debtPaid'] as bool? ?? false,
    fromWhatsapp: json['fromWhatsapp'] as bool? ?? false,
    returnTotal: (json['returnTotal'] as num?)?.toDouble() ?? 0,
    netTotal: (json['netTotal'] as num? ?? json['totalPrice'] as num).toDouble(),
    returnsCount: json['returnsCount'] as int? ?? 0,
  );
}

class Order {
  final int id;
  final int clientId;
  final String clientName;
  final String status;
  final String statusLabel;
  final DateTime createdAt;
  final DateTime? dueDate;
  final String? notes;
  final double totalPrice;
  final String? vehicleNumber;
  final DateTime? shippedAt;
  final String? shippedByName;
  final DateTime? debtDueDate;
  final bool debtPaid;
  final bool fromWhatsapp;
  final List<OrderItem> items;
  final List<ProductionTask> productionTasks;
  final double returnTotal;
  final double netTotal;
  final int returnsCount;

  bool get hasUnpaidDebt => debtDueDate != null && !debtPaid;

  Order({
    required this.id,
    required this.clientId,
    required this.clientName,
    required this.status,
    required this.statusLabel,
    required this.createdAt,
    this.dueDate,
    this.notes,
    required this.totalPrice,
    this.vehicleNumber,
    this.shippedAt,
    this.shippedByName,
    this.debtDueDate,
    this.debtPaid = false,
    this.fromWhatsapp = false,
    required this.items,
    required this.productionTasks,
    this.returnTotal = 0,
    required this.netTotal,
    this.returnsCount = 0,
  });

  factory Order.fromJson(Map<String, dynamic> json) => Order(
    id: json['id'] as int,
    clientId: json['clientId'] as int,
    clientName: json['clientName'] as String,
    status: json['status'] as String,
    statusLabel: json['statusLabel'] as String,
    createdAt: DateTime.parse(json['createdAt'] as String),
    dueDate: json['dueDate'] != null
        ? DateTime.parse(json['dueDate'] as String)
        : null,
    notes: json['notes'] as String?,
    totalPrice: (json['totalPrice'] as num).toDouble(),
    vehicleNumber: json['vehicleNumber'] as String?,
    shippedAt: json['shippedAt'] != null
        ? DateTime.parse(json['shippedAt'] as String)
        : null,
    shippedByName: json['shippedByName'] as String?,
    debtDueDate: json['debtDueDate'] != null
        ? DateTime.parse(json['debtDueDate'] as String)
        : null,
    debtPaid: json['debtPaid'] as bool? ?? false,
    fromWhatsapp: json['fromWhatsapp'] as bool? ?? false,
    items: (json['items'] as List)
        .map((e) => OrderItem.fromJson(e as Map<String, dynamic>))
        .toList(),
    productionTasks: (json['productionTasks'] as List)
        .map((e) => ProductionTask.fromJson(e as Map<String, dynamic>))
        .toList(),
    returnTotal: (json['returnTotal'] as num?)?.toDouble() ?? 0,
    netTotal: (json['netTotal'] as num? ?? json['totalPrice'] as num).toDouble(),
    returnsCount: json['returnsCount'] as int? ?? 0,
  );

  /// Копия с другим списком позиций — см. ApiClient._withAbsoluteOrderImages.
  Order withItems(List<OrderItem> items) => Order(
    id: id,
    clientId: clientId,
    clientName: clientName,
    status: status,
    statusLabel: statusLabel,
    createdAt: createdAt,
    dueDate: dueDate,
    notes: notes,
    totalPrice: totalPrice,
    vehicleNumber: vehicleNumber,
    shippedAt: shippedAt,
    shippedByName: shippedByName,
    debtDueDate: debtDueDate,
    debtPaid: debtPaid,
    fromWhatsapp: fromWhatsapp,
    items: items,
    productionTasks: productionTasks,
    returnTotal: returnTotal,
    netTotal: netTotal,
    returnsCount: returnsCount,
  );
}
