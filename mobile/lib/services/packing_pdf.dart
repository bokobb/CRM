import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/services.dart' show rootBundle;
import 'package:intl/intl.dart';
import 'package:pdf/pdf.dart';

import '../models/company_profile.dart';
import '../models/order.dart';
import '../models/product.dart';

/// «Объём и масса» по заказу — собирается прямо в приложении, без 1С и без сервера. Бланк один
/// в один с расходной накладной (InvoicePdfService.SALE на бэкенде: те же координаты, шрифт
/// Liberation Sans, толщины линий), только вместо «Цена»/«Сумма» — Коробок | Объём, м³ | Масса,
/// кг. Коды товаров — Product.oneCCode (CRM сверяет их с 1С раз в сутки).
///
/// Коробок = ⌈кол-во / штук в коробке⌉ (неполная — тоже коробка), объём = коробок × объём
/// коробки, масса = кол-во × вес единицы; весовой товар (кг) без веса — масса = количество.
/// [pack] — выбранная клиентом упаковка у товаров с двумя вариантами: productId → штук в коробке.
Future<Uint8List> buildPackingPdf(Order order, Map<int, Product> products, CompanyProfile? profile,
    Map<int, double> pack) async {
  final doc = PdfDocument();
  final regular = PdfTtfFont(doc, await rootBundle.load('assets/fonts/LiberationSans-Regular.ttf'));
  final bold = PdfTtfFont(doc, await rootBundle.load('assets/fonts/LiberationSans-Bold.ttf'));
  final c = _Canvas(doc, regular, bold);

  final rows = <_Row>[];
  final missing = <String>[];
  var boxes = 0;
  var volume = 0.0;
  var mass = 0.0;
  for (final item in order.items) {
    final p = products[item.productId];
    final units = p?.packingUnits ?? const <double>[];
    int? lineBoxes;
    double? lineVolume;
    if (units.isNotEmpty) {
      final chosen = pack[item.productId];
      final idx = chosen != null && units.contains(chosen) ? units.indexOf(chosen) : 0;
      lineBoxes = (item.quantity / units[idx] - 1e-9).ceil();
      final boxVolume = idx == 0 ? p!.boxVolumeM3 : p!.boxVolume2M3;
      if (boxVolume != null) lineVolume = lineBoxes * boxVolume;
    }
    final unit = item.productUnit ?? p?.unit ?? 'шт';
    final lineMass = p?.unitWeightKg != null
        ? item.quantity * p!.unitWeightKg!
        : unit.trim().toLowerCase() == 'кг'
            ? item.quantity
            : null;
    if (lineBoxes != null) boxes += lineBoxes;
    if (lineVolume != null) volume += lineVolume;
    if (lineMass != null) mass += lineMass;
    if (lineBoxes == null || lineVolume == null || lineMass == null) missing.add(item.productName);
    rows.add(_Row(
      code: p?.oneCCode ?? '',
      name: item.productName,
      quantity: item.quantity,
      unit: unit,
      tail: [
        lineBoxes?.toString() ?? '—',
        lineVolume == null ? '—' : _volume.format(lineVolume),
        lineMass == null ? '—' : _mass.format(lineMass),
      ],
    ));
  }

  final date = packingDate(order);
  final header = <List<String>>[
    ['Поставщик', (profile?.name?.trim().isNotEmpty ?? false) ? profile!.name!.trim() : 'Юг Сладости'],
    ['Покупатель', order.clientName.trim()],
    ['Основание', 'Без договора'],
    if ((profile?.warehouseName ?? '').trim().isNotEmpty) ['Склад', profile!.warehouseName!.trim()],
  ];

  c.newPage();
  c.y = 792.36;
  _drawDocument(c, 'Расходная накладная № ${order.invoiceNumber ?? order.id} от ${_printDate(date)}', header, rows);
  _drawTotals(c, rows.length, boxes, volume, mass);
  if (missing.isNotEmpty) {
    c.y -= 14;
    c.text(c.regular, 7.68, 38.28, c.y, 'Не заданы параметры упаковки (в итог вошло не всё): ${missing.join(', ')}');
  }
  _drawSignatures(c, profile?.warehouseManagerName ?? '', order.clientName.trim());
  return doc.save();
}

/// Дата бланка — как у «Реализации» в 1С: отгрузки, до неё — создания заказа.
DateTime packingDate(Order o) => o.shippedAt ?? o.createdAt;

/// «Объём_Масса_Покупатель_дд.мм.гггг.pdf».
String packingFileName(Order o) {
  final client = o.clientName.trim().replaceAll(RegExp(r'[\\/:*?"<>|]'), ' ').replaceAll(RegExp(r'\s+'), ' ');
  return 'Объём_Масса_${client}_${DateFormat('dd.MM.yyyy').format(packingDate(o))}.pdf';
}

// ---- геометрия образца 1С (см. InvoicePdfService на бэкенде) ----

const _pageHeight = 841.8898;
const _pageTop = _pageHeight - 36;
const _pageBottom = 36.0;
const _lineGray = 0.2;
const _sampleLeft = 36.48;
const _sampleRight = 477.24;
const _right = 595.2756 - _sampleLeft;
const _stretch = (_right - _sampleLeft) / (_sampleRight - _sampleLeft);

double _sx(double x) => _sampleLeft + (x - _sampleLeft) * _stretch;

final _cols = [
  _sx(36.48), _sx(66.72), _sx(123.00), _sx(238.00), _sx(283.36), _sx(298.36), _sx(337.00), _sx(400.00), _right, //
];
const _headers = ['№ п/п', 'Код', 'Товар', 'Количество', null, 'Коробок', 'Объём, м³', 'Масса, кг'];

const _titleSize = 11.88, _titleX = 39.36, _ruleGap = 4.36, _ruleWidth = 1.27;
const _labelSize = 8.52, _labelX = 38.40, _labelFirstGap = 17.12, _labelStep = 17.28, _tableGap = 9.16;
const _thick = 1.27, _thin = 0.64, _headerHeight = 27.88, _headerSize = 8.52, _headerBaseline = 16.52;
const _rowHeight = 10.2, _rowSize = 7.68, _rowBaseline = 7.48, _pad = 1.8, _unitPad = 2.76, _rightPad = 2.0;
const _totalGap = 12.68, _totalSize = 8.52, _totalRightPad = 1.62;
const _countGap = 19.56, _countSize = 7.68, _countX = 38.28;

final _qty = NumberFormat('#,##0.000', 'en_US');
final _volume = NumberFormat('#,##0.000', 'en_US');
final _mass = NumberFormat('#,##0.00', 'en_US');

const _months = [
  'января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря', //
];

String _printDate(DateTime d) => '${d.day} ${_months[d.month - 1]} ${d.year} г.';

class _Row {
  final String code;
  final String name;
  final double quantity;
  final String unit;
  final List<String> tail;

  _Row({required this.code, required this.name, required this.quantity, required this.unit, required this.tail});
}

void _drawDocument(_Canvas c, String title, List<List<String>> header, List<_Row> rows) {
  final headBlock = _ruleGap + _labelFirstGap + (header.length - 1) * _labelStep + _tableGap + _headerHeight +
      _rowHeight + _titleSize;
  if (c.y - headBlock < _pageBottom) {
    c.newPage();
    c.y = _pageTop - _titleSize;
  }
  c.text(c.bold, _titleSize, _titleX, c.y, title);
  final ruleY = c.y - _ruleGap;
  c.hline(_cols[0], _right, ruleY, _ruleWidth, _lineGray);

  var y = ruleY - _labelFirstGap;
  for (var i = 0; i < header.length; i++) {
    if (i > 0) y -= _labelStep;
    c.text(c.regular, _labelSize, _labelX, y, header[i][0]);
    c.text(c.bold, _labelSize, _sx(155.28), y - 0.12, header[i][1]);
  }
  c.y = y - _tableGap;
  _drawTable(c, rows);
}

void _drawTable(_Canvas c, List<_Row> rows) {
  final last = _cols.length - 1;
  final nameWidth = _cols[3] - _cols[2] - 2 * _pad;
  var segmentTop = c.y;
  _drawTableHeader(c);
  var y = c.y - _headerHeight;
  for (var i = 0; i < rows.length; i++) {
    final row = rows[i];
    final nameLines = c.wrap(c.regular, _rowSize, row.name, nameWidth);
    final rowHeight = _rowHeight + (nameLines.length - 1) * (_rowSize * 1.15);
    if (y - rowHeight < _pageBottom) {
      _closeTableSegment(c, segmentTop, y);
      c.newPage();
      c.y = _pageTop;
      segmentTop = c.y;
      _drawTableHeader(c);
      y = c.y - _headerHeight;
    }
    final baseline = y - _rowBaseline;
    c.text(c.regular, _rowSize, _cols[0] + _pad, baseline, '${i + 1}');
    c.text(c.regular, _rowSize, _cols[1] + _pad, baseline, row.code);
    var ny = baseline;
    for (final part in nameLines) {
      c.text(c.regular, _rowSize, _cols[2] + _pad, ny, part);
      ny -= _rowSize * 1.15;
    }
    c.rightText(c.regular, _rowSize, _cols[4] - _rightPad, baseline, _qty.format(row.quantity));
    c.text(c.regular, _rowSize, _cols[4] + _unitPad, baseline, row.unit);
    for (var k = 0; k < row.tail.length; k++) {
      c.rightText(c.regular, _rowSize, _cols[last - row.tail.length + 1 + k] - _rightPad, baseline, row.tail[k]);
    }
    y -= rowHeight;
    c.hline(_cols[0], _cols[last], y, _thin, _lineGray);
  }
  _closeTableSegment(c, segmentTop, y);
  c.y = y - _thin / 2;
}

void _drawTableHeader(_Canvas c) {
  final top = c.y;
  final bottom = top - _headerHeight;
  c.hline(_cols[0], _cols.last, top, _thick, _lineGray);
  c.hline(_cols[0], _cols.last, bottom, _thin, _lineGray);
  final baseline = top - _headerBaseline;
  for (var i = 0; i < _headers.length; i++) {
    final h = _headers[i];
    if (h == null) continue;
    final to = (i + 1 < _headers.length && _headers[i + 1] == null) ? i + 2 : i + 1;
    c.centeredText(c.bold, _headerSize, _cols[i], _cols[to] - _cols[i], baseline, h);
  }
}

void _closeTableSegment(_Canvas c, double segmentTop, double bottom) {
  final headerBottom = segmentTop - _headerHeight;
  final lineBottom = bottom - _thin / 2;
  c.hline(_cols[0], _cols.last, lineBottom, _thick, _lineGray);
  c.vline(_cols[0] + _thick / 2, lineBottom, segmentTop, _thick);
  c.vline(_cols.last + _thick / 2, lineBottom, segmentTop, _thick);
  for (var i = 1; i < _cols.length - 1; i++) {
    final merged = _headers[i] == null;
    c.vline(_cols[i] + _thin / 2, lineBottom, merged ? headerBottom : segmentTop, _thin);
  }
}

void _drawTotals(_Canvas c, int count, int boxes, double volume, double mass) {
  if (c.y - (_totalGap + _countGap) < _pageBottom) {
    c.newPage();
    c.y = _pageTop + _totalGap - _totalSize;
  }
  c.y -= _totalGap;
  c.rightText(c.bold, _totalSize, _cols[5] - 6, c.y, 'Итого:');
  c.rightText(c.bold, _totalSize, _cols[6] - _totalRightPad, c.y, '$boxes');
  c.rightText(c.bold, _totalSize, _cols[7] - _totalRightPad, c.y, _volume.format(volume));
  c.rightText(c.bold, _totalSize, _cols[8] - _totalRightPad, c.y, _mass.format(mass));

  c.y -= _countGap;
  final text = 'Всего наименований $count, коробок $boxes, объём ${_volume.format(volume)} м³, '
      'масса ${_mass.format(mass)} кг';
  c.text(c.regular, _countSize, _countX, c.y, text);
  c.hline(_countX, _countX + c.width(c.regular, _countSize, text), c.y - 0.84, 0.6, 0);
}

void _drawSignatures(_Canvas c, String releasedBy, String receivedBy) {
  const gap = 29.76;
  if (c.y - gap < _pageBottom) {
    c.newPage();
    c.y = _pageTop + gap;
  }
  c.y -= gap;
  c.text(c.bold, 8.52, 38.64, c.y, 'Отпустил');
  _signature(c, 81.72, _sx(243.48), c.y, releasedBy);
  c.text(c.bold, 8.52, _sx(260.76), c.y, 'Получил');
  _signature(c, _sx(260.76) + (303.84 - 260.76), _right, c.y, receivedBy);
}

/// «Имя / ______»: имя в начале линии; длинное уменьшается, чтобы под подпись осталось ≥ 40 %.
void _signature(_Canvas c, double lineStart, double lineEnd, double baseline, String name) {
  const size = 7.68;
  c.hline(lineStart, lineEnd, baseline - 2.96, 0.64, _lineGray);
  final text = '${name.trim().isEmpty ? '' : '${name.trim()} '}/';
  final maxWidth = (lineEnd - lineStart) * 0.6;
  final fitted = math.min(size, size * maxWidth / c.width(c.regular, size, text));
  c.text(c.regular, fitted, lineStart + 4, baseline - 0.24, text);
}

/// Страница и позиция на ней (y — в координатах PDF, снизу вверх), как Canvas на бэкенде.
class _Canvas {
  final PdfDocument doc;
  final PdfFont regular;
  final PdfFont bold;
  late PdfGraphics g;
  double y = _pageTop;

  _Canvas(this.doc, this.regular, this.bold);

  void newPage() {
    g = PdfPage(doc, pageFormat: PdfPageFormat.a4).getGraphics();
    y = _pageTop;
  }

  double width(PdfFont font, double size, String s) => font.stringMetrics(s).advanceWidth * size;

  void text(PdfFont font, double size, double x, double baseline, String s) {
    if (s.isEmpty) return;
    g.setFillColor(PdfColors.black);
    g.drawString(font, size, s, x, baseline);
  }

  void rightText(PdfFont font, double size, double rightEdge, double baseline, String s) =>
      text(font, size, rightEdge - width(font, size, s), baseline, s);

  void centeredText(PdfFont font, double size, double left, double w, double baseline, String s) =>
      text(font, size, left + (w - width(font, size, s)) / 2, baseline, s);

  void hline(double x1, double x2, double lineY, double lineWidth, double gray) {
    g.setStrokeColor(PdfColor(gray, gray, gray));
    g.setLineWidth(lineWidth);
    g.moveTo(x1, lineY);
    g.lineTo(x2, lineY);
    g.strokePath();
  }

  void vline(double x, double y1, double y2, double lineWidth) {
    g.setStrokeColor(PdfColor(_lineGray, _lineGray, _lineGray));
    g.setLineWidth(lineWidth);
    g.moveTo(x, y1);
    g.lineTo(x, y2);
    g.strokePath();
  }

  List<String> wrap(PdfFont font, double size, String s, double maxWidth) {
    final lines = <String>[];
    var line = '';
    for (final word in s.split(RegExp(r'\s+')).where((w) => w.isNotEmpty)) {
      final candidate = line.isEmpty ? word : '$line $word';
      if (width(font, size, candidate) > maxWidth && line.isNotEmpty) {
        lines.add(line);
        line = word;
      } else {
        line = candidate;
      }
    }
    if (line.isNotEmpty) lines.add(line);
    return lines.isEmpty ? [''] : lines;
  }
}
