// Универсальный отчёт из меню «Отчёты» (см. backend ReportDto): карточки + одна таблица.
// Типы колонок: TEXT, MONEY, QTY, INT. tone: good / bad / muted / null.

class ReportInfo {
  final String key;
  final String title;
  final String description;

  /// DAY — один день, PERIOD — с–по.
  final String period;

  /// TODAY / MONTH — какой период открыть по умолчанию.
  final String defaultRange;

  ReportInfo({required this.key, required this.title, required this.description, required this.period, required this.defaultRange});

  factory ReportInfo.fromJson(Map<String, dynamic> j) => ReportInfo(
        key: j['key'] as String,
        title: j['title'] as String,
        description: j['description'] as String? ?? '',
        period: j['period'] as String? ?? 'PERIOD',
        defaultRange: j['defaultRange'] as String? ?? 'TODAY',
      );
}

class ReportCard {
  final String label;
  final dynamic value;
  final String type;
  final String? hint;
  final String? tone;

  ReportCard({required this.label, this.value, required this.type, this.hint, this.tone});

  factory ReportCard.fromJson(Map<String, dynamic> j) => ReportCard(
        label: j['label'] as String,
        value: j['value'],
        type: j['type'] as String? ?? 'TEXT',
        hint: j['hint'] as String?,
        tone: j['tone'] as String?,
      );
}

class ReportColumn {
  final String label;
  final String type;

  ReportColumn(this.label, this.type);

  bool get numeric => type != 'TEXT';
}

class ReportRow {
  final List<dynamic> cells;
  final String? tone;

  ReportRow(this.cells, this.tone);
}

class Report {
  final String key;
  final String title;
  final DateTime from;
  final DateTime to;
  final List<ReportCard> cards;
  final List<ReportColumn> columns;
  final List<ReportRow> rows;
  final List<dynamic>? totals;
  final List<String> notes;

  Report({
    required this.key,
    required this.title,
    required this.from,
    required this.to,
    required this.cards,
    required this.columns,
    required this.rows,
    this.totals,
    required this.notes,
  });

  factory Report.fromJson(Map<String, dynamic> j) => Report(
        key: j['key'] as String,
        title: j['title'] as String,
        from: DateTime.parse(j['from'] as String),
        to: DateTime.parse(j['to'] as String),
        cards: [for (final c in j['cards'] as List? ?? []) ReportCard.fromJson(c as Map<String, dynamic>)],
        columns: [
          for (final c in j['columns'] as List) ReportColumn((c as Map)['label'] as String, c['type'] as String? ?? 'TEXT')
        ],
        rows: [
          for (final r in j['rows'] as List)
            ReportRow(List<dynamic>.from((r as Map)['cells'] as List), r['tone'] as String?)
        ],
        totals: j['totals'] == null ? null : List<dynamic>.from(j['totals'] as List),
        notes: [for (final n in j['notes'] as List? ?? []) n as String],
      );
}
