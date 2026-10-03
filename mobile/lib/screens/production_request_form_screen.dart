import 'package:flutter/material.dart';

import '../models/production_request.dart';
import '../services/api_client.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

class _Line {
  ProductionEligibleProduct? product;
  final batches = TextEditingController();
  final pieces = TextEditingController();
  bool _syncing = false;

  void wire(VoidCallback onChanged) {
    batches.addListener(() {
      if (_syncing || product == null) return;
      final v = double.tryParse(batches.text.replaceAll(',', '.'));
      _syncing = true;
      pieces.text = v == null ? '' : formatQty(v * product!.yieldQuantity);
      _syncing = false;
      onChanged();
    });
    pieces.addListener(() {
      if (_syncing || product == null) return;
      final v = double.tryParse(pieces.text.replaceAll(',', '.'));
      _syncing = true;
      batches.text = (v == null || product!.yieldQuantity == 0) ? '' : formatQty(v / product!.yieldQuantity);
      _syncing = false;
      onChanged();
    });
  }
}

class ProductionRequestFormScreen extends StatefulWidget {
  /// Если задан — форма открыта в режиме редактирования существующей заявки (только для
  /// заявок в статусе «Новая», см. ProductionRequestService.update): позиции подтягиваются
  /// с сервера, название заявки не меняется.
  final int? requestId;

  const ProductionRequestFormScreen({super.key, this.requestId});

  @override
  State<ProductionRequestFormScreen> createState() => _ProductionRequestFormScreenState();
}

class _ProductionRequestFormScreenState extends State<ProductionRequestFormScreen> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  late Future<List<ProductionEligibleProduct>> _future;
  final List<_Line> _lines = [];
  bool _saving = false;
  String? _error;

  /// Дата документа для 1С (время там всегда фиксированное 06:00, см. бэкенд) — по умолчанию
  /// сегодня, пользователь может выбрать другую (например, заявку оформили на вчерашнюю смену).
  DateTime _documentDate = DateTime.now();

  bool get _isEdit => widget.requestId != null;

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  Future<List<ProductionEligibleProduct>> _load() async {
    final products = await _api.getEligibleProductsForRequest();
    if (_isEdit) {
      final existing = await _api.getProductionRequest(widget.requestId!);
      _documentDate = existing.documentDate;
      for (final l in existing.lines) {
        final line = _Line();
        ProductionEligibleProduct? matched;
        for (final p in products) {
          if (p.id == l.productId) {
            matched = p;
            break;
          }
        }
        line.product = matched ??
            ProductionEligibleProduct(id: l.productId, name: l.productName, unit: l.productUnit, yieldQuantity: 1);
        line.batches.text = formatQty(l.batches);
        line.wire(() => setState(() {}));
        _lines.add(line);
      }
    } else {
      final line = _Line();
      line.wire(() => setState(() {}));
      _lines.add(line);
    }
    return products;
  }

  void _addLine() => setState(() {
        final line = _Line();
        line.wire(() => setState(() {}));
        _lines.add(line);
      });

  void _removeLine(_Line line) => setState(() => _lines.remove(line));

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _documentDate,
      firstDate: DateTime.now().subtract(const Duration(days: 365)),
      lastDate: DateTime.now().add(const Duration(days: 365)),
      helpText: 'Дата документа для 1С',
    );
    if (picked != null) setState(() => _documentDate = picked);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate() || _lines.any((l) => l.product == null)) {
      setState(() => _error = 'Заполните все позиции');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final items = _lines
          .map((l) => (l.product!.id, double.parse(l.batches.text.replaceAll(',', '.'))))
          .toList();
      if (_isEdit) {
        final adjustments = await _api.getProductionRequestAdjustments(widget.requestId!);
        bool keep = false;
        if (adjustments.isNotEmpty) {
          if (!mounted) return;
          final choice = await _askAdjustments(adjustments);
          if (choice == null) {
            setState(() => _saving = false);
            return;
          }
          keep = choice;
        }
        await _api.updateProductionRequest(widget.requestId!, items,
            documentDate: _documentDate, keepAdjustments: keep);
      } else {
        await _api.createProductionRequest(items, documentDate: _documentDate);
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      setState(() {
        _error = e.toString();
        _saving = false;
      });
    }
  }

  /// В составе были поправки к рецептуре — применить их к новому составу или очистить.
  /// null — отмена (ничего не сохраняем).
  Future<bool?> _askAdjustments(List<String> adjustments) => showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Поправки в составе'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('В заявке есть поправки к рецептуре:'),
                const SizedBox(height: 8),
                for (final a in adjustments)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text('• $a', style: const TextStyle(fontSize: 13.5)),
                  ),
                const SizedBox(height: 8),
                const Text(
                  'Применить — рецептура на новые замесы + эти поправки.\nОчистить — только рецептура.',
                  style: TextStyle(color: Colors.grey, fontSize: 12.5),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Очистить')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Применить')),
          ],
        ),
      );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Заявка №${widget.requestId}' : 'Новое производство')),
      body: FutureBuilder<List<ProductionEligibleProduct>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(
              error: snapshot.error.toString(),
              onRetry: () => setState(() => _future = _load()),
            );
          }
          final products = snapshot.data!;
          if (products.isEmpty) {
            return const Padding(
              padding: EdgeInsets.all(24),
              child: Center(
                child: Text(
                  'Нет ни одного товара с заполненной рецептурой — сначала добавьте рецептуру на странице товара.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
              ),
            );
          }
          return Form(
            key: _formKey,
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text(
                  _isEdit
                      ? 'Название заявки не меняется — оно определилось по группе товара при '
                          'создании. Замесы и штуки пересчитываются друг в друга сами.'
                      : 'Можно сразу добавить несколько позиций — например, из одного теста сегодня '
                          'решили сделать несколько разных вариантов. Замесы и штуки пересчитываются сами. '
                          'Название заявки определится автоматически по группе товара — если позиции из '
                          'разных групп, будет создано несколько заявок.',
                  style: const TextStyle(color: Colors.grey, fontSize: 13),
                ),
                const SizedBox(height: 12),
                InkWell(
                  onTap: _pickDate,
                  child: InputDecorator(
                    decoration: const InputDecoration(labelText: 'Дата документа для 1С', isDense: true),
                    child: Text(formatDate(_documentDate)),
                  ),
                ),
                const SizedBox(height: 12),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),
                ..._buildPositionWidgets(products),
                OutlinedButton.icon(
                  onPressed: _addLine,
                  icon: const Icon(Icons.add),
                  label: const Text('Добавить позицию'),
                ),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_isEdit ? 'Сохранить' : 'Создать заявку'),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  /// Позиции с выбранным товаром группируются под заголовком группы (сортировка — алфавит,
  /// "Без группы" всегда последней, зеркалит серверную ProductService.groupByGroup) — так
  /// сразу видно, как сервер разобьёт заявку. Позиции без выбранного товара ещё "в процессе
  /// заполнения" — идут ниже без заголовка, пока не выбран товар.
  List<Widget> _buildPositionWidgets(List<ProductionEligibleProduct> products) {
    final groups = <String, List<_Line>>{};
    final unassigned = <_Line>[];
    for (final line in _lines) {
      if (line.product == null) {
        unassigned.add(line);
        continue;
      }
      final group = (line.product!.group == null || line.product!.group!.trim().isEmpty)
          ? 'Без группы'
          : line.product!.group!;
      groups.putIfAbsent(group, () => []).add(line);
    }
    final keys = groups.keys.where((k) => k != 'Без группы').toList()..sort();
    if (groups.containsKey('Без группы')) keys.add('Без группы');

    final widgets = <Widget>[];
    for (final key in keys) {
      final linesInGroup = groups[key]!;
      final batches = linesInGroup.fold<double>(
          0, (sum, l) => sum + (double.tryParse(l.batches.text.replaceAll(',', '.')) ?? 0));
      widgets.add(Padding(
        padding: const EdgeInsets.fromLTRB(2, 12, 2, 6),
        child: Text(
          '$key · ${linesInGroup.length} поз.${batches > 0 ? ' · ${formatQty(batches)} замес.' : ''}',
          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
        ),
      ));
      for (final line in linesInGroup) {
        widgets.add(KeyedSubtree(key: ObjectKey(line), child: _buildLine(products, line)));
      }
    }
    for (final line in unassigned) {
      widgets.add(KeyedSubtree(key: ObjectKey(line), child: _buildLine(products, line)));
    }
    return widgets;
  }

  Widget _buildLine(List<ProductionEligibleProduct> products, _Line line) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Autocomplete<ProductionEligibleProduct>(
                  displayStringForOption: (p) => p.name,
                  optionsBuilder: (value) {
                    if (value.text.isEmpty) return const Iterable.empty();
                    final q = value.text.toLowerCase();
                    return products.where((p) => p.name.toLowerCase().contains(q));
                  },
                  onSelected: (p) => setState(() => line.product = p),
                  fieldViewBuilder: (context, controller, focusNode, onSubmit) {
                    if (line.product != null && controller.text != line.product!.name) {
                      controller.text = line.product!.name;
                    }
                    return TextFormField(
                      controller: controller,
                      focusNode: focusNode,
                      decoration: const InputDecoration(labelText: 'Товар', hintText: 'Начните вводить название...'),
                      onChanged: (v) {
                        if (line.product != null && v != line.product!.name) {
                          setState(() => line.product = null);
                        }
                      },
                      validator: (v) => line.product == null ? 'Выберите товар из списка' : null,
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
                            for (final p in options)
                              ListTile(dense: true, title: Text(p.name), onTap: () => onSelected(p)),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              if (_lines.length > 1)
                IconButton(
                  icon: const Icon(Icons.close),
                  tooltip: 'Убрать позицию',
                  onPressed: () => _removeLine(line),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: TextFormField(
                  controller: line.batches,
                  decoration: const InputDecoration(labelText: 'Замесов'),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  validator: (v) => (v == null || double.tryParse(v.replaceAll(',', '.')) == null) ? 'Число' : null,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextFormField(
                  controller: line.pieces,
                  decoration: InputDecoration(labelText: line.product != null ? (line.product!.unit ?? 'шт') : 'Штук/кг'),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
