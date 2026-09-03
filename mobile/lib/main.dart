import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'models/whatsapp_inbox.dart';
import 'navigation.dart';
import 'screens/client_detail_screen.dart';
import 'screens/client_form_screen.dart';
import 'screens/client_prices_screen.dart';
import 'screens/clients_screen.dart';
import 'screens/company_profile_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/debts_screen.dart';
import 'screens/ingredient_form_screen.dart';
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
import 'screens/production_request_detail_screen.dart';
import 'screens/production_request_form_screen.dart';
import 'screens/production_requests_screen.dart';
import 'screens/production_screen.dart';
import 'screens/purchases_screen.dart';
import 'screens/recipe_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/stock_movement_screen.dart';
import 'screens/user_form_screen.dart';
import 'screens/users_screen.dart';
import 'screens/warehouse_screen.dart';
import 'screens/whatsapp_inbox_resolve_screen.dart';
import 'screens/whatsapp_inbox_screen.dart';
import 'services/api_client.dart';
import 'services/push_service.dart';

const accent = Color(0xFFB8324A);
const accentDark = Color(0xFF8F2438);
const gold = Color(0xFFD9A73F);
const ink = Color(0xFF2B1F1A);
const bg = Color(0xFFF7F3EC);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('ru');
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
    await Firebase.initializeApp();
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
    return MaterialApp(
      title: 'Юг Сладости',
      debugShowCheckedModeBanner: false,
      navigatorKey: navigatorKey,
      theme: ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: bg,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          primary: accent,
          secondary: gold,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: bg,
          foregroundColor: ink,
          elevation: 0,
          centerTitle: false,
        ),
        cardTheme: const CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(12)),
            side: BorderSide(color: Color(0xFFE7E0D8)),
          ),
        ),
        inputDecorationTheme: const InputDecorationTheme(
          border: OutlineInputBorder(
            borderRadius: BorderRadius.all(Radius.circular(8)),
          ),
          filled: true,
          fillColor: Colors.white,
        ),
        floatingActionButtonTheme: const FloatingActionButtonThemeData(
          backgroundColor: accent,
        ),
      ),
      initialRoute: ApiClient.currentSession == null ? '/login' : '/',
      onGenerateRoute: (settings) {
        switch (settings.name) {
          case '/login':
            return MaterialPageRoute(
              builder: (_) => const LoginScreen(),
              settings: settings,
            );
          case '/':
            return MaterialPageRoute(
              builder: (_) => const DashboardScreen(),
              settings: settings,
            );
          case '/clients':
            return MaterialPageRoute(
              builder: (_) => const ClientsScreen(),
              settings: settings,
            );
          case '/clients/new':
            return MaterialPageRoute(
              builder: (_) => const ClientFormScreen(),
              settings: settings,
            );
          case '/products':
            return MaterialPageRoute(
              builder: (_) => const ProductsScreen(),
              settings: settings,
            );
          case '/products/new':
            return MaterialPageRoute(
              builder: (_) => const ProductFormScreen(),
              settings: settings,
            );
          case '/ingredients':
            return MaterialPageRoute(
              builder: (_) => const IngredientsScreen(),
              settings: settings,
            );
          case '/ingredients/new':
            return MaterialPageRoute(
              builder: (_) => const IngredientFormScreen(),
              settings: settings,
            );
          case '/warehouse':
            return MaterialPageRoute(
              builder: (_) => const WarehouseScreen(),
              settings: settings,
            );
          case '/warehouse/stock':
            return MaterialPageRoute(
              builder: (_) => const ProductStockScreen(),
              settings: settings,
            );
          case '/warehouse/receipts':
            return MaterialPageRoute(
              builder: (_) => const StockMovementScreen(isReceipt: true),
              settings: settings,
            );
          case '/warehouse/writeoffs':
            return MaterialPageRoute(
              builder: (_) => const StockMovementScreen(isReceipt: false),
              settings: settings,
            );
          case '/orders':
            final args = settings.arguments;
            return MaterialPageRoute(
              builder: (_) => OrdersScreen(
                initialFilter: args is OrdersFilterArgs ? args : null,
              ),
              settings: settings,
            );
          case '/orders/new':
            return MaterialPageRoute(
              builder: (_) => const OrderFormScreen(),
              settings: settings,
            );
          case '/production':
            return MaterialPageRoute(
              builder: (_) => const ProductionScreen(),
              settings: settings,
            );
          case '/production-requests':
            return MaterialPageRoute(
              builder: (_) => const ProductionRequestsScreen(),
              settings: settings,
            );
          case '/production-requests/new':
            return MaterialPageRoute(
              builder: (_) => const ProductionRequestFormScreen(),
              settings: settings,
            );
          case '/purchases':
            return MaterialPageRoute(
              builder: (_) => const PurchasesScreen(),
              settings: settings,
            );
          case '/notifications':
            return MaterialPageRoute(
              builder: (_) => const NotificationsScreen(),
              settings: settings,
            );
          case '/debts':
            return MaterialPageRoute(
              builder: (_) => const DebtsScreen(),
              settings: settings,
            );
          case '/whatsapp-inbox':
            return MaterialPageRoute(
              builder: (_) => const WhatsAppInboxScreen(),
              settings: settings,
            );
          case '/settings':
            return MaterialPageRoute(
              builder: (_) => const SettingsScreen(),
              settings: settings,
            );
          case '/settings/company':
            return MaterialPageRoute(
              builder: (_) => const CompanyProfileScreen(),
              settings: settings,
            );
          case '/settings/prices':
            return MaterialPageRoute(
              builder: (_) => const PricesScreen(),
              settings: settings,
            );
          case '/settings/users':
            return MaterialPageRoute(
              builder: (_) => const UsersScreen(),
              settings: settings,
            );
          case '/settings/users/new':
            return MaterialPageRoute(
              builder: (_) => const UserFormScreen(),
              settings: settings,
            );
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
    );
  }
}
