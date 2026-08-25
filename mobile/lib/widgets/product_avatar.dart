import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import 'app_list_tile.dart';

/// Круглая миниатюра товара для списков — фото, если оно есть, иначе буква названия
/// (тот же InitialAvatar, что и раньше). Единая точка, чтобы фото сразу появлялось везде,
/// где сейчас применяется (заказы, остатки, рецептура, товары, выбор товара).
class ProductAvatar extends StatelessWidget {
  final String name;
  final String? imageUrl;
  final Color? color;
  final double radius;

  const ProductAvatar({
    super.key,
    required this.name,
    this.imageUrl,
    this.color,
    this.radius = 20,
  });

  @override
  Widget build(BuildContext context) {
    final url = imageUrl;
    final size = radius * 2;
    if (url == null || url.isEmpty) {
      return InitialAvatar(text: name, color: color);
    }
    return ClipOval(
      child: CachedNetworkImage(
        imageUrl: url,
        width: size,
        height: size,
        fit: BoxFit.cover,
        memCacheWidth: (size * 2).round(),
        fadeInDuration: Duration.zero,
        placeholder: (context, url) => SizedBox(
          width: size,
          height: size,
          child: const Center(child: CircularProgressIndicator(strokeWidth: 1.5)),
        ),
        errorWidget: (context, url, error) => InitialAvatar(text: name, color: color),
      ),
    );
  }
}
