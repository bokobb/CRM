import 'package:flutter/material.dart';
import 'package:printing/printing.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/order.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// «Объём и масса» по заказу (свайп заказа вправо на «Заказах»): предпросмотр бланка расходной
/// накладной с коробками, объёмом и массой (PDF строит backend) и «Поделиться» сверху. У товаров
/// с двумя упаковками («Коржики (12/24 шт)») сверху переключатель «по 12 | по 24» — это решает
/// клиент; выбор запоминается для клиента на этом устройстве.
class PackingListScreen extends StatefulWidget {
  final int orderId;

  const PackingListScreen({super.key, required this.orderId});

  @override
  State<PackingListScreen> createState() => _PackingListScreenState();
}

class _PackingListScreenState extends State<PackingListScreen> {
  final _api = ApiClient();
  Order? _order;

  /// Товары заказа с двумя упаковками: productId → [товар, варианты].
  final Map<int, (String, List<double>)> _choices = {};
  final Map<int, double> _pack = {};
  Future<InvoiceFile>? _file;
  Object? _loadError;

  @override
  void initState() {
    super.initState();
    _load();
  }

  String _prefKey(int clientId, int productId) => 'packing_${clientId}_$productId';

  Future<void> _load() async {
    setState(() => _loadError = null);
    try {
      final order = await _api.getOrder(widget.orderId);
      final products = {for (final p in await _api.getProducts()) p.id: p};
      final prefs = await SharedPreferences.getInstance();
      _choices.clear();
      _pack.clear();
      for (final item in order.items) {
        final Product? p = products[item.productId];
        final units = p?.packingUnits ?? const <double>[];
        if (units.length < 2 || _choices.containsKey(item.productId)) continue;
        _choices[item.productId] = (item.productName, units);
        final saved = prefs.getDouble(_prefKey(order.clientId, item.productId));
        _pack[item.productId] = saved != null && units.contains(saved) ? saved : units.first;
      }
      setState(() {
        _order = order;
        _file = _api.getPackingFile(widget.orderId, Map.of(_pack));
      });
    } catch (e) {
      setState(() => _loadError = e);
    }
  }

  Future<void> _choose(int productId, double units) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble(_prefKey(_order!.clientId, productId), units);
    setState(() {
      _pack[productId] = units;
      _file = _api.getPackingFile(widget.orderId, Map.of(_pack));
    });
  }

  Future<void> _share(InvoiceFile file) async {
    await Share.shareXFiles(
      [XFile.fromData(file.bytes, mimeType: file.mimeType, name: file.filename)],
      fileNameOverrides: [file.filename],
    );
  }

  Widget _switches() {
    return Container(
      color: context.pal.card,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Упаковка — как решил клиент', style: TextStyle(color: context.pal.muted, fontSize: 12.5)),
          for (final e in _choices.entries)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Row(
                children: [
                  Expanded(child: Text(e.value.$1, maxLines: 2, overflow: TextOverflow.ellipsis)),
                  const SizedBox(width: 8),
                  SegmentedButton<double>(
                    showSelectedIcon: false,
                    segments: [for (final u in e.value.$2) ButtonSegment(value: u, label: Text('по ${formatQty(u)}'))],
                    selected: {_pack[e.key]!},
                    onSelectionChanged: (s) => _choose(e.key, s.first),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<InvoiceFile>(
      future: _file,
      builder: (context, snapshot) {
        final file = snapshot.connectionState == ConnectionState.done ? snapshot.data : null;
        final error = _loadError ?? (snapshot.connectionState == ConnectionState.done ? snapshot.error : null);
        return Scaffold(
          appBar: AppBar(
            title: Text('Объём и масса · №${widget.orderId}'),
            actions: [
              if (file != null)
                IconButton(
                  icon: const Icon(Icons.share_outlined),
                  tooltip: 'Поделиться',
                  onPressed: () => _share(file),
                ),
            ],
          ),
          body: error != null
              ? ErrorView(error: error.toString(), onRetry: _load)
              : Column(
                  children: [
                    if (_choices.isNotEmpty) _switches(),
                    Expanded(
                      child: file == null
                          ? const Center(child: CircularProgressIndicator())
                          : PdfPreview(
                              key: ValueKey(_pack.toString()),
                              build: (_) async => file.bytes,
                              useActions: false,
                              allowPrinting: false,
                              allowSharing: false,
                              canChangePageFormat: false,
                              canChangeOrientation: false,
                              canDebug: false,
                              pdfFileName: file.filename,
                            ),
                    ),
                  ],
                ),
        );
      },
    );
  }
}
