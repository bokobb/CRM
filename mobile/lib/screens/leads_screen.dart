import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/callback_request.dart';
import '../models/vacancy.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// «Отклики и заявки» — всё, что люди оставили на сайте, отрабатывается здесь:
/// «Заявки» — форма «Перезвоните мне» (ADMIN, ACCOUNTANT, SALES);
/// «Отклики» — на вакансии (только ADMIN/ACCOUNTANT: в них личные данные соискателей).
/// Заменил пункт меню «WhatsApp-заказы».
class LeadsScreen extends StatefulWidget {
  const LeadsScreen({super.key});

  @override
  State<LeadsScreen> createState() => _LeadsScreenState();
}

bool get _canSeeReplies => const {'ADMIN', 'ACCOUNTANT'}.contains(ApiClient.currentSession?.role);

/// Звонок по номеру из заявки/отклика.
Future<bool> _call(String phone) => launchUrl(Uri(scheme: 'tel', path: phone.replaceAll(RegExp(r'[^0-9+]'), '')));

class _LeadsScreenState extends State<LeadsScreen> with SingleTickerProviderStateMixin {
  final _api = ApiClient();
  late final TabController _tabs = TabController(length: _canSeeReplies ? 2 : 1, vsync: this);
  List<CallbackRequest>? _callbacks;
  List<VacancyReply>? _replies;
  String? _error;

  /// Фильтры: null — все; у откликов vacancy '' — резерв.
  String? _callbackStatus;
  String? _replyStatus;
  String? _replyVacancy;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final callbacks = await _api.getCallbackRequests();
      final replies = _canSeeReplies ? await _api.getVacancyReplies() : <VacancyReply>[];
      if (!mounted) return;
      setState(() {
        _callbacks = callbacks;
        _replies = replies;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
  }

  Future<void> _openCallback(CallbackRequest r) async {
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _CallbackSheet(request: r),
    );
    if (changed == true) _load();
  }

  Future<void> _openReply(VacancyReply r) async {
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _ReplySheet(reply: r),
    );
    if (changed == true) _load();
  }

  Widget _tabLabel(String text, int newCount) => Badge(
        isLabelVisible: newCount > 0,
        label: Text('$newCount'),
        offset: const Offset(14, -4),
        child: Text(text),
      );

  @override
  Widget build(BuildContext context) {
    final newCallbacks = _callbacks?.where((r) => r.status == 'NEW').length ?? 0;
    final newReplies = _replies?.where((r) => r.status == 'NEW').length ?? 0;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Отклики и заявки'),
        bottom: TabBar(
          controller: _tabs,
          tabs: [
            Tab(child: _tabLabel('Заявки', newCallbacks)),
            if (_canSeeReplies) Tab(child: _tabLabel('Отклики', newReplies)),
          ],
        ),
      ),
      drawer: const AppDrawer(currentRoute: '/leads'),
      bottomNavigationBar: const AppBottomNav(currentRoute: '/leads'),
      body: _error != null
          ? ErrorView(error: _error!, onRetry: _load)
          : _callbacks == null
              ? const Center(child: CircularProgressIndicator())
              : TabBarView(controller: _tabs, children: [
                  _callbackList(),
                  if (_canSeeReplies) _replyList(),
                ]),
    );
  }

  Widget _note(String text) => Padding(
        padding: const EdgeInsets.all(28),
        child: Text(text, textAlign: TextAlign.center, style: TextStyle(color: context.pal.muted)),
      );

  Widget _statusFilter(String? value, Map<String, String> statuses, ValueChanged<String?> onChanged) =>
      DropdownButtonFormField<String?>(
        initialValue: value,
        isExpanded: true,
        decoration: const InputDecoration(labelText: 'Статус', border: OutlineInputBorder(), isDense: true),
        items: [
          const DropdownMenuItem(value: null, child: Text('Все')),
          for (final s in statuses.entries) DropdownMenuItem(value: s.key, child: Text(s.value)),
        ],
        onChanged: onChanged,
      );

  Widget _callbackList() {
    final all = _callbacks!;
    final shown = all.where((r) => _callbackStatus == null || r.status == _callbackStatus).toList();
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 24),
        children: [
          _statusFilter(_callbackStatus, callbackStatuses, (v) => setState(() => _callbackStatus = v)),
          const SizedBox(height: 10),
          if (shown.isEmpty)
            _note(all.isEmpty
                ? 'Заявок пока нет. Они приходят с сайта — форма «Перезвоните мне» на главной и на «Контактах»'
                : 'Под фильтр ничего не подходит'),
          for (final r in shown)
            Card(
              margin: const EdgeInsets.only(bottom: 8),
              child: ListTile(
                leading: Icon(r.status == 'NEW' ? Icons.fiber_new_outlined : Icons.phone_callback_outlined,
                    color: r.status == 'NEW' ? Theme.of(context).colorScheme.primary : context.pal.muted),
                title: Text(r.name ?? 'Без имени', style: const TextStyle(fontWeight: FontWeight.w600)),
                subtitle: Text(
                  '${r.phone}${r.business == null ? '' : ' · ${r.business}'}\n'
                  '${formatDateTime(r.createdAt)} · ${callbackStatuses[r.status] ?? r.status}',
                  style: TextStyle(color: context.pal.muted),
                ),
                isThreeLine: true,
                trailing: IconButton(
                  tooltip: 'Позвонить',
                  icon: const Icon(Icons.call_outlined),
                  onPressed: () => _call(r.phone),
                ),
                onTap: () => _openCallback(r),
              ),
            ),
        ],
      ),
    );
  }

  Widget _replyList() {
    final all = _replies!;
    final shown = all.where((r) {
      if (_replyStatus != null && r.status != _replyStatus) return false;
      if (_replyVacancy == '') return r.isReserve;
      if (_replyVacancy != null) return r.vacancyTitle == _replyVacancy && !r.isReserve;
      return true;
    }).toList();
    final titles = {for (final r in all) if (!r.isReserve && r.vacancyTitle != null) r.vacancyTitle!}.toList()..sort();
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 24),
        children: [
          Row(children: [
            Expanded(child: _statusFilter(_replyStatus, vacancyReplyStatuses, (v) => setState(() => _replyStatus = v))),
            const SizedBox(width: 8),
            Expanded(
              child: DropdownButtonFormField<String?>(
                initialValue: _replyVacancy,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Вакансия', border: OutlineInputBorder(), isDense: true),
                items: [
                  const DropdownMenuItem(value: null, child: Text('Все')),
                  const DropdownMenuItem(value: '', child: Text('Резерв')),
                  for (final t in titles) DropdownMenuItem(value: t, child: Text(t, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() => _replyVacancy = v),
              ),
            ),
          ]),
          const SizedBox(height: 10),
          if (shown.isEmpty)
            _note(all.isEmpty ? 'Откликов пока нет. Они приходят со страницы сайта «Вакансии»' : 'Под фильтр ничего не подходит'),
          for (final r in shown)
            Card(
              margin: const EdgeInsets.only(bottom: 8),
              child: ListTile(
                leading: Icon(r.status == 'NEW' ? Icons.fiber_new_outlined : Icons.person_outline,
                    color: r.status == 'NEW' ? Theme.of(context).colorScheme.primary : context.pal.muted),
                title: Text(r.name ?? 'Без имени', style: const TextStyle(fontWeight: FontWeight.w600)),
                subtitle: Text(
                  '${r.isReserve ? 'Резерв' : r.vacancyTitle ?? '—'} · ${r.phone}\n'
                  '${formatDateTime(r.createdAt)} · ${vacancyReplyStatuses[r.status] ?? r.status}',
                  style: TextStyle(color: context.pal.muted),
                ),
                isThreeLine: true,
                trailing: IconButton(
                  tooltip: 'Позвонить',
                  icon: const Icon(Icons.call_outlined),
                  onPressed: () => _call(r.phone),
                ),
                onTap: () => _openReply(r),
              ),
            ),
        ],
      ),
    );
  }
}

class _CallbackSheet extends StatefulWidget {
  final CallbackRequest request;

  const _CallbackSheet({required this.request});

  @override
  State<_CallbackSheet> createState() => _CallbackSheetState();
}

class _CallbackSheetState extends State<_CallbackSheet> {
  final _api = ApiClient();
  late String _status = widget.request.status;
  late final _note = TextEditingController(text: widget.request.note ?? '');
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await _api.updateCallbackRequest(widget.request.id, status: _status, note: _note.text.trim());
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.request;
    final muted = TextStyle(color: context.pal.muted);
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(r.name ?? 'Без имени', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(['Перезвонить', if (r.business != null) r.business!, formatDateTime(r.createdAt)].join(' · '), style: muted),
            if (r.handledBy != null && r.handledAt != null)
              Text('Статус менял: ${r.handledBy}, ${formatDateTime(r.handledAt!)}', style: muted.copyWith(fontSize: 12)),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () async {
                if (!await _call(r.phone) && mounted) setState(() => _error = 'Не удалось открыть звонок');
              },
              icon: const Icon(Icons.call_outlined),
              label: Text(r.phone),
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: callbackStatuses.containsKey(_status) ? _status : 'NEW',
              decoration: const InputDecoration(labelText: 'Статус', border: OutlineInputBorder()),
              items: [for (final s in callbackStatuses.entries) DropdownMenuItem(value: s.key, child: Text(s.value))],
              onChanged: (v) => setState(() => _status = v ?? _status),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _note,
              minLines: 2,
              maxLines: 5,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(
                  labelText: 'Комментарий', hintText: 'Договорились о…, перезвонить в…', border: OutlineInputBorder()),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 14),
            FilledButton(onPressed: _saving ? null : _save, child: Text(_saving ? 'Сохраняю…' : 'Сохранить')),
          ],
        ),
      ),
    );
  }
}

class _ReplySheet extends StatefulWidget {
  final VacancyReply reply;

  const _ReplySheet({required this.reply});

  @override
  State<_ReplySheet> createState() => _ReplySheetState();
}

class _ReplySheetState extends State<_ReplySheet> {
  final _api = ApiClient();
  late String _status = widget.reply.status;
  late final _note = TextEditingController(text: widget.reply.note ?? '');
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Future<void> _callReply() async {
    if (!await _call(widget.reply.phone) && mounted) setState(() => _error = 'Не удалось открыть звонок');
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await _api.updateVacancyReply(widget.reply.id, status: _status, note: _note.text.trim());
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.reply;
    final muted = TextStyle(color: context.pal.muted);
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(r.name ?? 'Без имени', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Text('${r.isReserve ? 'Кадровый резерв' : 'Вакансия: ${r.vacancyTitle ?? '—'}'} · ${formatDateTime(r.createdAt)}',
                style: muted),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _callReply,
              icon: const Icon(Icons.call_outlined),
              label: Text(r.phone),
            ),
            if (r.about != null) ...[
              const SizedBox(height: 12),
              Text('О себе', style: muted),
              const SizedBox(height: 2),
              Text(r.about!),
            ],
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: vacancyReplyStatuses.containsKey(_status) ? _status : 'NEW',
              decoration: const InputDecoration(labelText: 'Статус', border: OutlineInputBorder()),
              items: [
                for (final s in vacancyReplyStatuses.entries) DropdownMenuItem(value: s.key, child: Text(s.value)),
              ],
              onChanged: (v) => setState(() => _status = v ?? _status),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _note,
              minLines: 2,
              maxLines: 5,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Комментарий', border: OutlineInputBorder()),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 14),
            FilledButton(onPressed: _saving ? null : _save, child: Text(_saving ? 'Сохраняю…' : 'Сохранить')),
            const SizedBox(height: 6),
            Text('Отклики хранятся полгода, потом удаляются сами.', textAlign: TextAlign.center,
                style: muted.copyWith(fontSize: 12)),
          ],
        ),
      ),
    );
  }
}
