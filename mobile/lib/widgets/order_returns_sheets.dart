import 'package:flutter/material.dart';

import '../models/product_return.dart';
import '../screens/returns_screen.dart';
import '../services/api_client.dart';
import 'error_view.dart';
import 'format.dart';

/// Расчёт по заказу с учётом закреплённых возвратов: "Заказ − Возврат = Итого" — открывается
/// долгим нажатием на карточку заказа (см. orders_screen.dart). Отсюда же открывается любой
/// возврат (изменить/удалить) и закрепляется созданный ранее в разделе "Возвраты".
Future<void> showOrderReturnsCalculation(
  BuildContext context, {
  required int orderId,
  required int clientId,
  required String clientName,
  required double orderTotal,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) =>
        _CalculationSheet(orderId: orderId, clientId: clientId, clientName: clientName, orderTotal: orderTotal),
  );
}

class _CalculationSheet extends StatefulWidget {
  final int orderId;
  final int clientId;
  final String clientName;
  final double orderTotal;

  const _CalculationSheet({
    required this.orderId,
    required this.clientId,
    required this.clientName,
    required this.orderTotal,
  });

  @override
  State<_CalculationSheet> createState() => _CalculationSheetState();
}

class _CalculationSheetState extends State<_CalculationSheet> {
  late Future<List<ProductReturn>> _future = ApiClient().getOrderReturns(widget.orderId);

  void _reload() => setState(() => _future = ApiClient().getOrderReturns(widget.orderId));

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: FutureBuilder<List<ProductReturn>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
            }
            if (snapshot.hasError) {
              return SizedBox(
                height: 140,
                child: ErrorView(error: snapshot.error.toString(), onRetry: _reload),
              );
            }
            final returns = snapshot.data!;
            final returnTotal = returns.fold<double>(0, (s, r) => s + r.totalSum);
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Заказ #${widget.orderId} · ${widget.clientName}', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 12),
                _row('Сумма заказа', formatMoney(widget.orderTotal)),
                if (returns.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 8),
                    child: Text('Возвратов по заказу нет', style: TextStyle(color: Colors.grey)),
                  )
                else ...[
                  _row('Возврат (${returns.length})', '−${formatMoney(returnTotal)}', color: returnColor),
                  for (final r in returns)
                    InkWell(
                      onTap: () => showReturnDetails(context, r, onChanged: _reload),
                      child: Padding(
                        padding: const EdgeInsets.only(left: 12, top: 2, bottom: 2),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            for (final i in r.items)
                              Text(
                                '• ${i.productName} ${formatQty(i.quantity)} × ${formatMoney(i.price)}'
                                '  (№${r.id}, ${formatDate(r.returnDate)})',
                                style: const TextStyle(color: Colors.grey, fontSize: 12.5),
                              ),
                          ],
                        ),
                      ),
                    ),
                ],
                const Divider(height: 24),
                _row('Итого', formatMoney(widget.orderTotal - returnTotal), bold: true),
                const SizedBox(height: 4),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    icon: const Icon(Icons.link, size: 18),
                    label: const Text('Закрепить созданный ранее возврат'),
                    onPressed: () async {
                      await showAttachReturnSheet(
                        context,
                        orderId: widget.orderId,
                        clientId: widget.clientId,
                        clientName: widget.clientName,
                      );
                      _reload();
                    },
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _row(String label, String value, {Color? color, bool bold = false}) {
    final style = TextStyle(fontSize: 16, color: color, fontWeight: bold ? FontWeight.bold : null);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style),
        ],
      ),
    );
  }
}

/// Закрепить за заказом один из ещё не закреплённых возвратов того же клиента (или открепить
/// уже закреплённый). После закрытия список заказов стоит обновить — суммы могли поменяться.
Future<void> showAttachReturnSheet(
  BuildContext context, {
  required int orderId,
  required int clientId,
  required String clientName,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _AttachSheet(orderId: orderId, clientId: clientId, clientName: clientName),
  );
}

class _AttachSheet extends StatefulWidget {
  final int orderId;
  final int clientId;
  final String clientName;

  const _AttachSheet({required this.orderId, required this.clientId, required this.clientName});

  @override
  State<_AttachSheet> createState() => _AttachSheetState();
}

class _AttachSheetState extends State<_AttachSheet> {
  final _api = ApiClient();
  late Future<(List<ProductReturn>, List<ProductReturn>)> _future = _load();
  bool _busy = false;

  Future<(List<ProductReturn>, List<ProductReturn>)> _load() async {
    final results = await Future.wait([
      _api.getOrderReturns(widget.orderId),
      _api.getUnattachedReturns(widget.clientId),
    ]);
    return (results[0], results[1]);
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
      setState(() => _future = _load());
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: FutureBuilder<(List<ProductReturn>, List<ProductReturn>)>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting && !snapshot.hasData) {
              return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
            }
            if (snapshot.hasError) {
              return SizedBox(
                height: 140,
                child: ErrorView(error: snapshot.error.toString(), onRetry: () => setState(() => _future = _load())),
              );
            }
            final (attached, available) = snapshot.data!;
            return ConstrainedBox(
              constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.75),
              child: ListView(
                shrinkWrap: true,
                children: [
                  Text('Возвраты по заказу #${widget.orderId}', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 4),
                  Text(widget.clientName, style: const TextStyle(color: Colors.grey)),
                  if (_busy) const LinearProgressIndicator(),
                  const SizedBox(height: 12),
                  if (attached.isNotEmpty) ...[
                    Text('Закреплены', style: Theme.of(context).textTheme.titleSmall),
                    for (final r in attached)
                      _tile(
                        r,
                        trailing: IconButton(
                          icon: const Icon(Icons.link_off),
                          tooltip: 'Открепить',
                          onPressed: _busy ? null : () => _run(() => _api.detachReturn(r.id)),
                        ),
                      ),
                    const SizedBox(height: 12),
                  ],
                  Text('Можно закрепить', style: Theme.of(context).textTheme.titleSmall),
                  if (available.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 12),
                      child: Text(
                        'Незакреплённых возвратов этого клиента нет — создайте возврат в разделе «Возвраты»',
                        style: TextStyle(color: Colors.grey),
                      ),
                    )
                  else
                    for (final r in available)
                      _tile(
                        r,
                        trailing: FilledButton.tonal(
                          onPressed: _busy ? null : () => _run(() => _api.attachReturn(r.id, widget.orderId)),
                          child: const Text('Закрепить'),
                        ),
                      ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _tile(ProductReturn r, {required Widget trailing}) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.assignment_return_outlined, color: returnColor),
      title: Text('№${r.id} · ${formatMoney(r.totalSum)}'),
      subtitle: Text(
        '${formatDate(r.returnDate)} · ${r.items.map((i) => '${i.productName} × ${formatQty(i.quantity)}').join(', ')}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: trailing,
      onTap: () => showReturnDetails(context, r),
    );
  }
}
