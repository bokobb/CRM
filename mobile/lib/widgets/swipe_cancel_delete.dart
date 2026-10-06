import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Свайп карточки влево: первый раз — «Отменить» (всё откатывается, документы в 1С
/// распроводятся и остаются черновиками), у уже отменённой — корзина «Удалить» (документы в 1С
/// помечаются на удаление). Действие подтверждается диалогом; карточка сама не исчезает —
/// после действия список обновляется ([onDone]). Необязательный свайп вправо — [onSwipeRight]
/// (например, «Объём и масса» у заказа): без подтверждения, карточка тоже остаётся на месте.
class SwipeCancelDelete extends StatelessWidget {
  final Object itemKey;
  final bool cancelled;
  final bool canCancel;
  final bool canDelete;

  /// Название объекта для диалога — «заказ №12», «заявку №150».
  final String subject;
  final Future<void> Function() onCancel;
  final Future<void> Function() onDelete;
  final VoidCallback onDone;
  final Widget child;
  final VoidCallback? onSwipeRight;

  /// Свой текст диалога (что будет в 1С), если правило отличается от заказов — у заявок
  /// отмена помечает документы на удаление, а удаление в 1С ничего не отправляет.
  final String? cancelHint;
  final String? deleteHint;
  final String? swipeRightLabel;
  final IconData? swipeRightIcon;

  const SwipeCancelDelete({
    super.key,
    required this.itemKey,
    required this.cancelled,
    required this.canCancel,
    required this.canDelete,
    required this.subject,
    required this.onCancel,
    required this.onDelete,
    required this.onDone,
    required this.child,
    this.onSwipeRight,
    this.cancelHint,
    this.deleteHint,
    this.swipeRightLabel,
    this.swipeRightIcon,
  });

  static Color _red(BuildContext context) => context.pal.danger;

  @override
  Widget build(BuildContext context) {
    final deleting = cancelled;
    final left = deleting ? canDelete : canCancel;
    final right = onSwipeRight != null;
    if (!left && !right) return child;
    final leftBackground = Padding(
      // Карточка (AppListTile) сама отступает снизу на 10 — фон повторяет её форму.
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.symmetric(horizontal: 22),
        decoration: BoxDecoration(color: _red(context), borderRadius: BorderRadius.circular(12)),
        child: deleting
            ? const Icon(Icons.delete_outline, color: Colors.white, size: 28)
            : const Text(
                'Отменить',
                style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16),
              ),
      ),
    );
    final rightBackground = Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 22),
        decoration: BoxDecoration(color: context.pal.accent, borderRadius: BorderRadius.circular(12)),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(swipeRightIcon ?? Icons.description_outlined, color: Colors.white, size: 22),
            const SizedBox(width: 8),
            Text(
              swipeRightLabel ?? '',
              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16),
            ),
          ],
        ),
      ),
    );
    return Dismissible(
      key: ValueKey(('swipe', itemKey, deleting)),
      direction: left && right
          ? DismissDirection.horizontal
          : right
          ? DismissDirection.startToEnd
          : DismissDirection.endToStart,
      dismissThresholds: const {DismissDirection.endToStart: 0.35, DismissDirection.startToEnd: 0.35},
      background: right ? rightBackground : leftBackground,
      secondaryBackground: right && left ? leftBackground : null,
      confirmDismiss: (direction) async {
        if (direction == DismissDirection.startToEnd) {
          onSwipeRight!();
        } else {
          await _run(context, deleting);
        }
        return false;
      },
      child: child,
    );
  }

  Future<void> _run(BuildContext context, bool deleting) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(deleting ? 'Удалить $subject?' : 'Отменить $subject?'),
        content: Text(
          deleting
              ? (deleteHint ?? 'Будет удалено из CRM, документы в 1С помечаются на удаление.')
              : (cancelHint ?? 'Все действия откатываются, документы в 1С будут распроведены и останутся черновиками.'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Нет')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: _red(context)),
            child: Text(deleting ? 'Удалить' : 'Отменить'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await (deleting ? onDelete() : onCancel());
      messenger.showSnackBar(SnackBar(content: Text(deleting ? 'Удалено: $subject' : 'Отменено: $subject')));
      onDone();
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Не удалось: $e')));
    }
  }
}
