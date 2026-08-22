import 'ingredient.dart';
import 'notification.dart';

class Dashboard {
  final int newOrders;
  final int inProduction;
  final int readyOrders;
  final int pendingTasks;
  final List<Ingredient> lowStock;
  final List<AppNotification> recentNotifications;

  Dashboard({
    required this.newOrders,
    required this.inProduction,
    required this.readyOrders,
    required this.pendingTasks,
    required this.lowStock,
    required this.recentNotifications,
  });

  factory Dashboard.fromJson(Map<String, dynamic> json) => Dashboard(
        newOrders: json['newOrders'] as int,
        inProduction: json['inProduction'] as int,
        readyOrders: json['readyOrders'] as int,
        pendingTasks: json['pendingTasks'] as int,
        lowStock: (json['lowStock'] as List).map((e) => Ingredient.fromJson(e as Map<String, dynamic>)).toList(),
        recentNotifications: (json['recentNotifications'] as List)
            .map((e) => AppNotification.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
