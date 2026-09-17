import 'package:flutter/material.dart';

class StatusBadge extends StatelessWidget {
  final String status;
  final String label;

  /// Например, "Оприходовано" на карточке заявки, если что-то оприходовано меньше нормы —
  /// красным вместо обычного зелёного (см. production_requests_screen.dart).
  final bool insufficient;

  const StatusBadge({super.key, required this.status, required this.label, this.insufficient = false});

  Color _bg(BuildContext context) {
    if (insufficient) return const Color(0xFFF6E0DF);
    switch (status) {
      case 'NEW':
      case 'PENDING':
        return const Color(0xFFF7EDD6);
      case 'CONFIRMED':
      case 'IN_PRODUCTION':
      case 'IN_PROGRESS':
      case 'COLLECTED':
        return const Color(0xFFF4E3DC);
      case 'READY':
      case 'DONE':
      case 'RECEIVED':
        return const Color(0xFFE3F0E6);
      case 'DELIVERED':
        return const Color(0xFFE5E5E5);
      case 'COMPLETED':
        return const Color(0xFFEFE3C2);
      case 'CANCELLED':
        return const Color(0xFFF6E0DF);
      default:
        return const Color(0xFFEEEEEE);
    }
  }

  Color _fg(BuildContext context) {
    if (insufficient) return const Color(0xFFB8433F);
    switch (status) {
      case 'NEW':
      case 'PENDING':
        return const Color(0xFFC99A2E);
      case 'CONFIRMED':
      case 'IN_PRODUCTION':
      case 'IN_PROGRESS':
      case 'COLLECTED':
        return const Color(0xFF8F2438);
      case 'READY':
      case 'DONE':
      case 'RECEIVED':
        return const Color(0xFF4C8A5E);
      case 'DELIVERED':
        return const Color(0xFF555555);
      case 'COMPLETED':
        return const Color(0xFF9A7A1E);
      case 'CANCELLED':
        return const Color(0xFFB8433F);
      default:
        return const Color(0xFF777777);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: _bg(context),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(color: _fg(context), fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }
}
