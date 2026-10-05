import 'package:flutter/material.dart';

import '../models/user.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';

bool get _isCurrentUserAdmin => ApiClient.currentSession?.role == 'ADMIN';

class UserFormScreen extends StatefulWidget {
  final int? userId;

  const UserFormScreen({super.key, this.userId});

  @override
  State<UserFormScreen> createState() => _UserFormScreenState();
}

class _UserFormScreenState extends State<UserFormScreen> {
  final _api = ApiClient();
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _fullName = TextEditingController();
  String _role = 'SALES';
  bool _enabled = true;
  bool _loading = false;
  bool _initialLoading = false;
  String? _error;

  bool get _isEdit => widget.userId != null;

  @override
  void initState() {
    super.initState();
    if (_isEdit) {
      _initialLoading = true;
      _api.getUser(widget.userId!).then((u) {
        _username.text = u.username;
        _fullName.text = u.fullName;
        _role = u.role;
        _enabled = u.enabled;
        setState(() => _initialLoading = false);
      });
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (_isEdit) {
        await _api.updateUser(widget.userId!, {
          'username': _username.text.trim(),
          'fullName': _fullName.text.trim(),
          'role': _role,
          'enabled': _enabled,
          if (_password.text.isNotEmpty) 'newPassword': _password.text,
        });
      } else {
        await _api.createUser({
          'username': _username.text.trim(),
          'password': _password.text,
          'fullName': _fullName.text.trim(),
          'role': _role,
        });
      }
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? 'Редактировать пользователя' : 'Новый пользователь')),
      body: _initialLoading
          ? const Center(child: CircularProgressIndicator())
          : Form(
              key: _formKey,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: TextStyle(color: context.pal.danger)),
                    ),
                  TextFormField(
                    controller: _username,
                    decoration: const InputDecoration(labelText: 'Логин'),
                    validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите логин' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _fullName,
                    decoration: const InputDecoration(labelText: 'ФИО'),
                    validator: (v) => (v == null || v.trim().isEmpty) ? 'Укажите ФИО' : null,
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _role,
                    decoration: const InputDecoration(labelText: 'Роль'),
                    items: [
                      for (final r in userRoles) DropdownMenuItem(value: r, child: Text(userRoleLabels[r]!)),
                    ],
                    // Роль может менять только ADMIN — при редактировании чужого профиля
                    // ACCOUNTANT видит роль, но изменить не может (см. UserApiController.update).
                    onChanged: (!_isEdit || _isCurrentUserAdmin) ? (v) => setState(() => _role = v!) : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _password,
                    decoration: InputDecoration(
                      labelText: _isEdit ? 'Новый пароль' : 'Пароль',
                      hintText: _isEdit ? 'Оставьте пустым, чтобы не менять' : null,
                    ),
                    validator: (v) {
                      if (_isEdit) {
                        return (v != null && v.isNotEmpty && v.length < 4) ? 'Минимум 4 символа' : null;
                      }
                      return (v == null || v.length < 4) ? 'Минимум 4 символа' : null;
                    },
                  ),
                  if (_isEdit) ...[
                    const SizedBox(height: 8),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Активен'),
                      value: _enabled,
                      onChanged: (v) => setState(() => _enabled = v),
                    ),
                  ],
                  const SizedBox(height: 20),
                  FilledButton(
                    onPressed: _loading ? null : _save,
                    child: _loading
                        ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Сохранить'),
                  ),
                ],
              ),
            ),
    );
  }
}
