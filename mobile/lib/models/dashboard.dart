import 'ingredient.dart';
import 'notification.dart';
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
