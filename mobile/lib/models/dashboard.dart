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

/// Дашборд «График + Требует внимания».
class DashboardInsights {
  final List<DaySales> days;
  final double weekTotal;
  final double prevWeekTotal;
  final List<DashboardAlert> alerts;

  DashboardInsights({required this.days, required this.weekTotal, required this.prevWeekTotal, required this.alerts});

  factory DashboardInsights.fromJson(Map<String, dynamic> json) => DashboardInsights(
    days: (json['days'] as List).map((e) => DaySales.fromJson(e as Map<String, dynamic>)).toList(),
    weekTotal: (json['weekTotal'] as num).toDouble(),
    prevWeekTotal: (json['prevWeekTotal'] as num).toDouble(),
    alerts: (json['alerts'] as List).map((e) => DashboardAlert.fromJson(e as Map<String, dynamic>)).toList(),
  );
}

class DaySales {
  final DateTime date;
  final double amount;

  DaySales({required this.date, required this.amount});

  factory DaySales.fromJson(Map<String, dynamic> json) =>
      DaySales(date: DateTime.parse(json['date'] as String), amount: (json['amount'] as num).toDouble());
}

class DashboardAlert {
  /// LOW_STOCK | OVERDUE_DEBT | STUCK_ORDER
  final String kind;
  final String title;
  final String meta;
  final int? orderId;

  DashboardAlert({required this.kind, required this.title, required this.meta, this.orderId});

  factory DashboardAlert.fromJson(Map<String, dynamic> json) => DashboardAlert(
    kind: json['kind'] as String,
    title: json['title'] as String,
    meta: json['meta'] as String,
    orderId: json['orderId'] as int?,
  );
}

/// Лента дня.
class DayFeed {
  final DateTime date;

  /// null — роль без денег (склады): «Продано» не показываем.
  final double? salesTotal;
  final int ordersCreated;
  final List<FeedEvent> events;

  DayFeed({required this.date, required this.salesTotal, required this.ordersCreated, required this.events});

  factory DayFeed.fromJson(Map<String, dynamic> json) => DayFeed(
    date: DateTime.parse(json['date'] as String),
    salesTotal: (json['salesTotal'] as num?)?.toDouble(),
    ordersCreated: json['ordersCreated'] as int,
    events: (json['events'] as List).map((e) => FeedEvent.fromJson(e as Map<String, dynamic>)).toList(),
  );
}

class FeedEvent {
  final DateTime time;

  /// ORDER_NEW | ORDER_SHIPPED | RETURN | REQUEST_NEW | REQUEST_COLLECTED
  final String kind;
  final String title;
  final String meta;
  final int? orderId;
  final int? requestId;

  FeedEvent({required this.time, required this.kind, required this.title, required this.meta, this.orderId, this.requestId});

  factory FeedEvent.fromJson(Map<String, dynamic> json) => FeedEvent(
    time: DateTime.parse(json['time'] as String),
    kind: json['kind'] as String,
    title: json['title'] as String,
    meta: json['meta'] as String,
    orderId: json['orderId'] as int?,
    requestId: json['requestId'] as int?,
  );
}

/// Дашборд «Сырьё · график».
class RawInsights {
  final List<RawDay> days;
  final double weekBatches;
  final double prevWeekBatches;
  final int pending;
  final int awaitingReceipt;
  final List<RawAlert> alerts;

  RawInsights({
    required this.days,
    required this.weekBatches,
    required this.prevWeekBatches,
    required this.pending,
    required this.awaitingReceipt,
    required this.alerts,
  });

  factory RawInsights.fromJson(Map<String, dynamic> json) => RawInsights(
    days: (json['days'] as List).map((e) => RawDay.fromJson(e as Map<String, dynamic>)).toList(),
    weekBatches: (json['weekBatches'] as num).toDouble(),
    prevWeekBatches: (json['prevWeekBatches'] as num).toDouble(),
    pending: json['pending'] as int,
    awaitingReceipt: json['awaitingReceipt'] as int,
    alerts: (json['alerts'] as List).map((e) => RawAlert.fromJson(e as Map<String, dynamic>)).toList(),
  );
}

class RawDay {
  final DateTime date;
  final double batches;
  final int requests;

  RawDay({required this.date, required this.batches, required this.requests});

  factory RawDay.fromJson(Map<String, dynamic> json) => RawDay(
    date: DateTime.parse(json['date'] as String),
    batches: (json['batches'] as num).toDouble(),
    requests: json['requests'] as int,
  );
}

class RawAlert {
  /// LOW_STOCK | STUCK_REQUEST | AWAITING_RECEIPT
  final String kind;
  final String title;
  final String meta;
  final int? requestId;

  RawAlert({required this.kind, required this.title, required this.meta, this.requestId});

  factory RawAlert.fromJson(Map<String, dynamic> json) => RawAlert(
    kind: json['kind'] as String,
    title: json['title'] as String,
    meta: json['meta'] as String,
    requestId: json['requestId'] as int?,
  );
}
