import 'package:flutter/material.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'navigation.dart';
import 'screens/client_detail_screen.dart';
import 'screens/client_form_screen.dart';
import 'screens/client_prices_screen.dart';
import 'screens/clients_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/ingredient_form_screen.dart';
import 'screens/ingredients_screen.dart';
import 'screens/login_screen.dart';
import 'screens/notifications_screen.dart';
import 'screens/order_detail_screen.dart';
import 'screens/order_form_screen.dart';
import 'screens/orders_screen.dart';
import 'screens/product_form_screen.dart';
import 'screens/products_screen.dart';
import 'screens/production_screen.dart';
import 'screens/recipe_screen.dart';
import 'screens/settings_screen.dart';
import 'services/api_client.dart';

const accent = Color(0xFFC96A4B);
const accentDark = Color(0xFFA8543A);
const ink = Color(0xFF2B2420);
const bg = Color(0xFFF6F4F1);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('ru');
  await ApiClient.loadSession();
  runApp(const CrmApp());
}

class CrmApp extends StatelessWidget {
  const CrmApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Кондитерская CRM',
      debugShowCheckedModeBanner: false,
      navigatorKey: navigatorKey,
      theme: ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: bg,
        colorScheme: ColorScheme.fromSeed(seedColor: accent, primary: accent, secondary: accentDark),
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
          border: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(8))),
          filled: true,
          fillColor: Colors.white,
        ),
        floatingActionButtonTheme: const FloatingActionButtonThemeData(backgroundColor: accent),
      ),
      initialRoute: ApiClient.currentSession == null ? '/login' : '/',
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
          case '/ingredients':
            return MaterialPageRoute(builder: (_) => const IngredientsScreen(), settings: settings);
          case '/ingredients/new':
            return MaterialPageRoute(builder: (_) => const IngredientFormScreen(), settings: settings);
          case '/orders':
            return MaterialPageRoute(builder: (_) => const OrdersScreen(), settings: settings);
          case '/orders/new':
            return MaterialPageRoute(builder: (_) => const OrderFormScreen(), settings: settings);
          case '/production':
            return MaterialPageRoute(builder: (_) => const ProductionScreen(), settings: settings);
          case '/notifications':
            return MaterialPageRoute(builder: (_) => const NotificationsScreen(), settings: settings);
          case '/settings':
            return MaterialPageRoute(builder: (_) => const SettingsScreen(), settings: settings);
        }

        final args = settings.arguments;
        if (settings.name == '/clients/detail' && args is int) {
          return MaterialPageRoute(builder: (_) => ClientDetailScreen(clientId: args), settings: settings);
        }
        if (settings.name == '/clients/prices' && args is int) {
          return MaterialPageRoute(builder: (_) => ClientPricesScreen(clientId: args), settings: settings);
        }
        if (settings.name == '/clients/edit' && args is int) {
          return MaterialPageRoute(builder: (_) => ClientFormScreen(clientId: args), settings: settings);
        }
        if (settings.name == '/products/edit' && args is int) {
          return MaterialPageRoute(builder: (_) => ProductFormScreen(productId: args), settings: settings);
        }
        if (settings.name == '/products/recipe' && args is int) {
          return MaterialPageRoute(builder: (_) => RecipeScreen(productId: args), settings: settings);
        }
        if (settings.name == '/ingredients/edit' && args is int) {
          return MaterialPageRoute(builder: (_) => IngredientFormScreen(ingredientId: args), settings: settings);
        }
        if (settings.name == '/orders/detail' && args is int) {
          return MaterialPageRoute(builder: (_) => OrderDetailScreen(orderId: args), settings: settings);
        }

        return MaterialPageRoute(builder: (_) => const DashboardScreen());
      },
    );
  }
}
