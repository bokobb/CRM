import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class StatusBadge extends StatelessWidget {
  final String status;
  final String label;

  /// Например, "Оприходовано" на карточке заявки, если что-то оприходовано меньше нормы —
  /// красным вместо обычного зелёного (см. production_requests_screen.dart).
  final bool insufficient;

  const StatusBadge({super.key, required this.status, required this.label, this.insufficient = false});

  Color _color(BuildContext context) {
    final p = context.pal;
    if (insufficient) return p.danger;
    switch (status) {
      case 'PLACED':
        return p.accent;
      case 'NEW':
      case 'PENDING':
        return p.warn;
      case 'CONFIRMED':
      case 'IN_PRODUCTION':
      case 'IN_PROGRESS':
      case 'COLLECTED':
      case 'READY':
        return p.info;
      case 'DELIVERED':
      case 'DONE':
      case 'RECEIVED':
        return p.success;
      case 'CANCELLED':
      case 'NO_SHOW':
        return p.danger;
      default:
        return p.muted;
    }
  }

  /// Строгий вид: точка + подпись цветом статуса, без цветной плашки.
  @override
  Widget build(BuildContext context) {
    final c = _color(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(width: 7, height: 7, decoration: BoxDecoration(color: c, shape: BoxShape.circle)),
        const SizedBox(width: 6),
        Text(label, style: TextStyle(color: c, fontSize: 12.5, fontWeight: FontWeight.w500)),
      ],
    );
  }
}
