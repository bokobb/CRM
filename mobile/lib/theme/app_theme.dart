import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Палитра темы приложения. Тем несколько (см. [AppPalette.all]) — пользователь выбирает
/// дневную и ночную в «Настройки › Оформление». Экраны берут цвета через `context.pal`, а не
/// константами, чтобы любая тема работала везде.
@immutable
class AppPalette extends ThemeExtension<AppPalette> {
  final String id;
  final String name;
  final bool isDark;

  /// Шрифт темы; null — системный (Roboto), как было в первой версии приложения.
  final String? font;

  /// Карточки с тонкой рамкой; без рамки — отделяются от фона только оттенком.
  final bool bordered;
  final double radius;

  /// Множитель размера текста (тема «Склад» — крупнее) и плотные строки (тема «Офис»).
  final double textScale;
  final bool compact;

  /// Цвет шапки экрана; null — как фон (у «Корпоративного синего» шапка тёмно-синяя).
  final Color? header;
  final Color? onHeader;

  final Color bg;
  final Color card;
  final Color ink;
  final Color muted;
  final Color line;
  final Color seg;
  final Color track;
  final Color accent;
  final Color onAccent;
  final Color danger;
  final Color warn;
  final Color success;
  final Color info;

  const AppPalette({
    required this.id,
    required this.name,
    required this.isDark,
    this.font = appFont,
    this.bordered = true,
    this.radius = 14,
    this.textScale = 1.0,
    this.compact = false,
    this.header,
    this.onHeader,
    required this.bg,
    required this.card,
    required this.ink,
    required this.muted,
    required this.line,
    required this.seg,
    required this.track,
    required this.accent,
    this.onAccent = Colors.white,
    required this.danger,
    required this.warn,
    required this.success,
    required this.info,
  });

  /// Первая версия: малиновый акцент, кремовый фон, системный шрифт.
  static const classic = AppPalette(
    id: 'classic',
    name: 'Классическая',
    isDark: false,
    font: null,
    radius: 12,
    bg: Color(0xFFF7F3EC),
    card: Color(0xFFFFFFFF),
    ink: Color(0xFF2B1F1A),
    muted: Color(0xFF8A817A),
    line: Color(0xFFE7E0D8),
    seg: Color(0xFFEFE8DE),
    track: Color(0xFFEFE8DE),
    accent: Color(0xFFB8324A),
    danger: Color(0xFFB8433F),
    warn: Color(0xFFC08A2E),
    success: Color(0xFF4C8A5E),
    info: Color(0xFF3F6A93),
  );

  /// Строгая (день): нейтральный фон, белые карточки с рамкой, бордовый акцент.
  static const light = AppPalette(
    id: 'strict_light',
    name: 'Строгая',
    isDark: false,
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

  /// Тёплая бумага: без чистого белого, тёмно-коричневый текст, терракотовый акцент.
  static const paper = AppPalette(
    id: 'paper',
    name: 'Тёплая бумага',
    isDark: false,
    bordered: false,
    radius: 18,
    bg: Color(0xFFECE6DC),
    card: Color(0xFFF6F2EA),
    ink: Color(0xFF3B342D),
    muted: Color(0xFF7A7066),
    line: Color(0xFFE4DDD2),
    seg: Color(0xFFE2DBCF),
    track: Color(0xFFE2DBCF),
    accent: Color(0xFF8C4A3C),
    onAccent: Color(0xFFFBF6EE),
    danger: Color(0xFFA5453A),
    warn: Color(0xFF9A6A1E),
    success: Color(0xFF4A7A55),
    info: Color(0xFF3F6A93),
  );

  /// Шалфей: приглушённый серо-зелёный, минимум бликов.
  static const sage = AppPalette(
    id: 'sage',
    name: 'Шалфей',
    isDark: false,
    bordered: false,
    radius: 18,
    bg: Color(0xFFE3E7E1),
    card: Color(0xFFEEF1EC),
    ink: Color(0xFF2F3631),
    muted: Color(0xFF6C766E),
    line: Color(0xFFDCE1D9),
    seg: Color(0xFFD8DDD5),
    track: Color(0xFFD8DDD5),
    accent: Color(0xFF3F6B5B),
    onAccent: Color(0xFFF3F6F2),
    danger: Color(0xFFA44B42),
    warn: Color(0xFF94701F),
    success: Color(0xFF3F6B5B),
    info: Color(0xFF3D6888),
  );

  /// Строгая (ночь): тёмно-серая.
  static const dark = AppPalette(
    id: 'strict_dark',
    name: 'Строгая ночь',
    isDark: true,
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

  /// Сумерки: тёмная, но не чёрная; светло-серый текст вместо белого.
  static const dusk = AppPalette(
    id: 'dusk',
    name: 'Сумерки',
    isDark: true,
    bordered: false,
    radius: 18,
    bg: Color(0xFF2B2E33),
    card: Color(0xFF353940),
    ink: Color(0xFFD8D5CF),
    muted: Color(0xFF9C9A95),
    line: Color(0xFF40454C),
    seg: Color(0xFF34383E),
    track: Color(0xFF40454C),
    accent: Color(0xFFC98A7E),
    onAccent: Color(0xFF2B2E33),
    danger: Color(0xFFE2958B),
    warn: Color(0xFFD9B26B),
    success: Color(0xFF9CC4A5),
    info: Color(0xFF93B3D3),
  );

  /// Сепия: тёплая тёмная, как режим чтения.
  static const sepia = AppPalette(
    id: 'sepia',
    name: 'Сепия',
    isDark: true,
    bordered: false,
    radius: 18,
    bg: Color(0xFF26211C),
    card: Color(0xFF322C26),
    ink: Color(0xFFE3D8C8),
    muted: Color(0xFFA89B8A),
    line: Color(0xFF3D362F),
    seg: Color(0xFF302A24),
    track: Color(0xFF3D362F),
    accent: Color(0xFFD2A15F),
    onAccent: Color(0xFF26211C),
    danger: Color(0xFFE09A86),
    warn: Color(0xFFD9B06A),
    success: Color(0xFFB3C59A),
    info: Color(0xFFA9BCCB),
  );

  /// Корпоративный синий: тёмно-синяя шапка, светло-серый фон, белые карточки.
  static const navy = AppPalette(
    id: 'navy',
    name: 'Корпоративный синий',
    isDark: false,
    bordered: false,
    radius: 16,
    header: Color(0xFF22364F),
    onHeader: Color(0xFFE6ECF3),
    bg: Color(0xFFEEF1F5),
    card: Color(0xFFFFFFFF),
    ink: Color(0xFF22303F),
    muted: Color(0xFF6A7889),
    line: Color(0xFFE3E8EE),
    seg: Color(0xFFE1E6EC),
    track: Color(0xFFE1E6EC),
    accent: Color(0xFF2F4867),
    onAccent: Color(0xFFE6ECF3),
    danger: Color(0xFFB0503F),
    warn: Color(0xFF9A6A1E),
    success: Color(0xFF3E7457),
    info: Color(0xFF2F5E8E),
  );

  /// Офис: нейтральный тёплый серый, плотные строки — больше заказов на экране.
  static const office = AppPalette(
    id: 'office',
    name: 'Офис (компактно)',
    isDark: false,
    radius: 10,
    compact: true,
    bg: Color(0xFFF2F1EE),
    card: Color(0xFFFAF9F7),
    ink: Color(0xFF2D2B28),
    muted: Color(0xFF77726B),
    line: Color(0xFFE2DFD9),
    seg: Color(0xFFE8E6E1),
    track: Color(0xFFE8E6E1),
    accent: Color(0xFF4A5568),
    danger: Color(0xFFA5453A),
    warn: Color(0xFFA8741F),
    success: Color(0xFF4A7A55),
    info: Color(0xFF3F6A93),
  );

  /// Склад: песочный, крупный текст и большие скругления — видно издалека.
  static const warehouse = AppPalette(
    id: 'warehouse',
    name: 'Склад (крупно)',
    isDark: false,
    bordered: false,
    radius: 22,
    textScale: 1.15,
    bg: Color(0xFFEAE5DC),
    card: Color(0xFFF4F0E8),
    ink: Color(0xFF35302A),
    muted: Color(0xFF7A7167),
    line: Color(0xFFE0D9CD),
    seg: Color(0xFFE0D9CD),
    track: Color(0xFFE0D9CD),
    accent: Color(0xFF6E4A3A),
    onAccent: Color(0xFFF7F1E8),
    danger: Color(0xFFA5453A),
    warn: Color(0xFFA87A2A),
    success: Color(0xFF557C5E),
    info: Color(0xFF45698C),
  );

  /// Лента: тёплый бежевый, коричневый акцент.
  static const ribbon = AppPalette(
    id: 'ribbon',
    name: 'Лента',
    isDark: false,
    bordered: false,
    bg: Color(0xFFF1EEE8),
    card: Color(0xFFF9F7F3),
    ink: Color(0xFF33302B),
    muted: Color(0xFF7D766C),
    line: Color(0xFFDDD7CD),
    seg: Color(0xFFE7E2D9),
    track: Color(0xFFE7E2D9),
    accent: Color(0xFF5E4636),
    onAccent: Color(0xFFF7F1E8),
    danger: Color(0xFFA5453A),
    warn: Color(0xFFA87A2A),
    success: Color(0xFF557C5E),
    info: Color(0xFF45698C),
  );

  /// Доска: холодный светло-серый, синий акцент.
  static const board = AppPalette(
    id: 'board',
    name: 'Доска',
    isDark: false,
    bordered: false,
    radius: 16,
    bg: Color(0xFFE9ECEF),
    card: Color(0xFFF5F7F9),
    ink: Color(0xFF2A3038),
    muted: Color(0xFF6B7480),
    line: Color(0xFFDFE3E8),
    seg: Color(0xFFDFE3E8),
    track: Color(0xFFDFE3E8),
    accent: Color(0xFF3D5A80),
    danger: Color(0xFFA44B42),
    warn: Color(0xFFA87A2A),
    success: Color(0xFF557C5E),
    info: Color(0xFF45698C),
  );

  /// Минимализм: карточки сливаются с фоном, только тонкие линии; тёмные кнопки.
  static const minimal = AppPalette(
    id: 'minimal',
    name: 'Минимализм',
    isDark: false,
    radius: 4,
    bg: Color(0xFFF3F0EA),
    card: Color(0xFFF3F0EA),
    ink: Color(0xFF3A3631),
    muted: Color(0xFF8A8278),
    line: Color(0xFFDCD6CC),
    seg: Color(0xFFE9E4DB),
    track: Color(0xFFE4DFD6),
    accent: Color(0xFF3A3631),
    onAccent: Color(0xFFF3F0EA),
    danger: Color(0xFFA5453A),
    warn: Color(0xFF9A6A1E),
    success: Color(0xFF557C5E),
    info: Color(0xFF45698C),
  );

  /// Графит: тёмно-серый с янтарным акцентом.
  static const graphite = AppPalette(
    id: 'graphite',
    name: 'Графит',
    isDark: true,
    bordered: false,
    radius: 18,
    bg: Color(0xFF303236),
    card: Color(0xFF3A3D42),
    ink: Color(0xFFDCD9D3),
    muted: Color(0xFFA3A09A),
    line: Color(0xFF46494F),
    seg: Color(0xFF44474D),
    track: Color(0xFF46494F),
    accent: Color(0xFFE0A667),
    onAccent: Color(0xFF303236),
    danger: Color(0xFFE3A08F),
    warn: Color(0xFFD9B26B),
    success: Color(0xFF9FC4A8),
    info: Color(0xFF93B3D3),
  );

  /// Тёмно-синий: мягкая синяя ночь, не чёрная.
  static const navyNight = AppPalette(
    id: 'navy_night',
    name: 'Тёмно-синий',
    isDark: true,
    bordered: false,
    radius: 18,
    bg: Color(0xFF1F2A38),
    card: Color(0xFF283546),
    ink: Color(0xFFD5DCE4),
    muted: Color(0xFF8E9CAD),
    line: Color(0xFF34445A),
    seg: Color(0xFF2C3A4C),
    track: Color(0xFF34445A),
    accent: Color(0xFF7FA3C8),
    onAccent: Color(0xFF1F2A38),
    danger: Color(0xFFD9A193),
    warn: Color(0xFFE0BE7E),
    success: Color(0xFF9CC0A6),
    info: Color(0xFF9DBCDB),
  );

  static const all = [
    classic, light, paper, sage, navy, office, warehouse, ribbon, board, minimal,
    dark, dusk, sepia, graphite, navyNight,
  ];
  static List<AppPalette> get lightThemes => all.where((p) => !p.isDark).toList();
  static List<AppPalette> get darkThemes => all.where((p) => p.isDark).toList();

  static AppPalette byId(String? id, AppPalette fallback) =>
      all.firstWhere((p) => p.id == id, orElse: () => fallback);

  /// Мягкий фон под цветной текст/иконку (плашки статусов, предупреждения) — в любой теме.
  Color tint(Color c) => c.withValues(alpha: isDark ? 0.16 : 0.10);

  /// Рамка карточки: линия или (у тем без рамок) прозрачная.
  Color get cardBorder => bordered ? line : Colors.transparent;

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
    onPrimary: p.onAccent,
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
  final base = ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    fontFamily: p.font,
    visualDensity: p.compact ? VisualDensity.compact : VisualDensity.standard,
  );
  const radius12 = BorderRadius.all(Radius.circular(12));
  return base.copyWith(
    extensions: [p],
    scaffoldBackgroundColor: p.bg,
    canvasColor: p.bg,
    dividerColor: p.line,
    textTheme: base.textTheme.apply(bodyColor: p.ink, displayColor: p.ink),
    appBarTheme: AppBarTheme(
      backgroundColor: p.header ?? p.bg,
      foregroundColor: p.onHeader ?? p.ink,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(fontFamily: p.font, fontSize: 20, fontWeight: FontWeight.w600, color: p.onHeader ?? p.ink, letterSpacing: -0.2),
    ),
    cardTheme: CardThemeData(
      color: p.card,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: const EdgeInsets.symmetric(vertical: 4),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(p.radius)),
        side: BorderSide(color: p.cardBorder),
      ),
    ),
    dividerTheme: DividerThemeData(color: p.line, thickness: 1, space: 1),
    listTileTheme: ListTileThemeData(
      iconColor: p.muted,
      textColor: p.ink,
      selectedColor: p.ink,
      selectedTileColor: p.seg,
      dense: p.compact,
    ),
    drawerTheme: DrawerThemeData(backgroundColor: p.bg, surfaceTintColor: Colors.transparent),
    dialogTheme: DialogThemeData(backgroundColor: p.card, surfaceTintColor: Colors.transparent),
    bottomSheetTheme: BottomSheetThemeData(backgroundColor: p.card, surfaceTintColor: Colors.transparent, modalBackgroundColor: p.card),
    popupMenuTheme: PopupMenuThemeData(color: p.card, surfaceTintColor: Colors.transparent),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: p.isDark ? p.seg : p.ink,
      contentTextStyle: TextStyle(fontFamily: p.font, color: p.isDark ? p.ink : p.bg),
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
        foregroundColor: p.onAccent,
        minimumSize: const Size(0, 48),
        shape: const RoundedRectangleBorder(borderRadius: radius12),
        textStyle: TextStyle(fontFamily: p.font, fontSize: 15, fontWeight: FontWeight.w600),
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: p.accent,
        foregroundColor: p.onAccent,
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
        textStyle: TextStyle(fontFamily: p.font, fontSize: 15, fontWeight: FontWeight.w500),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: p.accent)),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: p.accent,
      foregroundColor: p.onAccent,
      elevation: 2,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(16))),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: p.card,
      selectedColor: p.ink,
      side: BorderSide(color: p.line),
      labelStyle: TextStyle(fontFamily: p.font, color: p.ink, fontSize: 13),
      secondaryLabelStyle: TextStyle(fontFamily: p.font, color: p.bg, fontSize: 13),
      checkmarkColor: p.bg,
      shape: const StadiumBorder(),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: p.accent, linearTrackColor: p.track),
    checkboxTheme: CheckboxThemeData(
      fillColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.accent : null),
      checkColor: WidgetStatePropertyAll(p.onAccent),
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

/// Выбор оформления (хранится на телефоне): дневная тема, ночная тема и режим — авто (как в
/// системе), день или ночь. Переключатель солнце/луна меняет режим между выбранными темами.
class ThemeController {
  static final mode = ValueNotifier<ThemeMode>(ThemeMode.system);
  static final day = ValueNotifier<AppPalette>(AppPalette.light);
  static final night = ValueNotifier<AppPalette>(AppPalette.dark);
  static final listenable = Listenable.merge([mode, day, night]);

  static const _modeKey = 'theme_mode';
  static const _dayKey = 'theme_day';
  static const _nightKey = 'theme_night';

  static Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      mode.value = switch (prefs.getString(_modeKey)) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      };
      day.value = AppPalette.byId(prefs.getString(_dayKey), AppPalette.light);
      night.value = AppPalette.byId(prefs.getString(_nightKey), AppPalette.dark);
    } catch (_) {}
  }

  static Future<void> setMode(ThemeMode m) async {
    mode.value = m;
    await _save(_modeKey, switch (m) { ThemeMode.light => 'light', ThemeMode.dark => 'dark', _ => 'system' });
  }

  static Future<void> setDay(AppPalette p) async {
    day.value = p;
    await _save(_dayKey, p.id);
  }

  static Future<void> setNight(AppPalette p) async {
    night.value = p;
    await _save(_nightKey, p.id);
  }

  /// Переключатель в шапке: из текущего вида — в противоположный.
  static Future<void> toggle(BuildContext context) =>
      setMode(Theme.of(context).brightness == Brightness.dark ? ThemeMode.light : ThemeMode.dark);

  static Future<void> _save(String key, String value) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(key, value);
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
