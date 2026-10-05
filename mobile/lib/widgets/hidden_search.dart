import 'package:flutter/material.dart';

const _matchStyle = TextStyle(
  color: Color(0xFFD32F2F),
  fontWeight: FontWeight.w800,
  backgroundColor: Color(0x22D32F2F),
);

/// Текст, в котором совпадения с [query] (без учёта регистра) выделены красным.
List<TextSpan> highlightSpans(String text, String? query) {
  final q = query?.trim().toLowerCase() ?? '';
  if (q.isEmpty) return [TextSpan(text: text)];
  final lower = text.toLowerCase();
  final spans = <TextSpan>[];
  var start = 0;
  while (true) {
    final i = lower.indexOf(q, start);
    if (i < 0) break;
    if (i > start) spans.add(TextSpan(text: text.substring(start, i)));
    spans.add(TextSpan(text: text.substring(i, i + q.length), style: _matchStyle));
    start = i + q.length;
  }
  if (start < text.length) spans.add(TextSpan(text: text.substring(start)));
  return spans;
}

/// Поиск без поля ввода: лупа открывает клавиатуру, набранное нигде не показывается — только
/// фильтрует список и подсвечивается красным в названиях ([highlightSpans]). Повторное
/// нажатие на лупу сбрасывает поиск. Экран держит один экземпляр и передаёт свой setState.
class HiddenSearch {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  bool open = false;
  String query = '';

  bool matches(String text) => query.isEmpty || text.toLowerCase().contains(query);

  void clear(void Function(VoidCallback) setState) => setState(() {
        _controller.clear();
        query = '';
      });

  Widget button(void Function(VoidCallback) setState, {Color? color}) => IconButton(
        iconSize: 28,
        tooltip: open ? 'Сбросить поиск' : 'Поиск',
        color: color,
        icon: Icon(open ? Icons.search_off : Icons.search),
        onPressed: () => setState(() {
          open = !open;
          if (open) {
            _focus.requestFocus();
          } else {
            _controller.clear();
            query = '';
            _focus.unfocus();
          }
        }),
      );

  /// Невидимое поле — только чтобы открылась клавиатура. Ставить в дерево виджетов экрана.
  Widget field(void Function(VoidCallback) setState) => !open
      ? const SizedBox.shrink()
      : SizedBox(
          height: 1,
          child: Opacity(
            opacity: 0,
            child: TextField(
              controller: _controller,
              focusNode: _focus,
              autofocus: true,
              enableInteractiveSelection: false,
              decoration: const InputDecoration(border: InputBorder.none, isCollapsed: true),
              onChanged: (v) => setState(() => query = v.trim().toLowerCase()),
            ),
          ),
        );

  void dispose() {
    _controller.dispose();
    _focus.dispose();
  }
}
