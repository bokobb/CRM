import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Свайп карточки влево: первый раз — «Отменить» (всё откатывается, документы в 1С
/// распроводятся и остаются черновиками), у уже отменённой — корзина «Удалить» (документы в 1С
/// помечаются на удаление). Действие подтверждается диалогом; карточка сама не исчезает —
/// после действия список обновляется ([onDone]).
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
  });

  static Color _red(BuildContext context) => context.pal.danger;

  @override
  Widget build(BuildContext context) {
    final deleting = cancelled;
    if (deleting ? !canDelete : !canCancel) return child;
    return Dismissible(
      key: ValueKey(('swipe', itemKey, deleting)),
      direction: DismissDirection.endToStart,
      dismissThresholds: const {DismissDirection.endToStart: 0.35},
      background: Padding(
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
      ),
      confirmDismiss: (_) async {
        await _run(context, deleting);
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
              ? 'Будет удалено из CRM, документы в 1С помечаются на удаление.'
              : 'Все действия откатываются, документы в 1С будут распроведены и останутся черновиками.',
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
