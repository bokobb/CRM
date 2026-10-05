import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Палитра строгого делового стиля (см. макет «Юг Сладости — новый стиль»): нейтральный фон,
/// белые карточки с тонкой рамкой, бордовый акцент только на главных действиях. Две версии —
/// день и ночь; экраны берут цвета через `context.pal`, а не константами, чтобы ночная тема
/// работала везде.
@immutable
class AppPalette extends ThemeExtension<AppPalette> {
  final Color bg;
  final Color card;
  final Color ink;
  final Color muted;
  final Color line;
  final Color seg;
  final Color track;
  final Color accent;
  final Color danger;
  final Color warn;
  final Color success;
  final Color info;

  const AppPalette({
    required this.bg,
    required this.card,
    required this.ink,
    required this.muted,
    required this.line,
    required this.seg,
    required this.track,
    required this.accent,
    required this.danger,
    required this.warn,
    required this.success,
    required this.info,
  });

  static const light = AppPalette(
    bg: Color(0xFFF5F4F1),
    card: Color(0xFFFFFFFF),
    ink: Color(0xFF1C1B1A),
    muted: Color(0xFF6B665F),
    line: Color(0xFFE6E3DE),
    seg: Color(0xFFEAE7E2),
    track: Color(0xFFEFECE8),
    accent: Color(0xFF7A1F2E),
    danger: Color(0xFFB3261E),
    warn: Color(0xFFA86B12),
    success: Color(0xFF2F7A4F),
    info: Color(0xFF2B6CB0),
  );

  static const dark = AppPalette(
    bg: Color(0xFF141517),
    card: Color(0xFF1E1F22),
    ink: Color(0xFFECEAE6),
    muted: Color(0xFFA19D97),
    line: Color(0xFF2C2D31),
    seg: Color(0xFF26272B),
    track: Color(0xFF2C2D31),
    accent: Color(0xFFC8566A),
    danger: Color(0xFFF08A80),
    warn: Color(0xFFE0A84A),
    success: Color(0xFF6CC08E),
    info: Color(0xFF7FA7E0),
  );

  /// Мягкий фон под цветной текст/иконку (плашки статусов, предупреждения) — в обеих темах.
  Color tint(Color c) => c.withValues(alpha: identical(this, dark) ? 0.16 : 0.10);

  bool get isDark => identical(this, dark);

  @override
  AppPalette copyWith() => this;

  @override
  AppPalette lerp(AppPalette? other, double t) => t < 0.5 ? this : (other ?? this);
}

extension AppPaletteContext on BuildContext {
  AppPalette get pal => Theme.of(this).extension<AppPalette>() ?? AppPalette.light;
}

const appFont = 'IBMPlexSans';

ThemeData buildAppTheme(AppPalette p) {
  final brightness = p.isDark ? Brightness.dark : Brightness.light;
  final scheme = ColorScheme.fromSeed(seedColor: p.accent, brightness: brightness).copyWith(
    primary: p.accent,
    onPrimary: Colors.white,
    secondary: p.warn,
    surface: p.card,
    onSurface: p.ink,
    onSurfaceVariant: p.muted,
    outline: p.line,
    outlineVariant: p.line,
    error: p.danger,
    surfaceContainerLowest: p.card,
    surfaceContainerLow: p.card,
    surfaceContainer: p.card,
    surfaceContainerHigh: p.card,
    surfaceContainerHighest: p.seg,
  );
  final base = ThemeData(useMaterial3: true, brightness: brightness, colorScheme: scheme, fontFamily: appFont);
  const radius12 = BorderRadius.all(Radius.circular(12));
  return base.copyWith(
    extensions: [p],
    scaffoldBackgroundColor: p.bg,
    canvasColor: p.bg,
    dividerColor: p.line,
    textTheme: base.textTheme.apply(bodyColor: p.ink, displayColor: p.ink),
    appBarTheme: AppBarTheme(
      backgroundColor: p.bg,
      foregroundColor: p.ink,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(fontFamily: appFont, fontSize: 20, fontWeight: FontWeight.w600, color: p.ink, letterSpacing: -0.2),
    ),
    cardTheme: CardThemeData(
      color: p.card,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: const EdgeInsets.symmetric(vertical: 4),
      shape: RoundedRectangleBorder(
        borderRadius: const BorderRadius.all(Radius.circular(14)),
        side: BorderSide(color: p.line),
      ),
    ),
    dividerTheme: DividerThemeData(color: p.line, thickness: 1, space: 1),
    listTileTheme: ListTileThemeData(iconColor: p.muted, textColor: p.ink, selectedColor: p.ink, selectedTileColor: p.seg),
    drawerTheme: DrawerThemeData(backgroundColor: p.bg, surfaceTintColor: Colors.transparent),
    dialogTheme: DialogThemeData(backgroundColor: p.card, surfaceTintColor: Colors.transparent),
    bottomSheetTheme: BottomSheetThemeData(backgroundColor: p.card, surfaceTintColor: Colors.transparent, modalBackgroundColor: p.card),
    popupMenuTheme: PopupMenuThemeData(color: p.card, surfaceTintColor: Colors.transparent),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: p.isDark ? p.seg : p.ink,
      contentTextStyle: TextStyle(fontFamily: appFont, color: p.isDark ? p.ink : p.bg),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: p.card,
      labelStyle: TextStyle(color: p.muted),
      hintStyle: TextStyle(color: p.muted),
      border: OutlineInputBorder(borderRadius: radius12, borderSide: BorderSide(color: p.line)),
      enabledBorder: OutlineInputBorder(borderRadius: radius12, borderSide: BorderSide(color: p.line)),
      focusedBorder: OutlineInputBorder(borderRadius: radius12, borderSide: BorderSide(color: p.accent, width: 1.6)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: p.accent,
        foregroundColor: Colors.white,
        minimumSize: const Size(0, 48),
        shape: const RoundedRectangleBorder(borderRadius: radius12),
        textStyle: const TextStyle(fontFamily: appFont, fontSize: 15, fontWeight: FontWeight.w600),
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: p.accent,
        foregroundColor: Colors.white,
        elevation: 0,
        minimumSize: const Size(0, 48),
        shape: const RoundedRectangleBorder(borderRadius: radius12),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: p.ink,
        side: BorderSide(color: p.line),
        minimumSize: const Size(0, 48),
        shape: const RoundedRectangleBorder(borderRadius: radius12),
        textStyle: const TextStyle(fontFamily: appFont, fontSize: 15, fontWeight: FontWeight.w500),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: p.accent)),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: p.accent,
      foregroundColor: Colors.white,
      elevation: 2,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(16))),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: p.card,
      selectedColor: p.ink,
      side: BorderSide(color: p.line),
      labelStyle: TextStyle(fontFamily: appFont, color: p.ink, fontSize: 13),
      secondaryLabelStyle: TextStyle(fontFamily: appFont, color: p.bg, fontSize: 13),
      checkmarkColor: p.bg,
      shape: const StadiumBorder(),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: p.accent, linearTrackColor: p.track),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.accent : null),
      checkColor: const WidgetStatePropertyAll(Colors.white),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? Colors.white : null),
      trackColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.accent : null),
    ),
    tabBarTheme: TabBarThemeData(labelColor: p.ink, unselectedLabelColor: p.muted, indicatorColor: p.accent, dividerColor: p.line),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        backgroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.ink : p.card),
        foregroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.bg : p.ink),
        side: WidgetStatePropertyAll(BorderSide(color: p.line)),
      ),
    ),
    datePickerTheme: DatePickerThemeData(backgroundColor: p.card, surfaceTintColor: Colors.transparent),
  );
}

/// День / ночь / как в системе — выбор хранится на телефоне.
class ThemeController {
  static final mode = ValueNotifier<ThemeMode>(ThemeMode.system);
  static const _key = 'theme_mode';

  static Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      mode.value = switch (prefs.getString(_key)) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      };
    } catch (_) {}
  }

  /// Переключатель в шапке: из текущего вида — в противоположный.
  static Future<void> toggle(BuildContext context) async {
    final next = Theme.of(context).brightness == Brightness.dark ? ThemeMode.light : ThemeMode.dark;
    mode.value = next;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_key, next == ThemeMode.dark ? 'dark' : 'light');
    } catch (_) {}
  }
}

/// Кнопка солнце/луна для шапки экрана.
class ThemeToggleButton extends StatelessWidget {
  const ThemeToggleButton({super.key});

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return IconButton(
      tooltip: dark ? 'Дневная тема' : 'Ночная тема',
      icon: Icon(dark ? Icons.light_mode_outlined : Icons.dark_mode_outlined),
      onPressed: () => ThemeController.toggle(context),
    );
  }
}
