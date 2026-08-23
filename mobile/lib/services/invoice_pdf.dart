import 'dart:typed_data';

import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';

import '../models/order.dart';
import '../widgets/format.dart';

/// Печатная накладная в духе 1С/ТОРГ-12: шапка, таблица позиций (количество — крупным
/// жирным шрифтом, чтобы кладовщик не перепутал при сверке с бумагой), итог и подписи.
Future<Uint8List> buildInvoicePdf(Order o) async {
  final regular = await PdfGoogleFonts.notoSansRegular();
  final bold = await PdfGoogleFonts.notoSansBold();
  final theme = pw.ThemeData.withFont(base: regular, bold: bold);
  final doc = pw.Document();

  doc.addPage(
    pw.Page(
      theme: theme,
      pageFormat: PdfPageFormat.a4,
      margin: const pw.EdgeInsets.all(28),
      build: (context) {
        return pw.Column(
          crossAxisAlignment: pw.CrossAxisAlignment.start,
          children: [
            pw.Text('Накладная № ${o.id} от ${formatDate(o.createdAt)}',
                style: pw.TextStyle(fontSize: 16, fontWeight: pw.FontWeight.bold)),
            pw.SizedBox(height: 4),
            pw.Text('Грузополучатель: ${o.clientName}', style: const pw.TextStyle(fontSize: 11)),
            if (o.dueDate != null)
              pw.Text('Срок отгрузки: ${formatDate(o.dueDate!)}', style: const pw.TextStyle(fontSize: 11)),
            pw.SizedBox(height: 14),
            pw.Table(
              border: pw.TableBorder.all(color: PdfColors.grey600, width: 0.6),
              columnWidths: const {
                0: pw.FlexColumnWidth(0.6),
                1: pw.FlexColumnWidth(3.4),
                2: pw.FlexColumnWidth(1.3),
                3: pw.FlexColumnWidth(1.3),
                4: pw.FlexColumnWidth(1.5),
              },
              children: [
                pw.TableRow(
                  decoration: const pw.BoxDecoration(color: PdfColors.grey300),
                  children: [
                    _headerCell('№'),
                    _headerCell('Наименование'),
                    _headerCell('Кол-во'),
                    _headerCell('Цена'),
                    _headerCell('Сумма'),
                  ],
                ),
                for (final (i, item) in o.items.indexed)
                  pw.TableRow(
                    children: [
                      _cell('${i + 1}'),
                      _cell(item.productName),
                      // Количество — крупнее и жирнее остальных колонок, это то, что
                      // проверяют на складе физически, ошибка здесь дороже всего.
                      pw.Padding(
                        padding: const pw.EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                        child: pw.Text(formatQty(item.quantity),
                            style: pw.TextStyle(fontSize: 13, fontWeight: pw.FontWeight.bold)),
                      ),
                      _cell(formatMoney(item.price)),
                      _cell(formatMoney(item.price * item.quantity)),
                    ],
                  ),
              ],
            ),
            pw.SizedBox(height: 8),
            pw.Align(
              alignment: pw.Alignment.centerRight,
              child: pw.Text('Итого: ${formatMoney(o.totalPrice)}',
                  style: pw.TextStyle(fontSize: 13, fontWeight: pw.FontWeight.bold)),
            ),
            if (o.vehicleNumber != null) ...[
              pw.SizedBox(height: 14),
              pw.Text('ТС: ${o.vehicleNumber}', style: const pw.TextStyle(fontSize: 11)),
              if (o.shippedAt != null)
                pw.Text(
                    'Отгрузил: ${o.shippedByName ?? "—"} · ${formatDateTime(o.shippedAt!)}',
                    style: const pw.TextStyle(fontSize: 11)),
            ],
            pw.Spacer(),
            pw.Row(
              mainAxisAlignment: pw.MainAxisAlignment.spaceBetween,
              children: [
                pw.Text('Отпустил: _______________________', style: const pw.TextStyle(fontSize: 11)),
                pw.Text('Получил: _______________________', style: const pw.TextStyle(fontSize: 11)),
              ],
            ),
          ],
        );
      },
    ),
  );

  return doc.save();
}

pw.Widget _headerCell(String text) => pw.Padding(
      padding: const pw.EdgeInsets.symmetric(horizontal: 4, vertical: 6),
      child: pw.Text(text, style: pw.TextStyle(fontSize: 10, fontWeight: pw.FontWeight.bold)),
    );

pw.Widget _cell(String text) => pw.Padding(
      padding: const pw.EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      child: pw.Text(text, style: const pw.TextStyle(fontSize: 10)),
    );
