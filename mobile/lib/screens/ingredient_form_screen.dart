import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../theme/app_theme.dart';

class IngredientFormScreen extends StatefulWidget {
  final int? ingredientId;

  const IngredientFormScreen({super.key, this.ingredientId});

  @override
  State<IngredientFormScreen> createState() => _IngredientFormScreenState();
}

class _IngredientFormScreenState extends State<IngredientFormScreen> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _unit = TextEditingController();
  final _quantity = TextEditingController();
  final _minThreshold = TextEditingController();
  bool _loading = false;
  bool _initialLoading = false;
  String? _error;

  bool get _isEdit => widget.ingredientId != null;

  @override
  void initState() {
    super.initState();
    if (_isEdit) {
      _initialLoading = true;
      _api.getIngredient(widget.ingredientId!).then((i) {
        _name.text = i.name;
        _unit.text = i.unit;
        _quantity.text = i.quantityInStock.toString();
        _minThreshold.text = i.minThreshold.toString();
        setState(() => _initialLoading = false);
      });
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final body = {
      'name': _name.text.trim(),
      'unit': _unit.text.trim(),
      'quantityInStock': double.tryParse(_quantity.text.replaceAll(',', '.')) ?? 0,
      'minThreshold': double.tryParse(_minThreshold.text.replaceAll(',', '.')) ?? 0,
    };
    try {
      if (_isEdit) {
        await _api.updateIngredient(widget.ingredientId!, body);
      } else {
        await _api.createIngredient(body);
      }
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Редактировать ингредиент' : 'Новый ингредиент')),
      body: _initialLoading
          ? const Center(child: CircularProgressIndicator())
          : Form(
              key: _formKey,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: TextStyle(color: context.pal.danger)),
                    ),
                  TextFormField(
                    controller: _name,
                    decoration: const InputDecoration(labelText: 'Название'),
                    validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите название' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _unit,
                    decoration: const InputDecoration(labelText: 'Единица измерения', hintText: 'кг, г, л, мл, шт'),
                    validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите единицу' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _quantity,
                    decoration: const InputDecoration(labelText: 'Текущий остаток'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    validator: (v) => (v == null || double.tryParse(v.replaceAll(',', '.')) == null)
                        ? 'Введите число'
                        : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _minThreshold,
                    decoration: const InputDecoration(labelText: 'Минимальный порог'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    validator: (v) => (v == null || double.tryParse(v.replaceAll(',', '.')) == null)
                        ? 'Введите число'
                        : null,
                  ),
                  const SizedBox(height: 20),
                  FilledButton(
                    onPressed: _loading ? null : _save,
                    child: _loading
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Сохранить'),
                  ),
                ],
              ),
            ),
    );
  }
}
