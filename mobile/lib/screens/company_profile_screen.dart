import 'package:flutter/material.dart';

import '../models/company_profile.dart';
import '../services/api_client.dart';
import '../widgets/app_drawer.dart';

class CompanyProfileScreen extends StatefulWidget {
  const CompanyProfileScreen({super.key});

  @override
  State<CompanyProfileScreen> createState() => _CompanyProfileScreenState();
}

class _CompanyProfileScreenState extends State<CompanyProfileScreen> {
  final _api = ApiClient();
  final _name = TextEditingController();
  final _address = TextEditingController();
  final _phone = TextEditingController();
  final _email = TextEditingController();
  bool _initialLoading = true;
  bool _loading = false;
  bool _saved = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _api.getCompanyProfile().then((p) {
      _name.text = p.name ?? '';
      _address.text = p.address ?? '';
      _phone.text = p.phone ?? '';
      _email.text = p.email ?? '';
      setState(() => _initialLoading = false);
    });
  }

  Future<void> _save() async {
    setState(() {
      _loading = true;
      _error = null;
      _saved = false;
    });
    try {
      await _api.updateCompanyProfile(CompanyProfile(
        name: _name.text.trim(),
        address: _address.text.trim(),
        phone: _phone.text.trim(),
        email: _email.text.trim(),
      ));
      setState(() {
        _loading = false;
        _saved = true;
      });
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
      appBar: AppBar(title: const Text('Профиль компании')),
      drawer: const AppDrawer(currentRoute: '/settings'),
      body: _initialLoading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: const TextStyle(color: Colors.red)),
                  ),
                TextField(controller: _name, decoration: const InputDecoration(labelText: 'Название компании')),
                const SizedBox(height: 12),
                TextField(controller: _address, decoration: const InputDecoration(labelText: 'Адрес')),
                const SizedBox(height: 12),
                TextField(controller: _phone, decoration: const InputDecoration(labelText: 'Телефон')),
                const SizedBox(height: 12),
                TextField(controller: _email, decoration: const InputDecoration(labelText: 'Email')),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _loading ? null : _save,
                  child: _loading
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Сохранить'),
                ),
                if (_saved) ...[
                  const SizedBox(height: 8),
                  const Text('Сохранено', style: TextStyle(color: Color(0xFF4C8A5E))),
                ],
              ],
            ),
    );
  }
}
