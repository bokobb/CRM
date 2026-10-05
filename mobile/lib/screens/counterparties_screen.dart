import 'package:flutter/material.dart';

import '../models/counterparty.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/error_view.dart';
import '../theme/app_theme.dart';

/// "Настройки → Контрагенты" — поставщики сырья для закупок (см. CounterpartyService на
/// бэкенде). Создание здесь сразу выгружает контрагента и в 1С (best-effort — см. oneCLinked/
/// oneCSyncError у Counterparty); удаление и разовая загрузка ВСЕХ контрагентов из 1С пока
/// только на вебе (/counterparties), сюда не перенесены — не запрошено.
class CounterpartiesScreen extends StatefulWidget {
  const CounterpartiesScreen({super.key});

  @override
  State<CounterpartiesScreen> createState() => _CounterpartiesScreenState();
}

class _CounterpartiesScreenState extends State<CounterpartiesScreen> {
  final _api = ApiClient();
  late Future<List<Counterparty>> _future;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _future = _api.getCounterparties();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getCounterparties());
    await _future;
  }

  Future<void> _addCounterparty() async {
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Новый контрагент'),
        content: TextField(
          controller: controller,
          autofocus: true,
          textCapitalization: TextCapitalization.sentences,
          decoration: const InputDecoration(labelText: 'Название'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Создать')),
        ],
      ),
    );
    if (name == null || name.trim().isEmpty) return;
    setState(() => _saving = true);
    try {
      final created = await _api.createCounterparty(name.trim());
      await _refresh();
      if (!mounted) return;
      if (created.oneCSyncError != null) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Контрагент создан, но не выгружен в 1С: ${created.oneCSyncError}'),
          backgroundColor: context.pal.danger,
        ));
      } else {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Контрагент создан')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Контрагенты')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      floatingActionButton: FloatingActionButton(
        onPressed: _saving ? null : _addCounterparty,
        child: _saving
            ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : const Icon(Icons.add),
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Counterparty>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final counterparties = snapshot.data!;
            if (counterparties.isEmpty) {
              return ListView(children: [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Контрагентов пока нет', style: TextStyle(color: context.pal.muted))),
                ),
              ]);
            }
            return ListView.separated(
              itemCount: counterparties.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final c = counterparties[i];
                return ListTile(
                  title: Text(c.name),
                  subtitle: c.oneCSyncError != null
                      ? Text('Не выгружен в 1С: ${c.oneCSyncError}', style: TextStyle(color: context.pal.danger))
                      : Text(c.oneCLinked ? 'Привязан к 1С' : '—', style: TextStyle(color: context.pal.muted)),
                  trailing: c.oneCLinked ? Icon(Icons.check_circle, color: context.pal.success) : null,
                );
              },
            );
          },
        ),
      ),
    );
  }
}
