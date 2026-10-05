import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// AppBar с сворачиваемым поиском: обычно просто заголовок + иконка-лупа справа; после тапа
/// заголовок заменяется текстовым полем, ввод сразу фильтрует список через [onQueryChanged]
/// (крестик закрывает поле и сбрасывает фильтр). Используется на списках товаров/клиентов/
/// сырья и т.п. — везде, где фильтрация происходит на уже загруженном списке, без обращения
/// к серверу на каждую букву.
class SearchableAppBar extends StatefulWidget implements PreferredSizeWidget {
  final String title;
  final ValueChanged<String> onQueryChanged;
  final String hintText;
  final List<Widget>? extraActions;

  const SearchableAppBar({
    super.key,
    required this.title,
    required this.onQueryChanged,
    this.hintText = 'Поиск...',
    this.extraActions,
  });

  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);

  @override
  State<SearchableAppBar> createState() => _SearchableAppBarState();
}

class _SearchableAppBarState extends State<SearchableAppBar> {
  bool _searching = false;
  final _controller = TextEditingController();

  void _open() => setState(() => _searching = true);

  void _close() {
    _controller.clear();
    widget.onQueryChanged('');
    setState(() => _searching = false);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AppBar(
      title: _searching
          ? TextField(
              controller: _controller,
              autofocus: true,
              style: TextStyle(color: context.pal.ink),
              cursorColor: context.pal.ink,
              decoration: InputDecoration(
                hintText: widget.hintText,
                hintStyle: const TextStyle(color: Color(0x992B1F1A)),
                border: InputBorder.none,
                filled: false,
                contentPadding: EdgeInsets.zero,
              ),
              onChanged: widget.onQueryChanged,
            )
          : Text(widget.title),
      actions: [
        if (_searching)
          IconButton(icon: const Icon(Icons.close), tooltip: 'Закрыть поиск', onPressed: _close)
        else ...[
          IconButton(icon: const Icon(Icons.search), tooltip: 'Поиск', onPressed: _open),
          ...?widget.extraActions,
        ],
      ],
    );
  }
}
