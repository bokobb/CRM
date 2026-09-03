import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/receipt_card.dart';
import '../widgets/status_badge.dart';
import '../widgets/success_feedback.dart';

class ProductionRequestDetailScreen extends StatefulWidget {
  final int requestId;

  const ProductionRequestDetailScreen({super.key, required this.requestId});

  @override
  State<ProductionRequestDetailScreen> createState() => _ProductionRequestDetailScreenState();
}

class _ProductionRequestDetailScreenState extends State<ProductionRequestDetailScreen> {
  final _api = ApiClient();
  late Future<ProductionRequest> _future;
  final Set<int> _checked = {};
  final _receivedQty = TextEditingController();
  bool _actionInProgress = false;
  String? _error;

  String? get _role => ApiClient.currentSession?.role;
  bool get _canCollect => _role == 'ADMIN' || _role == 'WAREHOUSE_RAW';
  bool get _canReceive => _role == 'ADMIN' || _role == 'WAREHOUSE_FG';

  @override
  void initState() {
    super.initState();
    _future = _api.getProductionRequest(widget.requestId);
  }

  Future<void> _refresh() async {
    setState(() {
      _checked.clear();
      _future = _api.getProductionRequest(widget.requestId);
    });
    await _future;
  }

  Future<void> _collect(ProductionRequest request) async {
    setState(() {
      _actionInProgress = true;
      _error = null;
    });
    try {
      await _api.collectProductionRequest(widget.requestId, _checked.toList());
      if (mounted) await playSuccessFeedback(context);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _actionInProgress = false);
    }
  }

  Future<void> _receive(ProductionRequest request) async {
    final qty = double.tryParse(_receivedQty.text.trim().replaceAll(',', '.'));
    if (qty == null || qty <= 0) {
      setState(() => _error = 'Введите количество');
      return;
    }
    setState(() {
      _actionInProgress = true;
      _error = null;
    });
    try {
      await _api.receiveProductionRequest(widget.requestId, qty);
      if (mounted) await playSuccessFeedback(context);
      await _refresh();
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _actionInProgress = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Заявка №${widget.requestId}')),
      body: FutureBuilder<ProductionRequest>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
          }
          final r = snapshot.data!;
          return RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(r.productName, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                          const SizedBox(height: 4),
                          Text(
                            '${formatQty(r.batches)} замес. · ${r.createdBy} · ${formatDateTime(r.createdAt)}',
                            style: const TextStyle(color: Colors.grey, fontSize: 13),
                          ),
                        ],
                      ),
                    ),
                    StatusBadge(status: r.status, label: r.statusLabel),
                  ],
                ),
                if (r.status == 'COLLECTED' || r.status == 'RECEIVED') ...[
                  const SizedBox(height: 10),
                  Text('Собрал: ${r.collectedBy} · ${formatDateTime(r.collectedAt!)}',
                      style: const TextStyle(color: Colors.grey, fontSize: 13)),
                ],
                if (r.status == 'RECEIVED') ...[
                  const SizedBox(height: 4),
                  Text(
                    'Оприходовано ${formatQty(r.receivedQuantity!)} ${r.productUnit ?? 'шт'} — '
                    '${r.receivedBy} · ${formatDateTime(r.receivedAt!)}',
                    style: const TextStyle(color: Color(0xFF4C8A5E), fontWeight: FontWeight.w600, fontSize: 13),
                  ),
                ],
                const SizedBox(height: 18),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),

                if (r.status == 'NEW' && _canCollect) ...[
                  ReceiptCard(
                    title: 'Состав заявки',
                    icon: Icons.checklist_rounded,
                    children: [
                      for (int i = 0; i < r.items.length; i++)
                        ReceiptRow(
                          index: i + 1,
                          name: r.items[i].ingredientName,
                          qty: '${formatQty(r.items[i].requiredQuantity)} ${r.items[i].ingredientUnit}',
                          checkbox: _checked.contains(r.items[i].id),
                          onCheckboxChanged: (v) => setState(() {
                            if (v == true) {
                              _checked.add(r.items[i].id);
                            } else {
                              _checked.remove(r.items[i].id);
                            }
                          }),
                        ),
                    ],
                    footer: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Отмечено ${_checked.length} из ${r.items.length}',
                          style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                        ),
                        if (_checked.length == r.items.length)
                          const Icon(Icons.check_circle, size: 16, color: Color(0xFF4C8A5E)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: (_actionInProgress || _checked.length != r.items.length) ? null : () => _collect(r),
                    child: _actionInProgress
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Собрано'),
                  ),
                ] else ...[
                  ReceiptCard(
                    title: 'Состав заявки',
                    children: [
                      for (int i = 0; i < r.items.length; i++)
                        ReceiptRow(
                          index: i + 1,
                          name: r.items[i].ingredientName,
                          qty: '${formatQty(r.items[i].requiredQuantity)} ${r.items[i].ingredientUnit}',
                        ),
                    ],
                    footer: Text(
                      'Позиций: ${r.items.length}',
                      style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                    ),
                  ),
                ],

                if (r.status == 'COLLECTED' && _canReceive) ...[
                  const Divider(height: 32),
                  const Text('Оприходовать готовую продукцию', style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 10),
                  TextField(
                    controller: _receivedQty,
                    decoration: const InputDecoration(labelText: 'Сколько фактически получили'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: _actionInProgress ? null : () => _receive(r),
                    child: _actionInProgress
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Оприходовать'),
                  ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }
}
