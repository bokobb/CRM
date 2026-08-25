import 'package:flutter/material.dart';

import '../main.dart';
import '../models/product.dart';
import '../models/whatsapp_inbox.dart';
import '../services/api_client.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/product_avatar.dart';
import '../widgets/success_feedback.dart';

/// Уточнение одного сообщения из WhatsApp: для каждой нераспознанной позиции выбираем
/// товар из каталога, подтверждаем — заказ создаётся, а выбор запоминается как синоним
/// (см. WhatsAppOrderIntakeService.resolvePending на бэкенде).
class WhatsAppInboxResolveScreen extends StatefulWidget {
  final WhatsAppInboundMessage message;

  const WhatsAppInboxResolveScreen({super.key, required this.message});

  @override
  State<WhatsAppInboxResolveScreen> createState() => _WhatsAppInboxResolveScreenState();
}

class _WhatsAppInboxResolveScreenState extends State<WhatsAppInboxResolveScreen> {
  final _api = ApiClient();
  late Future<List<Product>> _future;
  final Map<int, Product> _selections = {};
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _api.getProducts();
  }

  Future<void> _pickProduct(WhatsAppInboundItem item, List<Product> products) async {
    final picked = await showModalBottomSheet<Product>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (context) {
        var query = '';
        return StatefulBuilder(
          builder: (context, setSheetState) {
            final filtered = query.isEmpty
                ? products
                : products.where((p) => p.name.toLowerCase().contains(query.toLowerCase())).toList();
            return Padding(
              padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
              child: SizedBox(
                height: MediaQuery.of(context).size.height * 0.75,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                      child: TextField(
                        autofocus: true,
                        decoration: InputDecoration(
                          labelText: 'Товар для "${item.rawProductText}"',
                          prefixIcon: const Icon(Icons.search),
                        ),
                        onChanged: (v) => setSheetState(() => query = v),
                      ),
                    ),
                    Expanded(
                      child: ListView.builder(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        itemCount: filtered.length,
                        itemBuilder: (context, i) {
                          final p = filtered[i];
                          return AppListTile(
                            leading: ProductAvatar(name: p.name, imageUrl: p.imageUrl),
                            title: p.name,
                            subtitle: formatMoney(p.price),
                            onTap: () => Navigator.pop(context, p),
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
    if (picked != null) setState(() => _selections[item.id] = picked);
  }

  Future<void> _confirm(WhatsAppInboundMessage message) async {
    setState(() {
      _saving = true;
      _error = null;
    });
    final resolutions = {for (final e in _selections.entries) e.key: e.value.id};
    try {
      await _api.resolveWhatsAppInbox(message.id, resolutions);
      if (!mounted) return;
      await playSuccessFeedback(context);
      if (!mounted) return;
      Navigator.of(context).pushNamedAndRemoveUntil('/orders', (route) => false);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _saving = false;
      });
    }
  }

  Future<void> _ignore(WhatsAppInboundMessage message) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Игнорировать сообщение?'),
        content: const Text('Заказ создан не будет.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Игнорировать')),
        ],
      ),
    );
    if (confirmed != true) return;
    await _api.ignoreWhatsAppInbox(message.id);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Уточнить заказ')),
      body: FutureBuilder<List<Product>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(
              error: snapshot.error.toString(),
              onRetry: () => setState(() => _future = _api.getProducts()),
            );
          }
          final message = widget.message;
          final products = snapshot.data!;
          final allResolved = message.items
              .where((i) => !i.isMatched)
              .every((i) => _selections.containsKey(i.id));

          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(message.clientName, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
              Text(message.fromPhone, style: const TextStyle(color: Colors.grey)),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
                child: Text(message.rawText),
              ),
              const SizedBox(height: 16),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(_error!, style: const TextStyle(color: Colors.red)),
                ),
              for (final item in message.items)
                Container(
                  margin: const EdgeInsets.only(bottom: 10),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: const Color(0xFFE7E0D8)),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('"${item.rawProductText}" · ${formatQty(item.quantity)}',
                                style: const TextStyle(color: Colors.grey, fontSize: 12.5)),
                            const SizedBox(height: 4),
                            if (item.isMatched)
                              Text(item.productName!, style: const TextStyle(fontWeight: FontWeight.w600))
                            else if (_selections.containsKey(item.id))
                              Text(_selections[item.id]!.name,
                                  style: const TextStyle(fontWeight: FontWeight.w600, color: accent))
                            else
                              const Text('Не выбран товар', style: TextStyle(color: Colors.grey)),
                          ],
                        ),
                      ),
                      if (!item.isMatched)
                        OutlinedButton(
                          onPressed: () => _pickProduct(item, products),
                          child: Text(_selections.containsKey(item.id) ? 'Изменить' : 'Выбрать'),
                        ),
                    ],
                  ),
                ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: FilledButton(
                  onPressed: (_saving || !allResolved) ? null : () => _confirm(message),
                  child: _saving
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Подтвердить и создать заказ'),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: () => _ignore(message),
                  style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                  child: const Text('Игнорировать сообщение'),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}
