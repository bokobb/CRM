import 'package:flutter/material.dart';

import 'app_list_tile.dart';

/// Полноэкранный bottom sheet с полем поиска — тап открывает лист, ввод сразу фильтрует
/// список. Единственная реализация выбора товара/клиента/сырья из списка во всём
/// приложении (заказ, возврат, карточка заказа, рецепт, WhatsApp-разбор и т.п.).
Future<T?> pickFromList<T>({
  required BuildContext context,
  required String title,
  required List<T> items,
  required String Function(T) label,
  String Function(T)? subtitle,
  Widget Function(T)? leading,
}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (context) => _PickerSheet<T>(
      title: title,
      items: items,
      label: label,
      subtitle: subtitle,
      leading: leading,
    ),
  );
}

class _PickerSheet<T> extends StatefulWidget {
  final String title;
  final List<T> items;
  final String Function(T) label;
  final String Function(T)? subtitle;
  final Widget Function(T)? leading;

  const _PickerSheet({
    required this.title,
    required this.items,
    required this.label,
    this.subtitle,
    this.leading,
  });

  @override
  State<_PickerSheet<T>> createState() => _PickerSheetState<T>();
}

class _PickerSheetState<T> extends State<_PickerSheet<T>> {
  final _focus = FocusNode();
  var _query = '';
  Animation<double>? _routeAnimation;

  /// Фокус (и клавиатура) — только когда лист уже доехал до места, а не через autofocus.
  /// В Safari на iPhone (веб-версия) autofocus срабатывал, пока лист ещё внизу экрана:
  /// Safari прокручивал страницу, чтобы показать поле над клавиатурой, и после этого
  /// касания приходились со сдвигом — нажатие на товар выбирало соседний.
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_routeAnimation != null) return;
    _routeAnimation = ModalRoute.of(context)?.animation;
    if (_routeAnimation == null || _routeAnimation!.isCompleted) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _requestFocus());
    } else {
      _routeAnimation!.addStatusListener(_onRouteStatus);
    }
  }

  void _onRouteStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed) return;
    _routeAnimation!.removeStatusListener(_onRouteStatus);
    _requestFocus();
  }

  void _requestFocus() {
    if (mounted) _focus.requestFocus();
  }

  @override
  void dispose() {
    _routeAnimation?.removeStatusListener(_onRouteStatus);
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final label = widget.label;
    final q = _query.toLowerCase();
    final filtered = q.isEmpty ? widget.items : widget.items.where((i) => label(i).toLowerCase().contains(q)).toList();
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SizedBox(
        height: MediaQuery.of(context).size.height * 0.75,
        child: Column(
          children: [
            const SizedBox(height: 10),
            Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: const Color(0xFFE7E0D8),
                borderRadius: BorderRadius.circular(4),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
              child: TextField(
                focusNode: _focus,
                decoration: InputDecoration(labelText: widget.title, prefixIcon: const Icon(Icons.search)),
                onChanged: (v) => setState(() => _query = v),
              ),
            ),
            Expanded(
              child: ListView.builder(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                itemCount: filtered.length,
                itemBuilder: (context, i) {
                  final item = filtered[i];
                  return AppListTile(
                    // Ключ по самому элементу: если список перефильтруется прямо во время
                    // нажатия, тап не перейдёт на другой товар, оказавшийся на той же позиции.
                    key: ObjectKey(item),
                    leading: widget.leading != null ? widget.leading!(item) : InitialAvatar(text: label(item)),
                    title: label(item),
                    subtitle: widget.subtitle != null ? widget.subtitle!(item) : null,
                    onTap: () => Navigator.pop(context, item),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
