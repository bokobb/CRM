import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../models/counterparty.dart';
import '../models/ingredient.dart';
import '../models/purchase.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/searchable_app_bar.dart';
import 'purchase_invoice_screen.dart';

/// Закупки сырья у контрагентов — см. PurchaseService на бэкенде. Один контрагент может
/// привезти сразу несколько разных товаров за один раз (см. Purchase.items).
class PurchasesScreen extends StatefulWidget {
  const PurchasesScreen({super.key});

  @override
  State<PurchasesScreen> createState() => _PurchasesScreenState();
}

class _PurchasesScreenState extends State<PurchasesScreen> {
  final _api = ApiClient();
  late Future<List<Purchase>> _future;
  String _query = '';

  /// По умолчанию — сегодня (см. DateFilterBar), как и на остальных списках.
  DateTimeRange _range = todayRange();

  /// Закупки, для которых прямо сейчас идёт ручная пересинхронизация с 1С (см. _syncOneC) —
  /// чтобы показать спиннер именно на нужной строке списка, а не блокировать весь экран.
  final Set<int> _syncingIds = {};

  @override
  void initState() {
    super.initState();
    _future = _api.getPurchases();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getPurchases());
    await _future;
  }

  /// Повторная ручная синхронизация закупки с 1С — на случай, если автоматическая (сразу при
  /// сохранении) не сработала (см. ProductionRequestDetailScreen._syncOneC — тот же приём).
  Future<void> _syncOneC(Purchase p) async {
    setState(() => _syncingIds.add(p.id));
    try {
      await _api.syncPurchaseOneC(p.id);
      await _refresh();
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Закупка №${p.id} синхронизирована с 1С')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Не удалось синхронизировать: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _syncingIds.remove(p.id));
    }
  }

  void _onRangeChanged(DateTimeRange range) => setState(() => _range = range);

  List<Purchase> _filter(List<Purchase> purchases) {
    final end = _range.end.add(const Duration(days: 1));
    return purchases
        .where((p) => !p.purchaseDate.isBefore(_range.start) && p.purchaseDate.isBefore(end))
        .where((p) =>
            _query.isEmpty ||
            p.counterpartyName.toLowerCase().contains(_query.toLowerCase()) ||
            p.items.any((i) => i.ingredientName.toLowerCase().contains(_query.toLowerCase())))
        .toList();
  }

  /// Фото накладной поставщика — на весь экран, с масштабом и «Поделиться».
  void _showInvoicePhoto(Purchase p) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => InvoicePhotoScreen(
        imageUrl: p.imageUrl!,
        title: '${p.counterpartyName} · ${formatDate(p.purchaseDate)}',
      ),
    ));
  }

  /// Свайп карточки влево — предпросмотр приходной накладной (PDF с backend) и «Поделиться».
  void _showInvoice(Purchase p) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => PurchaseInvoiceScreen(purchase: p)));
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
        hintText: 'Сырьё или контрагент...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/purchases'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(
            child: RefreshIndicator(
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
                      final itemsSummary = p.items
                          .map((i) => '${i.ingredientName} × ${formatQty(i.quantity)} ${i.ingredientUnit}')
                          .join(', ');
                      final syncing = _syncingIds.contains(p.id);
                      final tile = AppListTile(
                        leadingIcon: Icons.local_shipping_outlined,
                        leadingColor: const Color(0xFFD9A73F),
                        title: p.counterpartyName,
                        subtitle: '$itemsSummary · ${formatDate(p.purchaseDate)}'
                            '${p.oneCSyncError != null ? ' · не синхронизировано с 1С' : ''}',
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (p.imageUrl != null) ...[
                              const Icon(Icons.photo_camera_outlined, size: 18, color: Colors.grey),
                              const SizedBox(width: 6),
                            ],
                            if (p.totalSum > 0) ...[
                              Text(formatMoney(p.totalSum)),
                              const SizedBox(width: 6),
                            ],
                            if (syncing)
                              const SizedBox(
                                height: 18,
                                width: 18,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            else if (p.oneCSyncError != null)
                              IconButton(
                                icon: const Icon(Icons.sync_problem, color: Colors.red),
                                tooltip: '${p.oneCSyncError}\nНажмите, чтобы повторить',
                                onPressed: () => _syncOneC(p),
                              )
                            else if (p.oneCDocumentNumber != null)
                              Icon(Icons.check_circle_outline, color: const Color(0xFF4C8A5E), size: 20,
                                  semanticLabel: 'В 1С №${p.oneCDocumentNumber}'),
                          ],
                        ),
                        onTap: p.imageUrl != null ? () => _showInvoicePhoto(p) : null,
                      );
                      return Dismissible(
                        key: ValueKey(('invoice', p.id)),
                        direction: DismissDirection.endToStart,
                        dismissThresholds: const {DismissDirection.endToStart: 0.35},
                        background: Padding(
                          // Карточка (AppListTile) сама отступает снизу на 10 — фон повторяет её форму.
                          padding: const EdgeInsets.only(bottom: 10),
                          child: Container(
                            alignment: Alignment.centerRight,
                            padding: const EdgeInsets.symmetric(horizontal: 22),
                            decoration: BoxDecoration(
                              color: const Color(0xFFD9A73F),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(Icons.receipt_long_outlined, color: Colors.white),
                                SizedBox(width: 8),
                                Text(
                                  'Накладная',
                                  style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16),
                                ),
                              ],
                            ),
                          ),
                        ),
                        confirmDismiss: (_) async {
                          _showInvoice(p);
                          return false;
                        },
                        child: tile,
                      );
                    },
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Одна позиция закупки в форме — до сохранения товар может быть ещё не выбран.
class _PurchaseItemRow {
  Ingredient? ingredient;
  final quantity = TextEditingController();
  final totalPrice = TextEditingController();
}

class _PurchaseForm extends StatefulWidget {
  const _PurchaseForm();

  @override
  State<_PurchaseForm> createState() => _PurchaseFormState();
}

class _PurchaseFormState extends State<_PurchaseForm> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  late Future<(List<Ingredient>, List<Counterparty>)> _future;
  Counterparty? _counterparty;
  final List<_PurchaseItemRow> _items = [_PurchaseItemRow()];
  DateTime _date = DateTime.now();
  bool _saving = false;
  String? _error;
  XFile? _pickedImage;
  Uint8List? _pickedImageBytes;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(List<Ingredient>, List<Counterparty>)> _load() async {
    final results = await Future.wait([_api.getIngredients(), _api.getCounterparties()]);
    return (results[0] as List<Ingredient>, results[1] as List<Counterparty>);
  }

  void _addItemRow() => setState(() => _items.add(_PurchaseItemRow()));

  void _removeItemRow(_PurchaseItemRow row) => setState(() => _items.remove(row));

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
    if (!_formKey.currentState!.validate() || _counterparty == null || _items.any((r) => r.ingredient == null)) {
      setState(() => _error = 'Заполните все поля');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final items = _items
          .map((r) => (
                r.ingredient!.id,
                double.parse(r.quantity.text.replaceAll(',', '.')),
                r.totalPrice.text.trim().isEmpty ? null : double.tryParse(r.totalPrice.text.replaceAll(',', '.')),
              ))
          .toList();
      final purchase = await _api.createPurchase(
        counterpartyId: _counterparty!.id,
        items: items,
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
      child: FutureBuilder<(List<Ingredient>, List<Counterparty>)>(
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
                onRetry: () => setState(() => _future = _load()),
              ),
            );
          }
          final (ingredients, counterparties) = snapshot.data!;
          return Form(
            key: _formKey,
            child: SingleChildScrollView(
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
                  Autocomplete<Counterparty>(
                    displayStringForOption: (c) => c.name,
                    optionsBuilder: (value) {
                      if (value.text.isEmpty) return const Iterable.empty();
                      final q = value.text.toLowerCase();
                      return counterparties.where((c) => c.name.toLowerCase().contains(q));
                    },
                    onSelected: (c) => setState(() => _counterparty = c),
                    fieldViewBuilder: (context, controller, focusNode, onSubmit) {
                      if (_counterparty != null && controller.text != _counterparty!.name) {
                        controller.text = _counterparty!.name;
                      }
                      return TextFormField(
                        controller: controller,
                        focusNode: focusNode,
                        decoration: InputDecoration(
                          labelText: 'Поставщик',
                          hintText: counterparties.isEmpty ? 'Сначала добавьте контрагента на вебе' : 'Начните вводить название...',
                        ),
                        onChanged: (v) {
                          if (_counterparty != null && v != _counterparty!.name) {
                            setState(() => _counterparty = null);
                          }
                        },
                        validator: (v) => _counterparty == null ? 'Выберите поставщика из списка' : null,
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
                              for (final c in options)
                                ListTile(dense: true, title: Text(c.name), onTap: () => onSelected(c)),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text('Товары', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 8),
                  for (final row in _items)
                    KeyedSubtree(
                      key: ObjectKey(row),
                      child: Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: _buildItemRow(ingredients, row),
                      ),
                    ),
                  OutlinedButton.icon(
                    onPressed: _addItemRow,
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('Добавить товар'),
                  ),
                  const SizedBox(height: 16),
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
            ),
          );
        },
      ),
    );
  }

  Widget _buildItemRow(List<Ingredient> ingredients, _PurchaseItemRow row) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Autocomplete<Ingredient>(
                displayStringForOption: (i) => i.name,
                optionsBuilder: (value) {
                  if (value.text.isEmpty) return const Iterable.empty();
                  final q = value.text.toLowerCase();
                  return ingredients.where((i) => i.name.toLowerCase().contains(q));
                },
                onSelected: (i) => setState(() => row.ingredient = i),
                fieldViewBuilder: (context, controller, focusNode, onSubmit) {
                  if (row.ingredient != null && controller.text != row.ingredient!.name) {
                    controller.text = row.ingredient!.name;
                  }
                  return TextFormField(
                    controller: controller,
                    focusNode: focusNode,
                    decoration: const InputDecoration(labelText: 'Сырьё', hintText: 'Начните вводить название...'),
                    onChanged: (v) {
                      if (row.ingredient != null && v != row.ingredient!.name) {
                        setState(() => row.ingredient = null);
                      }
                    },
                    validator: (v) => row.ingredient == null ? 'Выберите сырьё из списка' : null,
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
            ),
            if (_items.length > 1)
              IconButton(
                icon: const Icon(Icons.close),
                tooltip: 'Убрать позицию',
                onPressed: () => _removeItemRow(row),
              ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: TextFormField(
                controller: row.quantity,
                decoration: const InputDecoration(labelText: 'Количество'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                validator: (v) =>
                    (v == null || double.tryParse(v.replaceAll(',', '.')) == null) ? 'Введите число' : null,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextFormField(
                controller: row.totalPrice,
                decoration: const InputDecoration(labelText: 'Сумма, ₸ (необязательно)'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
