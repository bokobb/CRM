import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:printing/printing.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// Дневной отчёт "сколько сегодня изготовили" — зав. склада ГП открывает по кнопке "Отчёт" в
/// меню, видит список товаров, оприходованных за выбранный день, и может скачать/отправить PDF
/// (сам PDF генерируется на сервере — ProductionReportPdfService).
class ProductionReportScreen extends StatefulWidget {
  const ProductionReportScreen({super.key});

  @override
  State<ProductionReportScreen> createState() => _ProductionReportScreenState();
}

class _ProductionReportScreenState extends State<ProductionReportScreen> {
  final _api = ApiClient();
  late DateTime _date;
  late Future<DailyProductionReport> _future;
  bool _pdfInProgress = false;

  @override
  void initState() {
    super.initState();
    _date = DateTime.now();
    _load();
  }

  void _load() {
    _future = _api.getDailyProductionReport(date: _date);
  }

  Future<void> _refresh() async {
    setState(_load);
    await _future;
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
    );
    if (picked != null) {
      setState(() {
        _date = picked;
        _load();
      });
    }
  }

  Future<void> _sharePdf() async {
    setState(() => _pdfInProgress = true);
    try {
      final bytes = await _api.getDailyProductionReportPdf(date: _date);
      final filename =
          'otchet_${_date.toIso8601String().split('T').first}.pdf';
      await Printing.sharePdf(bytes: Uint8List.fromList(bytes), filename: filename);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
      }
    } finally {
      if (mounted) setState(() => _pdfInProgress = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Отчёт по оприходованию')),
      drawer: const AppDrawer(currentRoute: '/production-requests/report'),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: Row(
                children: [
                  Expanded(
                    child: InkWell(
                      onTap: _pickDate,
                      child: Row(
                        children: [
                          const Icon(Icons.calendar_today_outlined, size: 18),
                          const SizedBox(width: 8),
                          Text(formatDate(_date), style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                          const SizedBox(width: 4),
                          const Icon(Icons.arrow_drop_down),
                        ],
                      ),
                    ),
                  ),
                  OutlinedButton.icon(
                    onPressed: _pdfInProgress ? null : _sharePdf,
                    icon: _pdfInProgress
                        ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.picture_as_pdf_outlined, size: 18),
                    label: const Text('PDF'),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: FutureBuilder<DailyProductionReport>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final items = snapshot.data!.items;
                  if (items.isEmpty) {
                    return ListView(children: const [
                      Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(
                          child: Text('За этот день ничего не оприходовано', style: TextStyle(color: Colors.grey)),
                        ),
                      ),
                    ]);
                  }
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: items.length,
                    itemBuilder: (context, index) {
                      final it = items[index];
                      final deviationColor = it.deviation > 0
                          ? Colors.green.shade700
                          : (it.deviation < 0 ? Colors.red.shade700 : Colors.grey);
                      final deviationText =
                          '${it.deviation > 0 ? '+' : ''}${formatQty(it.deviation)} ${it.unit}';
                      return AppListTile(
                        leadingIcon: Icons.inventory_2_outlined,
                        leadingColor: const Color(0xFFD9A73F),
                        title: it.productName,
                        subtitle: '${formatQty(it.batches)} замес. · норма ${formatQty(it.norm)} ${it.unit} · '
                            'оприходований: ${it.receivedCount}',
                        trailing: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(
                              '${formatQty(it.received)} ${it.unit}',
                              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                            ),
                            Text(
                              deviationText,
                              style: TextStyle(fontSize: 12.5, color: deviationColor, fontWeight: FontWeight.w600),
                            ),
                          ],
                        ),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
