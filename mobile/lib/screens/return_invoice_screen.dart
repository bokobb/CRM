import 'package:flutter/material.dart';
import 'package:printing/printing.dart';
import 'package:share_plus/share_plus.dart';

import '../models/product_return.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';

/// Накладная по возврату (свайп возврата вправо на «Возвратах»): предпросмотр и «Поделиться»
/// сверху. PDF строит backend по макету 1С — тот же блок «Возврат от покупателя», что в накладной
/// заказа, только отдельным файлом.
class ReturnInvoiceScreen extends StatefulWidget {
  final ProductReturn productReturn;

  const ReturnInvoiceScreen({super.key, required this.productReturn});

  @override
  State<ReturnInvoiceScreen> createState() => _ReturnInvoiceScreenState();
}

class _ReturnInvoiceScreenState extends State<ReturnInvoiceScreen> {
  final _api = ApiClient();
  late Future<InvoiceFile> _future;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<InvoiceFile> _load() => _api.getReturnInvoiceFile(widget.productReturn.id);

  Future<void> _share(InvoiceFile file) async {
    await Share.shareXFiles(
      [XFile.fromData(file.bytes, mimeType: file.mimeType, name: file.filename)],
      fileNameOverrides: [file.filename],
    );
  }

  Widget _preview(InvoiceFile file) => PdfPreview(
        build: (_) async => file.bytes,
        useActions: false,
        allowPrinting: false,
        allowSharing: false,
        canChangePageFormat: false,
        canChangeOrientation: false,
        canDebug: false,
        pdfFileName: file.filename,
      );

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<InvoiceFile>(
      future: _future,
      builder: (context, snapshot) {
        final file = snapshot.connectionState == ConnectionState.done ? snapshot.data : null;
        return Scaffold(
          appBar: AppBar(
            title: Text('Возврат · ${widget.productReturn.clientName}'),
            actions: [
              if (file != null)
                IconButton(
                  icon: const Icon(Icons.share_outlined),
                  tooltip: 'Поделиться',
                  onPressed: () => _share(file),
                ),
            ],
          ),
          body: switch (snapshot.connectionState) {
            ConnectionState.done when snapshot.hasError => ErrorView(
                error: snapshot.error.toString(),
                onRetry: () => setState(() => _future = _load()),
              ),
            ConnectionState.done => _preview(file!),
            _ => const Center(child: CircularProgressIndicator()),
          },
        );
      },
    );
  }
}
