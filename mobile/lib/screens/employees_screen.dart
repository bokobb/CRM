import 'package:flutter/material.dart';

import '../models/employee.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';
import '../widgets/app_list_tile.dart';
import '../widgets/error_view.dart';
import '../widgets/format.dart';

/// «Сотрудники» — работники цеха (без входа в приложение). Технолог распределяет их по бригадам
/// на экране «Производство». Ставку за смену видят и задают только админ и бухгалтер.
/// Не удаляются — «Уволен» снимает с распределения, история бригад остаётся.
class EmployeesScreen extends StatefulWidget {
  const EmployeesScreen({super.key});

  @override
  State<EmployeesScreen> createState() => _EmployeesScreenState();
}

bool get _canSeeRates => const {'ADMIN', 'ACCOUNTANT'}.contains(ApiClient.currentSession?.role);

class _EmployeesScreenState extends State<EmployeesScreen> {
  final _api = ApiClient();
  late Future<List<Employee>> _future;

  @override
  void initState() {
    super.initState();
    _future = _api.getEmployees();
  }

  Future<void> _refresh() async {
    setState(() => _future = _api.getEmployees());
    await _future;
  }

  Future<void> _edit(Employee? e) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _EmployeeForm(employee: e),
    );
    if (saved == true) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Сотрудники')),
      drawer: const AppDrawer(currentRoute: '/employees'),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _edit(null),
        tooltip: 'Добавить сотрудника',
        child: const Icon(Icons.person_add_alt_1),
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: FutureBuilder<List<Employee>>(
          future: _future,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return ErrorView(error: snapshot.error.toString(), onRetry: _refresh);
            }
            final list = snapshot.data!;
            if (list.isEmpty) {
              return ListView(children: const [
                Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(
                    child: Text('Сотрудников пока нет — добавьте кнопкой «+»', style: TextStyle(color: Colors.grey)),
                  ),
                ),
              ]);
            }
            return ListView.builder(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 88),
              itemCount: list.length,
              itemBuilder: (context, i) {
                final e = list[i];
                final parts = [
                  if (e.position != null) e.position!,
                  if (_canSeeRates) e.dailyRate == null ? 'ставка не задана' : '${formatMoney(e.dailyRate!)} за смену',
                  if (!e.active) 'уволен',
                ];
                return AppListTile(
                  leadingIcon: e.active ? Icons.person_outline : Icons.person_off_outlined,
                  leadingColor: e.active ? null : Colors.grey,
                  title: e.fullName,
                  subtitle: parts.isEmpty ? null : parts.join(' · '),
                  trailing: const Icon(Icons.edit_outlined, color: Colors.grey),
                  onTap: () => _edit(e),
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _EmployeeForm extends StatefulWidget {
  final Employee? employee;

  const _EmployeeForm({this.employee});

  @override
  State<_EmployeeForm> createState() => _EmployeeFormState();
}

class _EmployeeFormState extends State<_EmployeeForm> {
  final _api = ApiClient();
  late final _name = TextEditingController(text: widget.employee?.fullName ?? '');
  late final _position = TextEditingController(text: widget.employee?.position ?? '');
  late final _phone = TextEditingController(text: widget.employee?.phone ?? '');
  late final _rate = TextEditingController(
      text: widget.employee?.dailyRate == null ? '' : formatQty(widget.employee!.dailyRate!).replaceAll(' ', ''));
  late bool _active = widget.employee?.active ?? true;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _position.dispose();
    _phone.dispose();
    _rate.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty) {
      setState(() => _error = 'Укажите ФИО');
      return;
    }
    final rateText = _rate.text.replaceAll(' ', '').replaceAll(',', '.');
    final rate = rateText.isEmpty ? null : double.tryParse(rateText);
    if (rateText.isNotEmpty && rate == null) {
      setState(() => _error = 'Ставка — число, например 8000');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await _api.saveEmployee(widget.employee?.id, {
        'fullName': _name.text.trim(),
        'position': _position.text.trim(),
        'phone': _phone.text.trim(),
        'dailyRate': rate,
        'active': _active,
      });
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
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, 16 + MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.employee == null ? 'Новый сотрудник' : 'Сотрудник', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 14),
            TextField(
              controller: _name,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(labelText: 'ФИО', border: OutlineInputBorder()),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _position,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Должность (необязательно)', border: OutlineInputBorder()),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _phone,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(labelText: 'Телефон (необязательно)', border: OutlineInputBorder()),
            ),
            if (_canSeeRates) ...[
              const SizedBox(height: 10),
              TextField(
                controller: _rate,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(
                  labelText: 'Ставка за смену',
                  suffixText: '₸',
                  helperText: 'Идёт в себестоимость заявки, где сотрудник был в бригаде',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
            if (widget.employee != null)
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Работает'),
                subtitle: const Text('Выключите, если уволен — исчезнет из выбора бригады'),
                value: _active,
                onChanged: (v) => setState(() => _active = v),
              ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
            const SizedBox(height: 14),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: Text(_saving ? 'Сохраняю…' : 'Сохранить'),
            ),
          ],
        ),
      ),
    );
  }
}
