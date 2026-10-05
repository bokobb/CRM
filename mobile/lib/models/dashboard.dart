import 'ingredient.dart';
import 'notification.dart';
import 'order.dart';
import 'production_request.dart';

class Dashboard {
  final DateTime from;
  final DateTime to;
  final int newOrders;
  final int readyOrders;
  /// Фактическая продажа — отгружено минус возвраты за период.
  final double todaySalesTotal;
  final double grossSalesTotal;
  final double returnsTotal;
  final List<Ingredient> lowStock;
  final List<OrderNotificationGroup> orderNotifications;
  final List<AppNotification> generalNotifications;

  /// Оклады не в себестоимости за период: продажи (водители, грузчики, склад ГП) и администрация.
  /// null — роль не видит зарплату.
  final double? salesSalary;
  final double? adminSalary;

  Dashboard({
    required this.from,
    required this.to,
    required this.newOrders,
    required this.readyOrders,
    required this.todaySalesTotal,
    this.grossSalesTotal = 0,
    this.returnsTotal = 0,
    required this.lowStock,
    required this.orderNotifications,
    required this.generalNotifications,
    this.salesSalary,
    this.adminSalary,
  });

  factory Dashboard.fromJson(Map<String, dynamic> json) => Dashboard(
    from: DateTime.parse(json['from'] as String),
    to: DateTime.parse(json['to'] as String),
    newOrders: json['newOrders'] as int,
    readyOrders: json['readyOrders'] as int,
    todaySalesTotal: (json['todaySalesTotal'] as num).toDouble(),
    grossSalesTotal: (json['grossSalesTotal'] as num?)?.toDouble() ?? 0,
    returnsTotal: (json['returnsTotal'] as num?)?.toDouble() ?? 0,
    lowStock: (json['lowStock'] as List)
        .map((e) => Ingredient.fromJson(e as Map<String, dynamic>))
        .toList(),
    orderNotifications: (json['orderNotifications'] as List)
        .map((e) => OrderNotificationGroup.fromJson(e as Map<String, dynamic>))
        .toList(),
    generalNotifications: (json['generalNotifications'] as List)
        .map((e) => AppNotification.fromJson(e as Map<String, dynamic>))
        .toList(),
    salesSalary: ((json['salaryCosts'] as Map<String, dynamic>?)?['sales'] as num?)?.toDouble(),
    adminSalary: ((json['salaryCosts'] as Map<String, dynamic>?)?['admin'] as num?)?.toDouble(),
  );
}

/// Дашборд для роли WAREHOUSE_RAW — про заявки на сырьё (производство), не про заказы клиентов.
class ProductionDashboard {
  final DateTime from;
  final DateTime to;
  final int pending;
  final int awaitingReceipt;
  final int createdInRange;
  final int collectedInRange;
  final List<Ingredient> lowStock;
  final List<ProductionRequestSummary> pendingRequests;

  ProductionDashboard({
    required this.from,
    required this.to,
    required this.pending,
    required this.awaitingReceipt,
    required this.createdInRange,
    required this.collectedInRange,
    required this.lowStock,
    required this.pendingRequests,
  });

  factory ProductionDashboard.fromJson(Map<String, dynamic> json) => ProductionDashboard(
    from: DateTime.parse(json['from'] as String),
    to: DateTime.parse(json['to'] as String),
    pending: json['pending'] as int,
    awaitingReceipt: json['awaitingReceipt'] as int,
    createdInRange: json['createdInRange'] as int,
    collectedInRange: json['collectedInRange'] as int,
    lowStock: (json['lowStock'] as List)
        .map((e) => Ingredient.fromJson(e as Map<String, dynamic>))
        .toList(),
    pendingRequests: (json['pendingRequests'] as List)
        .map((e) => ProductionRequestSummary.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}

/// Дашборд склада ГП (WAREHOUSE_FG): сборка и отгрузка заказов, приём из цеха, остатки ГП.
class WarehouseDashboard {
  final DateTime from;
  final DateTime to;
  final int toAssemble;
  final int readyToShip;
  final int shippedInRange;
  final int awaitingReceipt;
  final List<StockLine> lowStock;
  final List<OrderSummary> activeOrders;

  WarehouseDashboard({
    required this.from,
    required this.to,
    required this.toAssemble,
    required this.readyToShip,
    required this.shippedInRange,
    required this.awaitingReceipt,
    required this.lowStock,
    required this.activeOrders,
  });

  factory WarehouseDashboard.fromJson(Map<String, dynamic> json) => WarehouseDashboard(
    from: DateTime.parse(json['from'] as String),
    to: DateTime.parse(json['to'] as String),
    toAssemble: json['toAssemble'] as int,
    readyToShip: json['readyToShip'] as int,
    shippedInRange: json['shippedInRange'] as int,
    awaitingReceipt: json['awaitingReceipt'] as int,
    lowStock: (json['lowStock'] as List)
        .map((e) => StockLine.fromJson(e as Map<String, dynamic>))
        .toList(),
    activeOrders: (json['activeOrders'] as List)
        .map((e) => OrderSummary.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}

/// Остаток готовой продукции (порога нет — только количество).
class StockLine {
  final int id;
  final String name;
  final String unit;
  final double quantityInStock;

  StockLine({required this.id, required this.name, required this.unit, required this.quantityInStock});

  factory StockLine.fromJson(Map<String, dynamic> json) => StockLine(
    id: json['id'] as int,
    name: json['name'] as String,
    unit: (json['unit'] as String?) ?? 'шт',
    quantityInStock: (json['quantityInStock'] as num).toDouble(),
  );
}
