import 'ingredient.dart';
import 'notification.dart';

class Dashboard {
  final DateTime from;
  final DateTime to;
  final int newOrders;
  final int readyOrders;
  final double todaySalesTotal;
  final List<Ingredient> lowStock;
  final List<OrderNotificationGroup> orderNotifications;
  final List<AppNotification> generalNotifications;

  Dashboard({
    required this.from,
    required this.to,
    required this.newOrders,
    required this.readyOrders,
    required this.todaySalesTotal,
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
