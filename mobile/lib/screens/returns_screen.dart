import 'package:flutter/material.dart';

import '../models/client.dart';
import '../models/product.dart';
import '../models/product_return.dart';
import '../services/api_client.dart';
import '../widgets/app_choice_chip.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/date_filter_bar.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';
import '../widgets/searchable_app_bar.dart';

const returnColor = Color(0xFFD9822B);

/// Возвраты испорченного / просроченного товара — см. ProductReturnService на бэкенде.
/// Создаются с нуля (товар мог быть продан когда угодно), сразу уходят в 1С на склад
/// "Испорченные товары"; за заказом закрепляются из списка заказов (см. orders_screen.dart).
class ReturnsScreen extends StatefulWidget {
  const ReturnsScreen({super.key});

  @override
  State<ReturnsScreen> createState() => _ReturnsScreenState();
}

class _ReturnsScreenState extends State<ReturnsScreen> {
  final _api = ApiClient();
  late Future<List<ProductReturn>> _future;
  String _query = '';
  DateTimeRange _range = todayRange();
  final Set<int> _syncingIds = {};

  bool get _canResync {
    final role = ApiClient.currentSession?.role;
    return role == 'ADMIN' || role == 'ACCOUNTANT';
  }

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<List<ProductReturn>> _load() => _api.getReturns(from: _range.start, to: _range.end);

  Future<void> _refresh() async {
    setState(() => _future = _load());
    await _future;
  }

  void _onRangeChanged(DateTimeRange range) {
    setState(() {
      _range = range;
      _future = _load();
    });
  }

  Future<void> _syncOneC(ProductReturn r) async {
    setState(() => _syncingIds.add(r.id));
    try {
      final updated = await _api.syncReturnOneC(r.id);
      await _refresh();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              updated.oneCSyncError == null
                  ? 'Возврат №${r.id} проведён в 1С'
                  : 'Не проведён: ${updated.oneCSyncError}',
            ),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось синхронизировать: $e')));
      }
    } finally {
      if (mounted) setState(() => _syncingIds.remove(r.id));
    }
  }

  Future<void> _openForm() async {
    if (await openReturnForm(context) != null) _refresh();
  }

  List<ProductReturn> _filter(List<ProductReturn> returns) {
    if (_query.isEmpty) return returns;
    final q = _query.toLowerCase();
    return returns
        .where(
          (r) => r.clientName.toLowerCase().contains(q) || r.items.any((i) => i.productName.toLowerCase().contains(q)),
        )
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SearchableAppBar(
        title: 'Возвраты',
        hintText: 'Клиент или товар...',
        onQueryChanged: (v) => setState(() => _query = v),
      ),
      drawer: const AppDrawer(currentRoute: '/returns'),
      floatingActionButton: FloatingActionButton(onPressed: _openForm, child: const Icon(Icons.add)),
      body: Column(
        children: [
          DateFilterBar(range: _range, onChanged: _onRangeChanged),
          Expanded(
            child: RefreshIndicator(
              onRefresh: _refresh,
              child: FutureBuilder<List<ProductReturn>>(
                future: _future,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
                  }
                  final returns = _filter(snapshot.data!);
                  if (returns.isEmpty) {
                    return ListView(
                      children: [
                        Padding(
                          padding: const EdgeInsets.all(32),
                          child: Center(
                            child: Text(
                              _query.isEmpty ? 'Возвратов нет' : 'Ничего не найдено',
                              style: const TextStyle(color: Colors.grey),
                            ),
                          ),
                        ),
                      ],
                    );
                  }
                  final total = returns.fold<double>(0, (s, r) => s + r.totalSum);
                  return ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    itemCount: returns.length + 1,
                    itemBuilder: (context, index) {
                      if (index == 0) {
                        return Padding(
                          padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
                          child: Text(
                            'Итого возвратов: ${formatMoney(total)}',
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                        );
                      }
                      final r = returns[index - 1];
                      final itemsSummary = r.items.map((i) => '${i.productName} × ${formatQty(i.quantity)}').join(', ');
                      final syncing = _syncingIds.contains(r.id);
                      return AppListTile(
                        leadingIcon: Icons.assignment_return_outlined,
                        leadingColor: returnColor,
                        title: '№${r.id} · ${r.clientName}',
                        subtitle: [
                          itemsSummary,
                          formatDate(r.returnDate),
                          if (r.reason != null) r.reason!,
                          r.orderId != null ? 'заказ #${r.orderId}' : 'не закреплён',
                        ].join(' · '),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(formatMoney(r.totalSum)),
                            const SizedBox(width: 6),
                            if (syncing)
                              const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                            else if (r.oneCSyncError != null)
                              IconButton(
                                icon: const Icon(Icons.sync_problem, color: Colors.red),
                                tooltip: _canResync ? '${r.oneCSyncError}\nНажмите, чтобы повторить' : r.oneCSyncError,
                                onPressed: _canResync ? () => _syncOneC(r) : null,
                              )
                            else if (r.oneCDocumentNumber != null)
                              Icon(
                                Icons.check_circle_outline,
                                color: const Color(0xFF4C8A5E),
                                size: 20,
                                semanticLabel: 'В 1С №${r.oneCDocumentNumber}',
                              ),
                          ],
                        ),
                        onTap: () => showReturnDetails(context, r, onChanged: _refresh),
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

/// Состав возврата — нижняя шторка; используется и в списке возвратов, и из карточки заказа.
void showReturnDetails(BuildContext context, ProductReturn r, {VoidCallback? onChanged}) {
  final outerContext = context;
  final role = ApiClient.currentSession?.role;
  final canDelete = role == 'ADMIN' || role == 'ACCOUNTANT';
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Возврат №${r.id} · ${r.clientName}', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              [
                formatDate(r.returnDate),
                if (r.reason != null) r.reason!,
                r.orderId != null ? 'закреплён за заказом #${r.orderId}' : 'не закреплён за заказом',
              ].join(' · '),
              style: const TextStyle(color: Colors.grey),
            ),
            if (r.oneCDocumentNumber != null)
              Text('1С: документ №${r.oneCDocumentNumber}', style: const TextStyle(color: Colors.grey)),
            if (r.oneCSyncError != null) Text(r.oneCSyncError!, style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 8),
            for (final i in r.items)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(i.productName),
                subtitle: Text('${formatQty(i.quantity)} × ${formatMoney(i.price)}'),
                trailing: Text(formatMoney(i.sum)),
              ),
            const Divider(),
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('Итого', style: TextStyle(fontWeight: FontWeight.bold)),
              trailing: Text(formatMoney(r.totalSum), style: const TextStyle(fontWeight: FontWeight.bold)),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                if (canDelete) ...[
                  Expanded(
                    child: OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                      icon: const Icon(Icons.delete_outline, size: 18),
                      label: const Text('Удалить'),
                      onPressed: () async {
                        Navigator.pop(context);
                        if (await _deleteReturn(outerContext, r)) onChanged?.call();
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                ],
                Expanded(
                  child: FilledButton.icon(
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    label: const Text('Изменить'),
                    onPressed: () async {
                      Navigator.pop(context);
                      if (await openReturnForm(outerContext, initial: r) != null) onChanged?.call();
                    },
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

/// Форма нового возврата или правки существующего ([initial]) — после сохранения показывает,
/// как прошла выгрузка в 1С. Возвращает сохранённый возврат или null, если форму закрыли.
Future<ProductReturn?> openReturnForm(BuildContext context, {ProductReturn? initial}) async {
  final saved = await showModalBottomSheet<ProductReturn>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _ReturnForm(initial: initial),
  );
  if (saved != null && context.mounted) {
    final action = initial == null ? 'сохранён' : 'изменён';
    final posted = initial == null ? 'проведён в 1С' : 'перепроведён в 1С';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          saved.oneCSyncError == null
              ? 'Возврат №${saved.id} $action${saved.oneCDocumentNumber != null ? ' и $posted' : ''}'
              : 'Возврат №${saved.id} $action, но в 1С ошибка: ${saved.oneCSyncError}',
        ),
      ),
    );
  }
  return saved;
}

/// Удаление с подтверждением — сервер сначала отменяет проведение в 1С и помечает документ на
/// удаление; если 1С не ответила, возврат остаётся и в CRM (показываем причину).
Future<bool> _deleteReturn(BuildContext context, ProductReturn r) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Удалить возврат №${r.id}?'),
      content: Text(
        r.oneCDocumentNumber != null
            ? 'В 1С документ №${r.oneCDocumentNumber} будет распроведён и помечен на удаление.'
            : 'Возврат будет удалён.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
        TextButton(
          onPressed: () => Navigator.pop(context, true),
          style: TextButton.styleFrom(foregroundColor: Colors.red),
          child: const Text('Удалить'),
        ),
      ],
    ),
  );
  if (confirmed != true) return false;
  try {
    await ApiClient().deleteReturn(r.id);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Возврат №${r.id} удалён')));
    }
    return true;
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось удалить: $e')));
    }
    return false;
  }
}

class _ReturnItemRow {
  Product? product;
  final quantity = TextEditingController();
  final price = TextEditingController();
}

const _reasons = ['Испорчен', 'Истёк срок'];

class _ReturnForm extends StatefulWidget {
  final ProductReturn? initial;

  const _ReturnForm({this.initial});

  @override
  State<_ReturnForm> createState() => _ReturnFormState();
}

class _ReturnFormState extends State<_ReturnForm> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  late Future<(List<Client>, List<Product>)> _future;
  Client? _client;

  /// Индивидуальные цены выбранного клиента: productId -> цена (см. ClientPriceService.resolvePrice).
  Map<int, double> _clientPrices = {};
  final List<_ReturnItemRow> _items = [_ReturnItemRow()];
  String? _reason = _reasons.first;
  DateTime _date = DateTime.now();
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<(List<Client>, List<Product>)> _load() async {
    final results = await Future.wait([_api.getClients(), _api.getProducts()]);
    final clients = results[0] as List<Client>;
    final products = results[1] as List<Product>;
    _prefill(clients, products);
    return (clients, products);
  }

  bool _prefilled = false;

  /// Правка: заполняем форму составом возврата — цены берём из самого возврата, не из прайса.
  void _prefill(List<Client> clients, List<Product> products) {
    final r = widget.initial;
    if (r == null || _prefilled) return;
    _prefilled = true;
    _client = clients.where((c) => c.id == r.clientId).firstOrNull;
    _reason = r.reason;
    _date = r.returnDate;
    _items
      ..clear()
      ..addAll(
        r.items.map((i) {
          final row = _ReturnItemRow()
            ..product = products.where((p) => p.id == i.productId).firstOrNull
            ..quantity.text = _formatPrice(i.quantity)
            ..price.text = _formatPrice(i.price);
          return row;
        }),
      );
    if (_client != null) {
      _api
          .getClientPrices(_client!.id)
          .then((prices) {
            if (mounted) setState(() => _clientPrices = {for (final p in prices) p.productId: p.price});
          })
          .catchError((_) {});
    }
  }

  double _priceFor(Product p) => _clientPrices[p.id] ?? p.price;

  Future<void> _selectClient(Client c) async {
    setState(() => _client = c);
    try {
      final prices = await _api.getClientPrices(c.id);
      if (!mounted || _client?.id != c.id) return;
      setState(() {
        _clientPrices = {for (final p in prices) p.productId: p.price};
        for (final row in _items) {
          if (row.product != null) row.price.text = _formatPrice(_priceFor(row.product!));
        }
      });
    } catch (_) {
      // Без индивидуальных цен — останутся базовые цены товаров, их можно поправить вручную.
    }
  }

  static String _formatPrice(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  static double? _parse(String text) => double.tryParse(text.replaceAll(',', '.').replaceAll(' ', ''));

  double get _total => _items.fold(0, (s, r) => s + (_parse(r.quantity.text) ?? 0) * (_parse(r.price.text) ?? 0));

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: _date.isBefore(DateTime.now().subtract(const Duration(days: 365)))
          ? _date
          : DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now(),
    );
    if (picked != null) setState(() => _date = picked);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate() || _client == null || _items.any((r) => r.product == null)) {
      setState(() => _error = 'Заполните все поля');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final items = _items.map((r) => (r.product!.id, _parse(r.quantity.text)!, _parse(r.price.text))).toList();
      final initial = widget.initial;
      final saved = initial == null
          ? await _api.createReturn(clientId: _client!.id, items: items, returnDate: _date, reason: _reason)
          : await _api.updateReturn(
              initial.id,
              clientId: _client!.id,
              items: items,
              returnDate: _date,
              reason: _reason,
            );
      if (mounted) Navigator.pop(context, saved);
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
      padding: EdgeInsets.only(left: 16, right: 16, top: 16, bottom: 16 + MediaQuery.of(context).viewInsets.bottom),
      child: FutureBuilder<(List<Client>, List<Product>)>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
          }
          if (snapshot.hasError) {
            return SizedBox(
              height: 120,
              child: ErrorView(error: snapshot.error.toString(), onRetry: () => setState(() => _future = _load())),
            );
          }
          final (clients, products) = snapshot.data!;
          return Form(
            key: _formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    widget.initial == null ? 'Новый возврат' : 'Возврат №${widget.initial!.id}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    'Проведётся в 1С на склад «Испорченные товары»',
                    style: TextStyle(color: Colors.grey, fontSize: 12.5),
                  ),
                  const SizedBox(height: 16),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: const TextStyle(color: Colors.red)),
                    ),
                  _autocomplete<Client>(
                    options: clients,
                    label: 'Клиент',
                    selected: _client,
                    name: (c) => c.name,
                    onSelected: _selectClient,
                    onCleared: () => setState(() => _client = null),
                    errorText: 'Выберите клиента из списка',
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    children: [
                      for (final r in _reasons)
                        AppChoiceChip(label: r, selected: _reason == r, onTap: () => setState(() => _reason = r)),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Text('Товары', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 8),
                  for (final row in _items)
                    KeyedSubtree(
                      key: ObjectKey(row),
                      child: Padding(padding: const EdgeInsets.only(bottom: 12), child: _buildItemRow(products, row)),
                    ),
                  OutlinedButton.icon(
                    onPressed: () => setState(() => _items.add(_ReturnItemRow())),
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('Добавить товар'),
                  ),
                  const SizedBox(height: 16),
                  InkWell(
                    onTap: _pickDate,
                    child: InputDecorator(
                      decoration: const InputDecoration(labelText: 'Дата возврата'),
                      child: Text(formatDate(_date)),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Сумма возврата: ${formatMoney(_total)}',
                    style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: _saving ? null : _save,
                    child: _saving
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : Text(widget.initial == null ? 'Сохранить и провести' : 'Сохранить и перепровести'),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildItemRow(List<Product> products, _ReturnItemRow row) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: _autocomplete<Product>(
                options: products,
                label: 'Товар',
                selected: row.product,
                name: (p) => p.name,
                onSelected: (p) => setState(() {
                  row.product = p;
                  row.price.text = _formatPrice(_priceFor(p));
                }),
                onCleared: () => setState(() => row.product = null),
                errorText: 'Выберите товар из списка',
              ),
            ),
            if (_items.length > 1)
              IconButton(
                icon: const Icon(Icons.close),
                tooltip: 'Убрать позицию',
                onPressed: () => setState(() => _items.remove(row)),
              ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: TextFormField(
                controller: row.quantity,
                decoration: InputDecoration(
                  labelText: 'Количество${row.product?.unit != null ? ', ${row.product!.unit}' : ''}',
                ),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                onChanged: (_) => setState(() {}),
                validator: (v) {
                  final q = _parse(v ?? '');
                  return q == null || q <= 0 ? 'Введите число' : null;
                },
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextFormField(
                controller: row.price,
                decoration: const InputDecoration(labelText: 'Цена, ₸'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                onChanged: (_) => setState(() {}),
                validator: (v) {
                  final p = _parse(v ?? '');
                  return p == null || p < 0 ? 'Введите цену' : null;
                },
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _autocomplete<T extends Object>({
    required List<T> options,
    required String label,
    required T? selected,
    required String Function(T) name,
    required void Function(T) onSelected,
    required VoidCallback onCleared,
    required String errorText,
  }) {
    return Autocomplete<T>(
      displayStringForOption: name,
      optionsBuilder: (value) {
        if (value.text.isEmpty) return const Iterable.empty();
        final q = value.text.toLowerCase();
        return options.where((o) => name(o).toLowerCase().contains(q));
      },
      onSelected: onSelected,
      fieldViewBuilder: (context, controller, focusNode, onSubmit) {
        if (selected != null && controller.text != name(selected)) {
          controller.text = name(selected);
        }
        return TextFormField(
          controller: controller,
          focusNode: focusNode,
          decoration: InputDecoration(labelText: label, hintText: 'Начните вводить название...'),
          onChanged: (v) {
            if (selected != null && v != name(selected)) onCleared();
          },
          validator: (v) => selected == null ? errorText : null,
        );
      },
      optionsViewBuilder: (context, onSelect, opts) => Align(
        alignment: Alignment.topLeft,
        child: Material(
          elevation: 4,
          borderRadius: BorderRadius.circular(8),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 260, minWidth: 260),
            child: ListView(
              padding: EdgeInsets.zero,
              shrinkWrap: true,
              children: [for (final o in opts) ListTile(dense: true, title: Text(name(o)), onTap: () => onSelect(o))],
            ),
          ),
        ),
      ),
    );
  }
}
