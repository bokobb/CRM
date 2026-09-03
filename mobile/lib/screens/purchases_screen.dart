import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../models/ingredient.dart';
import '../models/purchase.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/searchable_app_bar.dart';

/// Закупки сырья у поставщиков — см. PurchaseService на бэкенде. Отдельный, более
/// подробный учёт (поставщик, сумма, дата) поверх обычного прихода на склад.
class PurchasesScreen extends StatefulWidget {
  const PurchasesScreen({super.key});

  @override
  State<PurchasesScreen> createState() => _PurchasesScreenState();
}

class _PurchasesScreenState extends State<PurchasesScreen> {
  final _api = ApiClient();
  late Future<List<Purchase>> _future;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _future = _api.getPurchases();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getPurchases());
    await _future;
  }

  List<Purchase> _filter(List<Purchase> purchases) {
    if (_query.isEmpty) return purchases;
    final q = _query.toLowerCase();
    return purchases
        .where((p) => p.ingredientName.toLowerCase().contains(q) || p.supplier.toLowerCase().contains(q))
        .toList();
  }

  void _showInvoicePhoto(BuildContext context, String imageUrl) {
    showDialog<void>(
      context: context,
      builder: (context) => Dialog(
        insetPadding: const EdgeInsets.all(16),
        child: Stack(
          alignment: Alignment.topRight,
          children: [
            InteractiveViewer(child: Image.network(imageUrl)),
            IconButton(
              icon: const Icon(Icons.close, color: Colors.white),
              style: IconButton.styleFrom(backgroundColor: Colors.black45),
              onPressed: () => Navigator.pop(context),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _openForm() async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) => const _PurchaseForm(),
    );
    if (saved == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Покупки',
        hintText: 'Сырьё или поставщик...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/purchases'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Purchase>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final purchases = _filter(snapshot.data!);
            if (purchases.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: const EdgeInsets.all(32),
                  child: Center(
                    child: Text(_query.isEmpty ? 'Закупок пока нет' : 'Ничего не найдено',
                        style: const TextStyle(color: Colors.grey)),
                  ),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              itemCount: purchases.length,
              itemBuilder: (context, index) {
                final p = purchases[index];
                return AppListTile(
                  leadingIcon: Icons.local_shipping_outlined,
                  leadingColor: const Color(0xFFD9A73F),
                  title: p.ingredientName,
                  subtitle: '${p.supplier} · ${formatQty(p.quantity)} ${p.ingredientUnit} · ${formatDate(p.purchaseDate)}',
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (p.imageUrl != null) ...[
                        const Icon(Icons.photo_camera_outlined, size: 18, color: Colors.grey),
                        const SizedBox(width: 6),
                      ],
                      if (p.totalPrice != null) Text(formatMoney(p.totalPrice!)),
                    ],
                  ),
                  onTap: p.imageUrl != null ? () => _showInvoicePhoto(context, p.imageUrl!) : null,
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _PurchaseForm extends StatefulWidget {
  const _PurchaseForm();

  @override
  State<_PurchaseForm> createState() => _PurchaseFormState();
}

class _PurchaseFormState extends State<_PurchaseForm> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _quantity = TextEditingController();
  final _supplier = TextEditingController();
  final _totalPrice = TextEditingController();
  late Future<List<Ingredient>> _future;
  Ingredient? _ingredient;
  DateTime _date = DateTime.now();
  bool _saving = false;
  String? _error;
  XFile? _pickedImage;
  Uint8List? _pickedImageBytes;

  @override
  void initState() {
    super.initState();
    _future = _api.getIngredients();
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now(),
    );
    if (picked != null) setState(() => _date = picked);
  }

  Future<void> _pickImage(ImageSource source) async {
    final picked = await ImagePicker().pickImage(source: source, imageQuality: 85, maxWidth: 1600);
    if (picked == null) return;
    final bytes = await picked.readAsBytes();
    setState(() {
      _pickedImage = picked;
      _pickedImageBytes = bytes;
    });
  }

  Future<void> _choosePhotoSource() async {
    final source = await showModalBottomSheet<ImageSource>(
      context: context,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined),
              title: const Text('Сделать фото'),
              onTap: () => Navigator.pop(context, ImageSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('Выбрать из галереи'),
              onTap: () => Navigator.pop(context, ImageSource.gallery),
            ),
          ],
        ),
      ),
    );
    if (source != null) await _pickImage(source);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate() || _ingredient == null) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final qty = double.parse(_quantity.text.replaceAll(',', '.'));
      final priceText = _totalPrice.text.trim();
      final price = priceText.isEmpty ? null : double.tryParse(priceText.replaceAll(',', '.'));
      final purchase = await _api.createPurchase(
        ingredientId: _ingredient!.id,
        quantity: qty,
        supplier: _supplier.text.trim(),
        totalPrice: price,
        purchaseDate: _date,
      );
      if (_pickedImageBytes != null) {
        await _api.uploadPurchaseImage(
          purchase.id,
          bytes: _pickedImageBytes!,
          filename: _pickedImage!.name,
          contentType: _pickedImage!.mimeType,
        );
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        bottom: 16 + MediaQuery.of(context).viewInsets.bottom,
      ),
      child: FutureBuilder<List<Ingredient>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const SizedBox(
              height: 120,
              child: Center(child: CircularProgressIndicator()),
            );
          }
          if (snapshot.hasError) {
            return SizedBox(
              height: 120,
              child: ErrorView(
                error: snapshot.error.toString(),
                onRetry: () => setState(() => _future = _api.getIngredients()),
              ),
            );
          }
          final ingredients = snapshot.data!;
          return Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Новая закупка', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 16),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),
                Autocomplete<Ingredient>(
                  displayStringForOption: (i) => i.name,
                  optionsBuilder: (value) {
                    if (value.text.isEmpty) return const Iterable.empty();
                    final q = value.text.toLowerCase();
                    return ingredients.where((i) => i.name.toLowerCase().contains(q));
                  },
                  onSelected: (i) => setState(() => _ingredient = i),
                  fieldViewBuilder: (context, controller, focusNode, onSubmit) {
                    if (_ingredient != null && controller.text != _ingredient!.name) {
                      controller.text = _ingredient!.name;
                    }
                    return TextFormField(
                      controller: controller,
                      focusNode: focusNode,
                      decoration: const InputDecoration(labelText: 'Сырьё', hintText: 'Начните вводить название...'),
                      onChanged: (v) {
                        if (_ingredient != null && v != _ingredient!.name) {
                          setState(() => _ingredient = null);
                        }
                      },
                      validator: (v) => _ingredient == null ? 'Выберите сырьё из списка' : null,
                    );
                  },
                  optionsViewBuilder: (context, onSelected, options) => Align(
                    alignment: Alignment.topLeft,
                    child: Material(
                      elevation: 4,
                      borderRadius: BorderRadius.circular(8),
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxHeight: 260, minWidth: 260),
                        child: ListView(
                          padding: EdgeInsets.zero,
                          shrinkWrap: true,
                          children: [
                            for (final i in options)
                              ListTile(
                                dense: true,
                                title: Text(i.name),
                                subtitle: Text('${formatQty(i.quantityInStock)} ${i.unit}'),
                                onTap: () => onSelected(i),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _quantity,
                  decoration: const InputDecoration(labelText: 'Количество'),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  validator: (v) =>
                      (v == null || double.tryParse(v.replaceAll(',', '.')) == null) ? 'Введите число' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _supplier,
                  decoration: const InputDecoration(labelText: 'Поставщик'),
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите поставщика' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _totalPrice,
                  decoration: const InputDecoration(labelText: 'Сумма закупки, ₸ (необязательно)'),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                ),
                const SizedBox(height: 12),
                InkWell(
                  onTap: _pickDate,
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: 'Дата'),
                    child: Text(formatDate(_date)),
                  ),
                ),
                const SizedBox(height: 16),
                if (_pickedImageBytes != null) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.memory(_pickedImageBytes!, height: 160, fit: BoxFit.cover),
                  ),
                  const SizedBox(height: 8),
                ],
                OutlinedButton.icon(
                  onPressed: _choosePhotoSource,
                  icon: const Icon(Icons.photo_camera_outlined, size: 18),
                  label: Text(_pickedImageBytes == null ? 'Сфотографировать накладную' : 'Заменить фото'),
                ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Сохранить'),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
