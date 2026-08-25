import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import '../main.dart';
import '../models/product.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/format.dart';

/// Экран "товар крупным планом": картинка на половину экрана, степпер количества
/// поверх неё и большая кнопка снизу — используется при добавлении/редактировании
/// позиции в форме заказа.
class ProductQuantityScreen extends StatefulWidget {
  final Product product;
  final double unitPrice;
  final double initialQuantity;
  final bool isEditing;

  const ProductQuantityScreen({
    super.key,
    required this.product,
    required this.unitPrice,
    this.initialQuantity = 1,
    this.isEditing = false,
  });

  @override
  State<ProductQuantityScreen> createState() => _ProductQuantityScreenState();
}

class _ProductQuantityScreenState extends State<ProductQuantityScreen> {
  late final double _step = quantityStep(widget.product.unit);
  // ValueNotifier вместо setState на весь экран: тап по +/- должен перестраивать
  // только степпер и итоговую сумму, а не всё дерево (включая Image.network) —
  // иначе на реальном телефоне кнопка ощутимо "тормозит" при декодировании фото.
  late final ValueNotifier<double> _qty =
      ValueNotifier(widget.initialQuantity <= 0 ? _step : widget.initialQuantity);

  @override
  void dispose() {
    _qty.dispose();
    super.dispose();
  }

  void _change(double delta) {
    final next = _qty.value + delta;
    _qty.value = next < _step ? _step : next;
  }

  @override
  Widget build(BuildContext context) {
    final product = widget.product;
    final imageHeight = MediaQuery.of(context).size.height * 0.46;

    return Scaffold(
      backgroundColor: Colors.white,
      body: Column(
        children: [
          SizedBox(
            height: imageHeight,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned.fill(
                  child: DecoratedBox(
                    decoration: const BoxDecoration(
                      color: bg,
                      borderRadius: BorderRadius.vertical(bottom: Radius.circular(28)),
                    ),
                    child: ClipRRect(
                      borderRadius: const BorderRadius.vertical(bottom: Radius.circular(28)),
                      child: _ProductImage(product: product),
                    ),
                  ),
                ),
                Positioned(
                  top: 8,
                  left: 12,
                  child: SafeArea(
                    bottom: false,
                    child: _RoundIconButton(
                      icon: Icons.close,
                      onTap: () => Navigator.of(context).pop(),
                    ),
                  ),
                ),
                if (widget.isEditing)
                  Positioned(
                    top: 8,
                    right: 12,
                    child: SafeArea(
                      bottom: false,
                      child: _RoundIconButton(
                        icon: Icons.delete_outline,
                        onTap: () => Navigator.of(context).pop(0.0),
                      ),
                    ),
                  ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: -26,
                  child: Center(
                    child: ValueListenableBuilder<double>(
                      valueListenable: _qty,
                      builder: (context, qty, _) => _QuantityStepper(
                        quantity: qty,
                        onDecrement: () => _change(-_step),
                        onIncrement: () => _change(_step),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 42),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        product.name,
                        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                      ),
                    ),
                    if (product.category != null && product.category!.isNotEmpty)
                      Container(
                        margin: const EdgeInsets.only(left: 8, top: 4),
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: gold.withValues(alpha: 0.16),
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: Text(
                          product.category!,
                          style: const TextStyle(color: gold, fontWeight: FontWeight.w600, fontSize: 12),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  '${formatMoney(widget.unitPrice)}${product.unit != null && product.unit!.isNotEmpty ? ' / ${product.unit}' : ''}',
                  style: const TextStyle(color: Colors.grey, fontSize: 14),
                ),
                if (product.description != null && product.description!.isNotEmpty) ...[
                  const SizedBox(height: 14),
                  Text(
                    product.description!,
                    style: const TextStyle(color: Color(0xFF6B5E52), height: 1.4),
                  ),
                ],
              ],
            ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: SizedBox(
                width: double.infinity,
                height: 52,
                child: ValueListenableBuilder<double>(
                  valueListenable: _qty,
                  builder: (context, qty, _) => FilledButton(
                    style: FilledButton.styleFrom(
                      backgroundColor: accent,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(30)),
                    ),
                    onPressed: () => Navigator.of(context).pop(qty),
                    child: Text(
                      '${widget.isEditing ? 'Сохранить' : 'Добавить'} за ${formatMoney(qty * widget.unitPrice)}',
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ProductImage extends StatelessWidget {
  final Product product;

  const _ProductImage({required this.product});

  @override
  Widget build(BuildContext context) {
    final url = product.imageUrl;
    if (url == null || url.isEmpty) {
      return Center(child: InitialAvatar(text: product.name, color: accent));
    }
    // Ограничиваем декодирование шириной экрана в физических пикселях — иначе большое
    // фото декодируется в исходном разрешении и грузит UI-поток на каждый ребилд, из-за
    // чего +/- ощутимо тормозит на телефоне. CachedNetworkImage хранит файл на диске
    // между запусками приложения и сам перевалидирует его с сервером (условный GET по
    // ETag/Last-Modified — Spring отдаёт их для статики по умолчанию) — повторный
    // запуск приложения не перекачивает то, что не изменилось на сервере.
    final cacheWidth = (MediaQuery.of(context).size.width * MediaQuery.of(context).devicePixelRatio).round();
    return CachedNetworkImage(
      imageUrl: url,
      fit: BoxFit.cover,
      width: double.infinity,
      height: double.infinity,
      memCacheWidth: cacheWidth,
      fadeInDuration: Duration.zero,
      placeholder: (context, url) => const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      errorWidget: (context, url, error) => Center(child: InitialAvatar(text: product.name, color: accent)),
    );
  }
}

class _RoundIconButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;

  const _RoundIconButton({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: Container(
          width: 44,
          height: 44,
          alignment: Alignment.center,
          decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle, boxShadow: [
            BoxShadow(color: Color(0x33000000), blurRadius: 6, offset: Offset(0, 2)),
          ]),
          child: Icon(icon, size: 20, color: ink),
        ),
      ),
    );
  }
}

class _QuantityStepper extends StatelessWidget {
  final double quantity;
  final VoidCallback onDecrement;
  final VoidCallback onIncrement;

  const _QuantityStepper({required this.quantity, required this.onDecrement, required this.onIncrement});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(30),
        boxShadow: const [BoxShadow(color: Color(0x33000000), blurRadius: 10, offset: Offset(0, 3))],
      ),
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _button(Icons.remove, onDecrement),
          SizedBox(
            width: 32,
            child: Text(
              formatQty(quantity),
              textAlign: TextAlign.center,
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
            ),
          ),
          _button(Icons.add, onIncrement),
        ],
      ),
    );
  }

  Widget _button(IconData icon, VoidCallback onTap) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: Container(
          width: 44,
          height: 44,
          alignment: Alignment.center,
          decoration: const BoxDecoration(color: bg, shape: BoxShape.circle),
          child: Icon(icon, size: 18, color: ink),
        ),
      ),
    );
  }
}
