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
  /// Альбом товара для сайта в том виде, как будет после «Сохранить»: первое — обложка.
  final List<_PhotoItem> _photos = [];
  /// Фото с сервера, которые убрали в форме — удалятся при сохранении.
  final List<int> _removedPhotoIds = [];
  /// Порядок фото на сервере при открытии формы.
  final List<int> _initialPhotoIds = [];
  /// Выбранное фото (индекс в _photos) — под сеткой кнопки «Левее/Правее/Обложкой/Удалить».
  int? _selectedPhoto;

  bool get _isEdit => widget.productId != null;

  /// Фото товара меняет только админ (на бэкенде то же самое — ProductApiController).
  bool get _isAdmin => ApiClient.currentSession?.role == 'ADMIN';

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
        _photos.addAll(p.photos.map((ph) => _PhotoItem.server(ph)));
        _initialPhotoIds.addAll(p.photos.map((ph) => ph.id));
        // товар со ссылкой на фото, но без альбома (старые данные) — показываем ссылку как есть
        if (_photos.isEmpty && (p.imageUrl ?? '').isNotEmpty) _photos.add(_PhotoItem.link(p.imageUrl!));
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

  /// После выбора — обязательная обрезка 3:4 (ImageCropScreen): в каталоге сайта и альбоме фото 3:4,
  /// так у всех товаров одинаковый кадр. Из галереи можно выбрать сразу несколько — каждое
  /// обрезается по очереди («Назад» на обрезке пропускает фото). Загрузятся при «Сохранить».
  Future<void> _pickImages(ImageSource source) async {
    final picker = ImagePicker();
    final List<XFile> picked = source == ImageSource.gallery
        ? await picker.pickMultiImage(imageQuality: 92, maxWidth: 2400, maxHeight: 2400)
        : [?await picker.pickImage(source: source, imageQuality: 92, maxWidth: 2400, maxHeight: 2400)];
    for (final file in picked) {
      if (!mounted) return;
      final bytes = await file.readAsBytes();
      if (!mounted) return;
      final cropped = await Navigator.push<Uint8List>(
        context,
        MaterialPageRoute(builder: (_) => ImageCropScreen(bytes: bytes)),
      );
      if (cropped != null) setState(() => _photos.add(_PhotoItem.local(cropped)));
    }
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
              subtitle: const Text('Можно сразу несколько'),
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
    if (source != null) await _pickImages(source);
  }

  void _movePhoto(int from, int to) {
    if (to < 0 || to >= _photos.length || from == to) return;
    setState(() {
      _photos.insert(to, _photos.removeAt(from));
      _selectedPhoto = to;
    });
  }

  void _removePhoto(int i) {
    setState(() {
      final removed = _photos.removeAt(i);
      if (removed.server != null) _removedPhotoIds.add(removed.server!.id);
      _selectedPhoto = null;
    });
  }

  /// Применяет альбом из формы на сервере: удалить убранные, загрузить новые (по одному — до 10 МБ
  /// каждое), затем выставить порядок. Ссылку без альбома (_PhotoItem.link) не трогаем.
  Future<void> _syncPhotos(int productId) async {
    for (final id in _removedPhotoIds) {
      await _api.deleteProductPhoto(productId, id);
    }
    final ids = <int>[];
    final added = <int>[];
    for (final item in _photos) {
      if (item.server != null) {
        ids.add(item.server!.id);
      } else if (item.bytes != null) {
        final p = await _api.addProductPhoto(productId,
            bytes: item.bytes!, filename: 'product.png', contentType: 'image/png');
        ids.add(p.photos.last.id);
        added.add(p.photos.last.id);
      }
    }
    // На сервере сейчас: прежние фото без удалённых, новые — в конце. Не совпадает с формой — шлём порядок.
    final onServer = [..._initialPhotoIds.where((id) => !_removedPhotoIds.contains(id)), ...added];
    if (ids.length > 1 && !_sameOrder(ids, onServer)) await _api.reorderProductPhotos(productId, ids);
  }

  static bool _sameOrder(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
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
      if (_isAdmin) await _syncPhotos(product.id);
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
                  _photoSection(),
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
                  if (_isAdmin && _photos.every((p) => p.link != null)) ...[
                    TextFormField(
                      controller: _imageUrl,
                      decoration: const InputDecoration(
                        labelText: 'Или ссылка на фото',
                        hintText: 'https://...',
                        helperText: 'Используется, если фото с телефона не добавлены',
                      ),
                      keyboardType: TextInputType.url,
                    ),
                    const SizedBox(height: 12),
                  ],
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

  /// Альбом товара для сайта: сетка фото 3:4, первое — обложка. Админ добавляет, меняет порядок и
  /// удаляет (применится при «Сохранить»); остальные только смотрят.
  Widget _photoSection() {
    final pal = context.pal;
    final sel = _selectedPhoto != null && _selectedPhoto! < _photos.length ? _selectedPhoto : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Text('Фото для сайта', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(width: 8),
          Text('${_photos.length}', style: TextStyle(color: pal.muted)),
        ]),
        const SizedBox(height: 4),
        Text(
          _isAdmin
              ? 'Первое — обложка в каталоге и заказах. Остальные покупатель листает в альбоме. Нажмите на фото, чтобы поменять место или удалить.'
              : 'Фото товара меняет только администратор.',
          style: TextStyle(color: pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 10),
        LayoutBuilder(builder: (context, c) {
          const gap = 8.0;
          final w = (c.maxWidth - gap * 3) / 4;
          return Wrap(
            spacing: gap,
            runSpacing: gap,
            children: [
              for (var i = 0; i < _photos.length; i++) _photoTile(i, w, selected: sel == i),
              if (_isAdmin) _addTile(w),
            ],
          );
        }),
        if (_isAdmin && sel != null) ...[
          const SizedBox(height: 10),
          Text(sel == 0 ? 'Фото 1 — обложка' : 'Фото ${sel + 1}', style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 6),
          Wrap(spacing: 8, runSpacing: 8, children: [
            OutlinedButton(onPressed: sel == 0 ? null : () => _movePhoto(sel, sel - 1), child: const Text('← Левее')),
            OutlinedButton(
                onPressed: sel == _photos.length - 1 ? null : () => _movePhoto(sel, sel + 1), child: const Text('Правее →')),
            OutlinedButton(onPressed: sel == 0 ? null : () => _movePhoto(sel, 0), child: const Text('Сделать обложкой')),
            OutlinedButton(
              onPressed: () => _removePhoto(sel),
              style: OutlinedButton.styleFrom(foregroundColor: pal.danger),
              child: const Text('Удалить'),
            ),
          ]),
        ],
      ],
    );
  }

  Widget _photoTile(int i, double w, {required bool selected}) {
    final pal = context.pal;
    final item = _photos[i];
    final Widget image = item.bytes != null
        ? Image.memory(item.bytes!, fit: BoxFit.cover)
        : CachedNetworkImage(
            imageUrl: item.url!,
            cacheManager: TimeoutCacheManager(),
            fit: BoxFit.cover,
            errorWidget: (context, url, error) => Icon(Icons.image_not_supported_outlined, color: pal.muted),
          );
    return GestureDetector(
      onTap: _isAdmin && item.link == null ? () => setState(() => _selectedPhoto = selected ? null : i) : null,
      child: Container(
        width: w,
        height: w * 4 / 3,
        decoration: BoxDecoration(
          color: pal.bg,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: selected ? pal.accent : pal.line, width: selected ? 3 : 1),
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(fit: StackFit.expand, children: [
          image,
          Positioned(
            left: 4,
            top: 4,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(color: Colors.black.withValues(alpha: .7), borderRadius: BorderRadius.circular(99)),
              child: Text('${i + 1}', style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700)),
            ),
          ),
          if (i == 0)
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Container(
                color: Colors.black.withValues(alpha: .7),
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: const Text('Обложка',
                    textAlign: TextAlign.center, style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
              ),
            ),
          if (item.bytes != null)
            Positioned(
              right: 4,
              top: 4,
              child: Icon(Icons.cloud_upload_outlined, size: 16, color: pal.accent, shadows: const [Shadow(blurRadius: 4)]),
            ),
        ]),
      ),
    );
  }

  Widget _addTile(double w) {
    final pal = context.pal;
    return InkWell(
      onTap: _choosePhotoSource,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        width: w,
        height: w * 4 / 3,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: pal.muted, width: 1.5),
        ),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.add_a_photo_outlined, color: pal.accent),
          const SizedBox(height: 4),
          Text('Добавить', style: TextStyle(color: pal.accent, fontSize: 12, fontWeight: FontWeight.w600)),
        ]),
      ),
    );
  }
}

/// Фото в форме: уже на сервере, только что выбранное (загрузится при «Сохранить») или
/// старая ссылка без альбома (imageUrl), которую в форме не меняют.
class _PhotoItem {
  final ProductPhoto? server;
  final Uint8List? bytes;
  final String? link;

  _PhotoItem.server(ProductPhoto p) : server = p, bytes = null, link = null;
  _PhotoItem.local(Uint8List b) : server = null, bytes = b, link = null;
  _PhotoItem.link(String url) : server = null, bytes = null, link = url;

  String? get url => server?.url ?? link;
}
