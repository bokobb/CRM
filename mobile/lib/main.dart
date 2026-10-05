import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'firebase_web_options.dart';
import 'models/whatsapp_inbox.dart';
import 'navigation.dart';
import 'screens/client_detail_screen.dart';
import 'screens/client_form_screen.dart';
import 'screens/client_prices_screen.dart';
import 'screens/clients_screen.dart';
import 'screens/company_profile_screen.dart';
import 'screens/counterparties_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/debts_screen.dart';
import 'screens/ingredient_form_screen.dart';
import 'screens/ingredient_warehouse_screen.dart';
import 'screens/ingredients_screen.dart';
import 'screens/login_screen.dart';
import 'screens/notifications_screen.dart';
import 'screens/order_detail_screen.dart';
import 'screens/order_form_screen.dart';
import 'screens/orders_screen.dart';
import 'screens/prices_screen.dart';
import 'screens/product_form_screen.dart';
import 'screens/product_stock_screen.dart';
import 'screens/products_screen.dart';
import 'screens/reports_screen.dart';
import 'screens/production_report_screen.dart';
import 'screens/production_request_detail_screen.dart';
import 'screens/production_request_form_screen.dart';
import 'screens/production_requests_screen.dart';
import 'screens/production_screen.dart';
import 'screens/tech_cards_screen.dart';
import 'screens/employees_screen.dart';
import 'screens/purchases_screen.dart';
import 'screens/returns_screen.dart';
import 'screens/recipe_screen.dart';
import 'screens/recipes_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/stock_movement_screen.dart';
import 'screens/user_form_screen.dart';
import 'screens/users_screen.dart';
import 'screens/warehouse_screen.dart';
import 'screens/whatsapp_inbox_resolve_screen.dart';
import 'screens/whatsapp_inbox_screen.dart';
import 'screens/whatsapp_session_screen.dart';
import 'services/api_client.dart';
import 'services/push_service.dart';
import 'theme/app_layout.dart';
import 'theme/app_theme.dart';

const accent = Color(0xFFB8324A);
const accentDark = Color(0xFF8F2438);
const gold = Color(0xFFD9A73F);
const ink = Color(0xFF2B1F1A);
const bg = Color(0xFFF7F3EC);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('ru');
  await ThemeController.load();
  await LayoutController.load();
  final firebaseReady = await _initFirebase();
  await ApiClient.loadSession();
  if (firebaseReady) {
    PushService.init();
    final role = ApiClient.currentSession?.role;
    if (role != null) await PushService.subscribeToRole(role);
  }
  runApp(const CrmApp());
}

/// Push-уведомления не обязательны для работы приложения: пока google-services.json не
/// подключён (см. android/app/build.gradle.kts), Firebase просто не инициализируется —
/// остальной функционал работает как обычно.
Future<bool> _initFirebase() async {
  try {
    await Firebase.initializeApp(options: kIsWeb ? firebaseWebOptions : null);
    return true;
  } catch (e) {
    debugPrint('Firebase не подключён, push-уведомления отключены: $e');
    return false;
  }
}

class CrmApp extends StatelessWidget {
  const CrmApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: ThemeController.listenable,
      builder: (context, _) => MaterialApp(
        title: 'Юг Сладости',
        debugShowCheckedModeBanner: false,
        navigatorKey: navigatorKey,
        theme: buildAppTheme(ThemeController.day.value),
        darkTheme: buildAppTheme(ThemeController.night.value),
        themeMode: ThemeController.mode.value,
        // Тема может увеличивать текст (см. AppPalette.textScale) — поверх настройки телефона.
        builder: (context, child) {
          final scale = context.pal.textScale;
          if (scale == 1.0) return child!;
          final mq = MediaQuery.of(context);
          return MediaQuery(
            data: mq.copyWith(textScaler: TextScaler.linear(mq.textScaler.scale(1.0) * scale)),
            child: child!,
          );
        },
        initialRoute: ApiClient.currentSession == null ? '/login' : homeRouteForRole(ApiClient.currentSession!.role),
        onGenerateRoute: (settings) {
          switch (settings.name) {
            case '/login':
              return MaterialPageRoute(builder: (_) => const LoginScreen(), settings: settings);
            case '/':
              return MaterialPageRoute(builder: (_) => const DashboardScreen(), settings: settings);
            case '/clients':
              return MaterialPageRoute(builder: (_) => const ClientsScreen(), settings: settings);
            case '/clients/new':
              return MaterialPageRoute(builder: (_) => const ClientFormScreen(), settings: settings);
            case '/products':
              return MaterialPageRoute(builder: (_) => const ProductsScreen(), settings: settings);
            case '/products/new':
              return MaterialPageRoute(builder: (_) => const ProductFormScreen(), settings: settings);
            case '/recipes':
              return MaterialPageRoute(builder: (_) => const RecipesScreen(), settings: settings);
            case '/ingredients':
              return MaterialPageRoute(builder: (_) => const IngredientsScreen(), settings: settings);
            case '/ingredients/new':
              return MaterialPageRoute(builder: (_) => const IngredientFormScreen(), settings: settings);
            case '/warehouse':
              return MaterialPageRoute(builder: (_) => const WarehouseScreen(), settings: settings);
            case '/warehouse/stock':
              return MaterialPageRoute(builder: (_) => const ProductStockScreen(), settings: settings);
            case '/warehouse/receipts':
              return MaterialPageRoute(
                builder: (_) => const StockMovementScreen(isReceipt: true, isProduct: true),
                settings: settings,
              );
            case '/warehouse/writeoffs':
              return MaterialPageRoute(
                builder: (_) => const StockMovementScreen(isReceipt: false, isProduct: true),
                settings: settings,
              );
            case '/ingredients-warehouse':
              return MaterialPageRoute(builder: (_) => const IngredientWarehouseScreen(), settings: settings);
            case '/ingredients/receipts':
              return MaterialPageRoute(
                builder: (_) => const StockMovementScreen(isReceipt: true, isProduct: false),
                settings: settings,
              );
            case '/ingredients/writeoffs':
              return MaterialPageRoute(
                builder: (_) => const StockMovementScreen(isReceipt: false, isProduct: false),
                settings: settings,
              );
            case '/orders':
              final args = settings.arguments;
              return MaterialPageRoute(
                builder: (_) => OrdersScreen(initialFilter: args is OrdersFilterArgs ? args : null),
                settings: settings,
              );
            case '/orders/new':
              return MaterialPageRoute(builder: (_) => const OrderFormScreen(), settings: settings);
            case '/production':
              return MaterialPageRoute(builder: (_) => const ProductionScreen(), settings: settings);
            case '/employees':
              return MaterialPageRoute(builder: (_) => const EmployeesScreen(), settings: settings);
            case '/tech-cards':
              return MaterialPageRoute(builder: (_) => const TechCardsScreen(), settings: settings);
            case '/production-requests':
              return MaterialPageRoute(builder: (_) => const ProductionRequestsScreen(), settings: settings);
            case '/production-requests/new':
              return MaterialPageRoute(builder: (_) => const ProductionRequestFormScreen(), settings: settings);
            case '/reports':
              return MaterialPageRoute(builder: (_) => const ReportsScreen(), settings: settings);
            case '/production-requests/report':
              return MaterialPageRoute(builder: (_) => const ProductionReportScreen(), settings: settings);
            case '/purchases':
              return MaterialPageRoute(builder: (_) => const PurchasesScreen(), settings: settings);
            case '/notifications':
              return MaterialPageRoute(builder: (_) => const NotificationsScreen(), settings: settings);
            case '/returns':
              return MaterialPageRoute(builder: (_) => const ReturnsScreen(), settings: settings);
            case '/debts':
              return MaterialPageRoute(builder: (_) => const DebtsScreen(), settings: settings);
            case '/whatsapp-inbox':
              return MaterialPageRoute(builder: (_) => const WhatsAppInboxScreen(), settings: settings);
            case '/settings':
              return MaterialPageRoute(builder: (_) => const SettingsScreen(), settings: settings);
            case '/settings/company':
              return MaterialPageRoute(builder: (_) => const CompanyProfileScreen(), settings: settings);
            case '/settings/prices':
              return MaterialPageRoute(builder: (_) => const PricesScreen(), settings: settings);
            case '/settings/users':
              return MaterialPageRoute(builder: (_) => const UsersScreen(), settings: settings);
            case '/settings/users/new':
              return MaterialPageRoute(builder: (_) => const UserFormScreen(), settings: settings);
            case '/settings/whatsapp':
              return MaterialPageRoute(builder: (_) => const WhatsAppSessionScreen(), settings: settings);
            case '/settings/counterparties':
              return MaterialPageRoute(builder: (_) => const CounterpartiesScreen(), settings: settings);
          }

          final args = settings.arguments;
          if (settings.name == '/clients/detail' && args is int) {
            return MaterialPageRoute(
              builder: (_) => ClientDetailScreen(clientId: args),
              settings: settings,
            );
          }
          if (settings.name == '/clients/prices' && args is int) {
            return MaterialPageRoute(
              builder: (_) => ClientPricesScreen(clientId: args),
              settings: settings,
            );
          }
          if (settings.name == '/clients/edit' && args is int) {
            return MaterialPageRoute(
              builder: (_) => ClientFormScreen(clientId: args),
              settings: settings,
            );
          }
          if (settings.name == '/products/edit' && args is int) {
            return MaterialPageRoute(
              builder: (_) => ProductFormScreen(productId: args),
              settings: settings,
            );
          }
          if (settings.name == '/products/recipe' && args is int) {
            return MaterialPageRoute(
              builder: (_) => RecipeScreen(productId: args),
              settings: settings,
            );
          }
          if (settings.name == '/ingredients/edit' && args is int) {
            return MaterialPageRoute(
              builder: (_) => IngredientFormScreen(ingredientId: args),
              settings: settings,
            );
          }
          if (settings.name == '/orders/detail' && args is int) {
            return MaterialPageRoute(
              builder: (_) => OrderDetailScreen(orderId: args),
              settings: settings,
            );
          }
          if (settings.name == '/production-requests/detail' && args is int) {
            return MaterialPageRoute(
              builder: (_) => ProductionRequestDetailScreen(requestId: args),
              settings: settings,
            );
          }
          if (settings.name == '/production-requests/edit' && args is int) {
            return MaterialPageRoute(
              builder: (_) => ProductionRequestFormScreen(requestId: args),
              settings: settings,
            );
          }
          if (settings.name == '/whatsapp-inbox/detail' && args is WhatsAppInboundMessage) {
            return MaterialPageRoute(
              builder: (_) => WhatsAppInboxResolveScreen(message: args),
              settings: settings,
            );
          }
          if (settings.name == '/settings/users/edit' && args is int) {
            return MaterialPageRoute(
              builder: (_) => UserFormScreen(userId: args),
              settings: settings,
            );
          }

          return MaterialPageRoute(builder: (_) => const DashboardScreen());
        },
      ),
    );
  }
}
