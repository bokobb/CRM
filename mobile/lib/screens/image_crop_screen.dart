import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Обрезка фото товара 3:4 перед загрузкой: в каталоге сайта (templates/promo.html) и в альбоме
/// карточки 3:4, и чтобы все товары выглядели одинаково, сохраняется ровно то, что видно в
/// рамке, — PNG [outputWidth]×[outputHeight]. Фото двигается/масштабируется пальцами, кнопка
/// поворачивает на 90°. Отдалить можно до «целиком в кадре» — пустые поля заливаются белым.
/// Возвращает байты PNG или null, если отменили.
class ImageCropScreen extends StatefulWidget {
  final Uint8List bytes;

  const ImageCropScreen({super.key, required this.bytes});

  /// Как на сервере (FileStorageService.toPortrait) — там фото уже не пересчитывается.
  static const int outputWidth = 900;
  static const int outputHeight = 1200;

  /// Меньше этого по ширине кадра (в пикселях исходника) — фото слишком мелкое/сильно увеличено.
  static const int minSourcePixels = 600;

  @override
  State<ImageCropScreen> createState() => _ImageCropScreenState();
}

class _ImageCropScreenState extends State<ImageCropScreen> {
  final _controller = TransformationController();
  ui.Image? _image;
  double _side = 0; // ширина рамки 3:4 на экране, высота — _side * 4 / 3
  double _base = 1; // масштаб, при котором фото закрывает рамку
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _decode();
  }

  Future<void> _decode() async {
    try {
      final codec = await ui.instantiateImageCodec(widget.bytes);
      final frame = await codec.getNextFrame();
      final img = frame.image;
      if (img.width < ImageCropScreen.minSourcePixels || img.height < ImageCropScreen.minSourcePixels) {
        setState(() => _error =
            'Фото слишком маленькое (${img.width}×${img.height}). Нужно не меньше ${ImageCropScreen.minSourcePixels} px по каждой стороне — снимите ближе или выберите другое.');
        return;
      }
      setState(() => _image = img);
    } catch (_) {
      setState(() => _error = 'Не удалось открыть фото');
    }
  }

  /// Поворот на 90° по часовой — перерисовываем сам ui.Image, чтобы дальше считать как обычно.
  Future<void> _rotate() async {
    final src = _image;
    if (src == null) return;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.translate(src.height.toDouble(), 0);
    canvas.rotate(1.5707963267948966);
    canvas.drawImage(src, Offset.zero, Paint()..filterQuality = FilterQuality.high);
    final rotated = await recorder.endRecording().toImage(src.height, src.width);
    setState(() {
      _image = rotated;
      _side = 0; // пересчитать масштаб и центрирование под новую ориентацию
    });
  }

  void _layout(double side) {
    final img = _image!;
    if (_side == side) return;
    _side = side;
    final sh = _frameHeight;
    final bw = side / img.width, bh = sh / img.height;
    _base = bw > bh ? bw : bh; // фото закрывает рамку целиком
    final w = img.width * _base, h = img.height * _base;
    _controller.value = Matrix4.identity()..translateByDouble(-(w - side) / 2, -(h - sh) / 2, 0, 1);
  }

  double get _frameHeight => _side * 4 / 3;

  /// Сколько пикселей исходника попадает в рамку по ширине — для проверки качества.
  double get _visibleSourcePixels => _side / (_base * _controller.value.getMaxScaleOnAxis());

  Future<void> _save() async {
    final img = _image!;
    if (_visibleSourcePixels < ImageCropScreen.minSourcePixels) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Слишком сильно увеличено — фото будет мутным. Отдалите немного.')));
      return;
    }
    setState(() => _saving = true);
    const ow = ImageCropScreen.outputWidth, oh = ImageCropScreen.outputHeight;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawRect(const Rect.fromLTWH(0, 0, ow + 0.0, oh + 0.0), Paint()..color = Colors.white);
    canvas.scale(ow / _side);
    canvas.transform(_controller.value.storage);
    canvas.scale(_base);
    canvas.drawImage(img, Offset.zero, Paint()..filterQuality = FilterQuality.high);
    final result = await recorder.endRecording().toImage(ow, oh);
    final data = await result.toByteData(format: ui.ImageByteFormat.png);
    if (!mounted) return;
    Navigator.pop(context, data!.buffer.asUint8List());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Фото товара'),
        actions: [
          if (_image != null)
            IconButton(tooltip: 'Повернуть', icon: const Icon(Icons.rotate_90_degrees_cw_outlined), onPressed: _saving ? null : _rotate),
        ],
      ),
      body: SafeArea(
        child: _error != null
            ? Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text(_error!, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white)),
                ),
              )
            : _image == null
                ? const Center(child: CircularProgressIndicator())
                : Column(
                    children: [
                      const Padding(
                        padding: EdgeInsets.fromLTRB(16, 12, 16, 12),
                        child: Text(
                          'Товар по центру и почти на всю рамку. Двигайте и увеличивайте пальцами, ↻ — повернуть.',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Colors.white70),
                        ),
                      ),
                      Expanded(
                        child: LayoutBuilder(builder: (context, c) {
                          // рамка 3:4 во весь экран: ширина ограничена и шириной, и высотой (× 3/4)
                          final byW = c.maxWidth - 32, byH = (c.maxHeight - 32) * 3 / 4;
                          final side = byW < byH ? byW : byH;
                          _layout(side);
                          final img = _image!;
                          // отдалить можно до «фото целиком в рамке»
                          final fitW = side / img.width, fitH = _frameHeight / img.height;
                          final minScale = (fitW < fitH ? fitW : fitH) / _base;
                          return Center(
                            child: Container(
                              width: side,
                              height: _frameHeight,
                              decoration: BoxDecoration(
                                color: Colors.white,
                                border: Border.all(color: Colors.white, width: 2),
                              ),
                              child: ClipRect(
                                child: InteractiveViewer(
                                  transformationController: _controller,
                                  constrained: false,
                                  minScale: minScale,
                                  maxScale: 6,
                                  boundaryMargin: EdgeInsets.all(side),
                                  child: SizedBox(
                                    width: img.width * _base,
                                    height: img.height * _base,
                                    child: RawImage(image: img, fit: BoxFit.fill, filterQuality: FilterQuality.medium),
                                  ),
                                ),
                              ),
                            ),
                          );
                        }),
                      ),
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            style: FilledButton.styleFrom(backgroundColor: context.pal.accent),
                            onPressed: _saving ? null : _save,
                            child: _saving
                                ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                                : const Text('Готово'),
                          ),
                        ),
                      ),
                    ],
                  ),
      ),
    );
  }
}
