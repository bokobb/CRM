import 'dart:typed_data';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../theme/app_theme.dart';
import '../models/product.dart';
import '../services/api_client.dart';
import '../widgets/format.dart';
import '../widgets/timeout_cache_manager.dart';
import 'image_crop_screen.dart';

class ProductFormScreen extends StatefulWidget {
  final int? productId;

  const ProductFormScreen({super.key, this.productId});

  @override
  State<ProductFormScreen> createState() => _ProductFormScreenState();
}

class _ProductFormScreenState extends State<ProductFormScreen> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _category = TextEditingController();
  final _group = TextEditingController();
  final _description = TextEditingController();
  final _price = TextEditingController();
  final _unit = TextEditingController();
  final _imageUrl = TextEditingController();
  final _netWeightGrams = TextEditingController();
  final _unitWeightKg = TextEditingController();
  final _unitsPerBox = TextEditingController();
  final _boxVolumeM3 = TextEditingController();
  final _unitsPerBox2 = TextEditingController();
  final _boxVolume2M3 = TextEditingController();
  bool _semiFinished = false;
  bool _massReferenceProduct = false;
  String? _departmentKey;
  bool _loading = false;
  bool _initialLoading = false;
  String? _error;
  String? _existingImageUrl;
  Uint8List? _pickedImageBytes;

  bool get _isEdit => widget.productId != null;

  @override
  void initState() {
    super.initState();
    if (_isEdit) {
      _initialLoading = true;
      _api.getProduct(widget.productId!).then((p) {
        _name.text = p.name;
        _category.text = p.category ?? '';
        _group.text = p.group ?? '';
        _description.text = p.description ?? '';
        _price.text = p.price.toString();
        _unit.text = p.unit ?? '';
        _imageUrl.text = p.imageUrl ?? '';
        _existingImageUrl = p.imageUrl;
        _netWeightGrams.text = p.netWeightGrams?.toString() ?? '';
        _unitWeightKg.text = p.unitWeightKg == null ? '' : formatQty(p.unitWeightKg!);
        _unitsPerBox.text = p.unitsPerBox == null ? '' : formatQty(p.unitsPerBox!);
        _boxVolumeM3.text = p.boxVolumeM3 == null ? '' : p.boxVolumeM3!.toString();
        _unitsPerBox2.text = p.unitsPerBox2 == null ? '' : formatQty(p.unitsPerBox2!);
        _boxVolume2M3.text = p.boxVolume2M3 == null ? '' : p.boxVolume2M3!.toString();
        // Не заполнено — подставляем штуки из названия («(12/24 шт)» → 12 и 24).
        final fromName = Product.unitsFromName(p.name);
        if (_unitsPerBox.text.isEmpty && fromName.isNotEmpty) _unitsPerBox.text = formatQty(fromName[0]);
        if (_unitsPerBox2.text.isEmpty && fromName.length > 1) _unitsPerBox2.text = formatQty(fromName[1]);
        _semiFinished = p.semiFinished;
        _massReferenceProduct = p.massReferenceProduct;
        _departmentKey = p.oneCDepartmentKey;
        setState(() => _initialLoading = false);
      });
    }
  }

  /// После выбора — обязательная квадратная обрезка (ImageCropScreen): в каталоге сайта фото 1:1,
  /// так у всех товаров одинаковый кадр. Загружается уже готовый PNG 1000×1000.
  Future<void> _pickImage(ImageSource source) async {
    final picked = await ImagePicker().pickImage(source: source, imageQuality: 92, maxWidth: 2400, maxHeight: 2400);
    if (picked == null || !mounted) return;
    final bytes = await picked.readAsBytes();
    if (!mounted) return;
    final cropped = await Navigator.push<Uint8List>(
      context,
      MaterialPageRoute(builder: (_) => ImageCropScreen(bytes: bytes)),
    );
    if (cropped == null) return;
    setState(() => _pickedImageBytes = cropped);
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
              leading: Icon(Icons.photo_library_outlined, color: context.pal.accent),
              title: const Text('Выбрать из галереи'),
              onTap: () => Navigator.pop(context, ImageSource.gallery),
            ),
            ListTile(
              leading: Icon(Icons.photo_camera_outlined, color: context.pal.accent),
              title: const Text('Сделать фото'),
              onTap: () => Navigator.pop(context, ImageSource.camera),
            ),
          ],
        ),
      ),
    );
    if (source != null) await _pickImage(source);
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final body = {
      'name': _name.text.trim(),
      'category': _category.text.trim(),
      'group': _group.text.trim(),
      'description': _description.text.trim(),
      'price': double.tryParse(_price.text.replaceAll(',', '.')) ?? 0,
      'unit': _unit.text.trim(),
      'imageUrl': _imageUrl.text.trim(),
      'netWeightGrams': _netWeightGrams.text.trim().isEmpty
          ? null
          : double.tryParse(_netWeightGrams.text.replaceAll(',', '.')),
      'semiFinished': _semiFinished,
      'massReferenceProduct': _massReferenceProduct,
      'unitWeightKg': _num(_unitWeightKg),
      'unitsPerBox': _num(_unitsPerBox),
      'boxVolumeM3': _num(_boxVolumeM3),
      'unitsPerBox2': _num(_unitsPerBox2),
      'boxVolume2M3': _num(_boxVolume2M3),
      'oneCDepartmentKey': _departmentKey,
    };
    try {
      final product =
          _isEdit ? await _api.updateProduct(widget.productId!, body) : await _api.createProduct(body);
      if (_pickedImageBytes != null) {
        await _api.uploadProductImage(
          product.id,
          bytes: _pickedImageBytes!,
          filename: 'product.png',
          contentType: 'image/png',
        );
      }
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  String _unitLabel() => _unit.text.trim().isEmpty ? 'шт' : _unit.text.trim();

  static double? _num(TextEditingController c) =>
      c.text.trim().isEmpty ? null : double.tryParse(c.text.trim().replaceAll(',', '.'));

  Widget _numberField(TextEditingController c, String label, String hint) => TextFormField(
        controller: c,
        decoration: InputDecoration(labelText: label, hintText: hint),
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        validator: (v) => (v != null && v.trim().isNotEmpty && double.tryParse(v.trim().replaceAll(',', '.')) == null)
            ? 'Введите число'
            : null,
      );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Редактировать товар' : 'Новый товар')),
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
                  Center(child: _photoPreview()),
                  const SizedBox(height: 10),
                  Center(
                    child: OutlinedButton.icon(
                      onPressed: _choosePhotoSource,
                      icon: const Icon(Icons.add_a_photo_outlined, size: 18),
                      label: Text(_pickedImageBytes == null ? 'Загрузить фото с телефона' : 'Заменить фото'),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: _name,
                    decoration: const InputDecoration(labelText: 'Название'),
                    validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите название' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _category,
                    decoration: const InputDecoration(labelText: 'Категория', hintText: 'Торты, капкейки...'),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _group,
                    decoration: const InputDecoration(labelText: 'Группа', hintText: 'Коржик, Орешки...'),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _price,
                    decoration: const InputDecoration(labelText: 'Цена, ₸'),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    validator: (v) => (v == null || double.tryParse(v.replaceAll(',', '.')) == null)
                        ? 'Введите число'
                        : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _unit,
                    decoration: const InputDecoration(labelText: 'Ед. изм.', hintText: 'шт, кг...'),
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 16),
                  Text('Упаковка (для объёма и массы заказа)', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  _numberField(_unitWeightKg, 'Вес 1 ${_unitLabel()}, кг', 'С упаковкой, например 0,45'),
                  const SizedBox(height: 12),
                  Row(children: [
                    Expanded(child: _numberField(_unitsPerBox, 'Упаковка 1: ${_unitLabel()} в коробке', 'Например 12')),
                    const SizedBox(width: 12),
                    Expanded(child: _numberField(_boxVolumeM3, 'Объём коробки, м³', '0,4×0,3×0,2 = 0,024')),
                  ]),
                  const SizedBox(height: 12),
                  Row(children: [
                    Expanded(child: _numberField(_unitsPerBox2, 'Упаковка 2: ${_unitLabel()} в коробке', 'Необязательно')),
                    const SizedBox(width: 12),
                    Expanded(child: _numberField(_boxVolume2M3, 'Объём коробки, м³', 'Необязательно')),
                  ]),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: _netWeightGrams,
                    decoration: const InputDecoration(
                      labelText: 'Вес 1 шт для сверки производства, г',
                      hintText: 'Необязательно',
                      helperText: 'Только если норму при оприходовании нужно сверять по общей массе '
                          'группы (разные фасовки одного замеса), а не по штукам',
                      helperMaxLines: 2,
                    ),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    validator: (v) => (v != null && v.trim().isNotEmpty && double.tryParse(v.replaceAll(',', '.')) == null)
                        ? 'Введите число'
                        : null,
                  ),
                  const SizedBox(height: 4),
                  CheckboxListTile(
                    value: _massReferenceProduct,
                    onChanged: (v) => setState(() => _massReferenceProduct = v ?? false),
                    controlAffinity: ListTileControlAffinity.leading,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Эталон для пересчёта массы в штуки'),
                    subtitle: const Text('В заявке на сырьё "не хватает N кг" переведётся в "≈ M шт. этого товара" '
                        '(максимум один такой товар в группе, с заданным весом выше)'),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _description,
                    decoration: const InputDecoration(labelText: 'Описание'),
                    maxLines: 3,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _imageUrl,
                    decoration: const InputDecoration(
                      labelText: 'Или ссылка на фото',
                      hintText: 'https://...',
                      helperText: 'Используется, если фото с телефона не выбрано',
                    ),
                    keyboardType: TextInputType.url,
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String?>(
                    initialValue: _departmentKey,
                    decoration: const InputDecoration(labelText: 'Подразделение в 1С (корпус)'),
                    items: [
                      const DropdownMenuItem<String?>(
                        value: null,
                        child: Text('Не задано (используется общее по умолчанию)'),
                      ),
                      ...oneCDepartmentNames.entries.map(
                        (e) => DropdownMenuItem<String?>(value: e.key, child: Text(e.value)),
                      ),
                    ],
                    onChanged: (v) => setState(() => _departmentKey = v),
                  ),
                  const SizedBox(height: 4),
                  CheckboxListTile(
                    value: _semiFinished,
                    onChanged: (v) => setState(() => _semiFinished = v ?? false),
                    controlAffinity: ListTileControlAffinity.leading,
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Полуфабрикат'),
                    subtitle: const Text('Заготовка вроде «Пряник» — не в витрине заказов и «Остатках», '
                        'остаток учитывается как сырьё, в 1С уходит на склад сырья'),
                  ),
                  const SizedBox(height: 16),
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

  Widget _photoPreview() {
    Widget child;
    if (_pickedImageBytes != null) {
      child = Image.memory(_pickedImageBytes!, fit: BoxFit.cover);
    } else if (_existingImageUrl != null && _existingImageUrl!.isNotEmpty) {
      child = CachedNetworkImage(
        imageUrl: _existingImageUrl!,
        cacheManager: TimeoutCacheManager(),
        fit: BoxFit.cover,
        errorWidget: (context, url, error) => Icon(Icons.image_not_supported_outlined, color: context.pal.muted),
      );
    } else {
      child = Icon(Icons.image_outlined, size: 36, color: context.pal.muted);
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: 140,
        height: 140,
        color: context.pal.bg,
        alignment: Alignment.center,
        child: child,
      ),
    );
  }
}
