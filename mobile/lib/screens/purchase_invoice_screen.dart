import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:printing/printing.dart';
import 'package:share_plus/share_plus.dart';

import '../models/purchase.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';

/// Предпросмотр приходной накладной по закупке (открывается свайпом карточки влево на экране
/// «Покупки») с кнопкой «Поделиться». Сам PDF строит backend по макету 1С.
class PurchaseInvoiceScreen extends StatefulWidget {
  final Purchase purchase;

  const PurchaseInvoiceScreen({super.key, required this.purchase});

  @override
  State<PurchaseInvoiceScreen> createState() => _PurchaseInvoiceScreenState();
}

class _PurchaseInvoiceScreenState extends State<PurchaseInvoiceScreen> {
  final _api = ApiClient();
  late Future<InvoiceFile> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getPurchaseInvoiceFile(widget.purchase.id);
  }

  Future<void> _share(InvoiceFile file) async {
    await Share.shareXFiles(
      [XFile.fromData(file.bytes, mimeType: file.mimeType, name: file.filename)],
      fileNameOverrides: [file.filename],
    );
  }

  Widget _preview(InvoiceFile file) {
    return PdfPreview(
      build: (_) async => file.bytes,
      useActions: false,
      allowPrinting: false,
      allowSharing: false,
      canChangePageFormat: false,
      canChangeOrientation: false,
      canDebug: false,
      pdfFileName: file.filename,
    );
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<InvoiceFile>(
      future: _future,
      builder: (context, snapshot) {
        final file = snapshot.data;
        return Scaffold(
          appBar: AppBar(
            title: Text('Накладная · ${widget.purchase.counterpartyName}'),
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
                onRetry: () => setState(() => _future = _api.getPurchaseInvoiceFile(widget.purchase.id)),
              ),
            ConnectionState.done => _preview(file!),
            _ => const Center(child: CircularProgressIndicator()),
          },
          floatingActionButton: file == null
              ? null
              : FloatingActionButton.extended(
                  onPressed: () => _share(file),
                  icon: const Icon(Icons.share_outlined),
                  label: const Text('Поделиться'),
                ),
        );
      },
    );
  }
}

/// Фото накладной поставщика на весь экран: масштаб щипком/двойным касанием и «Поделиться».
class InvoicePhotoScreen extends StatefulWidget {
  final String imageUrl;
  final String title;

  const InvoicePhotoScreen({super.key, required this.imageUrl, required this.title});

  @override
  State<InvoicePhotoScreen> createState() => _InvoicePhotoScreenState();
}

class _InvoicePhotoScreenState extends State<InvoicePhotoScreen> {
  final _transform = TransformationController();
  TapDownDetails? _doubleTapDetails;
  bool _sharing = false;

  @override
  void dispose() {
    _transform.dispose();
    super.dispose();
  }

  /// Двойное касание — приблизить в 2.5 раза в точке касания, повторное — вернуть как было.
  void _onDoubleTap() {
    if (_transform.value != Matrix4.identity()) {
      _transform.value = Matrix4.identity();
      return;
    }
    final position = _doubleTapDetails?.localPosition ?? Offset.zero;
    const scale = 2.5;
    _transform.value = Matrix4.identity()
      ..translateByDouble(-position.dx * (scale - 1), -position.dy * (scale - 1), 0, 1)
      ..scaleByDouble(scale, scale, 1, 1);
  }

  Future<void> _share() async {
    setState(() => _sharing = true);
    try {
      final res = await http.get(Uri.parse(widget.imageUrl));
      if (res.statusCode < 200 || res.statusCode >= 300) {
        throw Exception('не удалось загрузить фото (${res.statusCode})');
      }
      final name = Uri.parse(widget.imageUrl).pathSegments.last;
      final mimeType = res.headers['content-type'] ?? 'image/jpeg';
      await Share.shareXFiles([XFile.fromData(res.bodyBytes, mimeType: mimeType, name: name)],
          fileNameOverrides: [name]);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось поделиться: $e')));
    } finally {
      if (mounted) setState(() => _sharing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(widget.title),
        actions: [
          _sharing
              ? const Padding(
                  padding: EdgeInsets.all(14),
                  child: SizedBox(
                      width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)),
                )
              : IconButton(icon: const Icon(Icons.share_outlined), tooltip: 'Поделиться', onPressed: _share),
        ],
      ),
      body: GestureDetector(
        onDoubleTapDown: (d) => _doubleTapDetails = d,
        onDoubleTap: _onDoubleTap,
        child: InteractiveViewer(
          transformationController: _transform,
          minScale: 1,
          maxScale: 6,
          child: SizedBox.expand(
            child: Image.network(
              widget.imageUrl,
              fit: BoxFit.contain,
              loadingBuilder: (context, child, progress) => progress == null
                  ? child
                  : const Center(child: CircularProgressIndicator(color: Colors.white)),
              errorBuilder: (context, error, stack) => const Center(
                child: Text('Не удалось загрузить фото', style: TextStyle(color: Colors.white70)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
