import 'package:flutter/material.dart';

const _receiptInk = Color(0xFF2B1F1A);
const _receiptMuted = Color(0xFFB8A88F);
const _receiptLine = Color(0xFFE3D9C8);
const _receiptAccent = Color(0xFF8F2438);
const _receiptInsufficient = Color(0xFFC0392B);

/// Карточка-«чек»: используется там, где нужно показать состав заявки/списка
/// позиций в узнаваемом виде кассового чека (шапка, пунктирные разделители,
/// построчный список с точечным лидером между названием и количеством,
/// «оторванный» нижний край).
class ReceiptCard extends StatelessWidget {
  final String title;
  final IconData icon;
  final List<Widget> children;
  final Widget? footer;

  const ReceiptCard({
    super.key,
    required this.title,
    this.icon = Icons.receipt_long_rounded,
    required this.children,
    this.footer,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.07),
            blurRadius: 16,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 18, 18, 10),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 15, color: _receiptAccent),
                const SizedBox(width: 6),
                Text(
                  title.toUpperCase(),
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 12.5,
                    letterSpacing: 1.4,
                    color: _receiptAccent,
                  ),
                ),
              ],
            ),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 18),
            child: _DashedLine(),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 8, 18, 6),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
          ),
          if (footer != null) ...[
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 18),
              child: _DashedLine(),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 10, 18, 14),
              child: footer!,
            ),
          ] else
            const SizedBox(height: 10),
          const _ReceiptBarcode(),
        ],
      ),
    );
  }
}

/// Одна строка чека: № · название · точечный лидер · количество.
/// Если передан [checkbox], название и слот под номер заменяются чекбоксом —
/// используется в режиме сборки заявки (отметить каждую позицию).
class ReceiptRow extends StatelessWidget {
  final int index;
  final String name;
  final String qty;
  final bool? checkbox;
  final ValueChanged<bool?>? onCheckboxChanged;

  /// Сырья/товара на складе меньше, чем указано в qty — строка красным, чтобы было видно
  /// сразу в чек-листе, не дожидаясь ошибки при нажатии "Собрано".
  final bool insufficient;

  /// Необязательное действие в конце строки (например, кнопка "заменить сырьё") — не
  /// участвует в общем onTap строки (переключение чекбокса), т.к. сам ловит нажатие первым.
  final Widget? trailingAction;

  const ReceiptRow({
    super.key,
    required this.index,
    required this.name,
    required this.qty,
    this.checkbox,
    this.onCheckboxChanged,
    this.insufficient = false,
    this.trailingAction,
  });

  @override
  Widget build(BuildContext context) {
    final checked = checkbox ?? false;
    final insufficientColor = insufficient && !checked ? _receiptInsufficient : null;
    final row = Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          if (checkbox != null)
            SizedBox(
              width: 30,
              height: 30,
              child: Checkbox(
                value: checked,
                onChanged: onCheckboxChanged,
                visualDensity: VisualDensity.compact,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
            )
          else
            SizedBox(
              width: 22,
              child: Text(
                '$index',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12, color: _receiptMuted),
              ),
            ),
          const SizedBox(width: 4),
          // Без ограничения на число строк — раньше длинное название обрезалось многоточием
          // (maxLines: 2) и часть могла быть не видна кладовщику; теперь просто переносится
          // на следующую строку, если не помещается, а не прячется. Пунктир — чисто
          // декоративный разделитель, поэтому название получает бОльшую часть ширины (flex 3
          // против 1), чтобы типичные названия вроде "Подсолнечное масло" помещались в одну строку.
          Expanded(
            flex: 3,
            child: Text(
              name,
              softWrap: true,
              style: TextStyle(
                fontFamily: 'monospace',
                fontSize: 13.5,
                fontWeight: insufficientColor != null ? FontWeight.w700 : null,
                height: 1.2,
                color: insufficientColor ?? (checked ? _receiptMuted : _receiptInk),
                decoration: checked ? TextDecoration.lineThrough : null,
                decorationColor: _receiptMuted,
              ),
            ),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: _DottedLeader(color: checked ? const Color(0xFFEDE6D9) : _receiptLine),
            ),
          ),
          Text(
            qty,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 13.5,
              fontWeight: FontWeight.w700,
              color: insufficientColor ?? (checked ? _receiptMuted : _receiptInk),
            ),
          ),
          if (trailingAction != null) ...[
            const SizedBox(width: 4),
            trailingAction!,
          ],
        ],
      ),
    );
    if (checkbox == null || onCheckboxChanged == null) return row;
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () => onCheckboxChanged!(!checked),
      child: row,
    );
  }
}

class _DottedLeader extends StatelessWidget {
  final Color color;
  const _DottedLeader({required this.color});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        const step = 5.0;
        final count = (constraints.maxWidth / step).floor().clamp(0, 200);
        return Text(
          '.' * count,
          maxLines: 1,
          overflow: TextOverflow.clip,
          softWrap: false,
          style: TextStyle(fontFamily: 'monospace', fontSize: 13, color: color, height: 1),
        );
      },
    );
  }
}

class _DashedLine extends StatelessWidget {
  const _DashedLine();

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        const dashWidth = 5.0;
        const dashSpace = 4.0;
        final count = (constraints.maxWidth / (dashWidth + dashSpace)).floor();
        return SizedBox(
          height: 1,
          child: Row(
            children: List.generate(
              count,
              (_) => Padding(
                padding: const EdgeInsets.only(right: dashSpace),
                child: Container(width: dashWidth, height: 1, color: _receiptLine),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Декоративная полоска-«штрихкод» внизу чека — псевдослучайный, но
/// стабильный (без Random) узор из полос разной ширины.
class _ReceiptBarcode extends StatelessWidget {
  const _ReceiptBarcode();

  static const _pattern = [3, 1, 2, 1, 4, 1, 1, 2, 3, 1, 1, 4, 2, 1, 3, 1, 2, 2, 1, 3, 1, 1, 4, 1, 2];

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: const BorderRadius.vertical(bottom: Radius.circular(14)),
      child: Container(
        height: 20,
        padding: const EdgeInsets.symmetric(horizontal: 18),
        alignment: Alignment.center,
        color: const Color(0xFFFBF9F5),
        child: Row(
          children: [
            for (final w in _pattern)
              Padding(
                padding: const EdgeInsets.only(right: 2),
                child: Container(width: w.toDouble(), height: 14, color: const Color(0xFFD8CEBB)),
              ),
          ],
        ),
      ),
    );
  }
}
