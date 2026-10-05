import 'package:flutter/material.dart';

import '../models/product_return.dart';
import 'format.dart';
import 'receipt_card.dart';
import '../theme/app_theme.dart';

TextStyle _mono(BuildContext context) => TextStyle(fontFamily: 'monospace', fontSize: 13, color: context.pal.ink);
TextStyle _monoMuted(BuildContext context) => TextStyle(fontFamily: 'monospace', fontSize: 11.5, color: context.pal.muted);

/// Позиции возвратов в виде кассового чека: наименование · кол-во · сумма. Используется в окне
/// расчёта по заказу (несколько возвратов, строка открывает свой возврат — [onTap]) и в карточке
/// возврата ([showTotal] — строка ИТОГО под чеком).
class ReturnsReceipt extends StatelessWidget {
  final List<ProductReturn> returns;
  final void Function(ProductReturn)? onTap;
  final bool showTotal;

  const ReturnsReceipt({super.key, required this.returns, this.onTap, this.showTotal = false});

  static Widget _line(Widget name, Widget qty, Widget sum) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Expanded(child: name),
      SizedBox(
        width: 56,
        child: Align(alignment: Alignment.centerRight, child: qty),
      ),
      SizedBox(
        width: 96,
        child: Align(alignment: Alignment.centerRight, child: sum),
      ),
    ],
  );

  @override
  Widget build(BuildContext context) {
    final total = returns.fold<double>(0, (s, r) => s + r.totalSum);
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: context.pal.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _line(
            Text('НАИМЕНОВАНИЕ', style: _monoMuted(context)),
            Text('КОЛ-ВО', style: _monoMuted(context)),
            Text('СУММА', style: _monoMuted(context)),
          ),
          const SizedBox(height: 6),
          const ReceiptDashedLine(),
          const SizedBox(height: 4),
          for (final r in returns)
            for (final i in r.items)
              InkWell(
                onTap: onTap == null ? null : () => onTap!(r),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 5),
                  child: _line(
                    Text(i.productName, style: _mono(context)),
                    Text(formatQty(i.quantity), style: _mono(context)),
                    Text(formatMoney(i.sum), style: _mono(context).copyWith(fontWeight: FontWeight.w700)),
                  ),
                ),
              ),
          if (showTotal) ...[
            const SizedBox(height: 4),
            const ReceiptDashedLine(),
            const SizedBox(height: 8),
            Row(
              children: [
                const Expanded(
                  child: Text(
                    'ИТОГО',
                    style: TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w700),
                  ),
                ),
                Text(
                  formatMoney(total),
                  style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w700, fontSize: 15),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
