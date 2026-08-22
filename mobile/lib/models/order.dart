const List<String> orderStatuses = [
  'NEW',
  'CONFIRMED',
  'IN_PRODUCTION',
  'READY',
  'DELIVERED',
  'CANCELLED',
];

const Map<String, String> orderStatusLabels = {
  'NEW': 'Новый',
  'CONFIRMED': 'Подтверждён',
  'IN_PRODUCTION': 'В производстве',
  'READY': 'Готов',
  'DELIVERED': 'Выдан',
  'CANCELLED': 'Отменён',
};

class OrderItem {
  final int id;
  final int productId;
  final String productName;
  final double quantity;
  final double price;

  OrderItem({
    required this.id,
    required this.productId,
    required this.productName,
    required this.quantity,
    required this.price,
  });

  factory OrderItem.fromJson(Map<String, dynamic> json) => OrderItem(
        id: json['id'] as int,
        productId: json['productId'] as int,
        productName: json['productName'] as String,
        quantity: (json['quantity'] as num).toDouble(),
        price: (json['price'] as num).toDouble(),
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
        completedAt: json['completedAt'] != null ? DateTime.parse(json['completedAt'] as String) : null,
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

  OrderSummary({
    required this.id,
    required this.clientId,
    required this.clientName,
    required this.status,
    required this.statusLabel,
    required this.createdAt,
    this.dueDate,
    required this.totalPrice,
  });

  factory OrderSummary.fromJson(Map<String, dynamic> json) => OrderSummary(
        id: json['id'] as int,
        clientId: json['clientId'] as int,
        clientName: json['clientName'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        dueDate: json['dueDate'] != null ? DateTime.parse(json['dueDate'] as String) : null,
        totalPrice: (json['totalPrice'] as num).toDouble(),
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
  final List<OrderItem> items;
  final List<ProductionTask> productionTasks;

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
    required this.items,
    required this.productionTasks,
  });

  factory Order.fromJson(Map<String, dynamic> json) => Order(
        id: json['id'] as int,
        clientId: json['clientId'] as int,
        clientName: json['clientName'] as String,
        status: json['status'] as String,
        statusLabel: json['statusLabel'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
        dueDate: json['dueDate'] != null ? DateTime.parse(json['dueDate'] as String) : null,
        notes: json['notes'] as String?,
        totalPrice: (json['totalPrice'] as num).toDouble(),
        items: (json['items'] as List).map((e) => OrderItem.fromJson(e as Map<String, dynamic>)).toList(),
        productionTasks: (json['productionTasks'] as List)
            .map((e) => ProductionTask.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
