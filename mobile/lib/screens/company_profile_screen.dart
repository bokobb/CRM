import 'package:flutter/material.dart';

import '../models/company_profile.dart';
import '../services/api_client.dart';
import '../widgets/app_bottom_nav.dart';
import '../widgets/app_drawer.dart';
import '../theme/app_theme.dart';

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
  final _pickupHours = TextEditingController();
  final _about = TextEditingController();
  final _legalInfo = TextEditingController();
  final _instagram = TextEditingController();
  final _whatsapp = TextEditingController();
  final _tiktok = TextEditingController();
  final _workHours = TextEditingController();
  final _jobsPhone = TextEditingController();
  final _mapPoint = TextEditingController();
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
      _pickupHours.text = p.pickupHours ?? '';
      _about.text = p.about ?? '';
      _legalInfo.text = p.legalInfo ?? '';
      _instagram.text = p.instagramUrl ?? '';
      _whatsapp.text = p.whatsappPhone ?? '';
      _tiktok.text = p.tiktokUrl ?? '';
      _workHours.text = p.workHours ?? '';
      _jobsPhone.text = p.jobsPhone ?? '';
      _mapPoint.text = p.mapPoint ?? '';
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
        pickupHours: _pickupHours.text.trim(),
        about: _about.text.trim(),
        legalInfo: _legalInfo.text.trim(),
        instagramUrl: _instagram.text.trim(),
        whatsappPhone: _whatsapp.text.trim(),
        tiktokUrl: _tiktok.text.trim(),
        workHours: _workHours.text.trim(),
        jobsPhone: _jobsPhone.text.trim(),
        mapPoint: _mapPoint.text.trim(),
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
      bottomNavigationBar: const AppBottomNav(currentRoute: '/settings'),
      body: _initialLoading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(_error!, style: TextStyle(color: context.pal.danger)),
                  ),
                TextField(controller: _name, decoration: const InputDecoration(labelText: 'Название компании')),
                const SizedBox(height: 12),
                TextField(controller: _address, decoration: const InputDecoration(labelText: 'Адрес')),
                const SizedBox(height: 12),
                TextField(controller: _phone, decoration: const InputDecoration(labelText: 'Телефон')),
                const SizedBox(height: 12),
                TextField(controller: _email, decoration: const InputDecoration(labelText: 'Email')),
                const SizedBox(height: 24),
                Text('Сайт для клиентов (главная страница)', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 4),
                Text('Название, адрес, телефон и email выше тоже показываются на сайте. Пустое поле на сайте не выводится.',
                    style: TextStyle(color: context.pal.muted, fontSize: 13)),
                const SizedBox(height: 12),
                TextField(
                    controller: _pickupHours,
                    decoration: const InputDecoration(labelText: 'Выдача заказов', hintText: 'Пн–Сб, 7:00–16:00')),
                const SizedBox(height: 12),
                TextField(
                    controller: _about,
                    minLines: 2,
                    maxLines: 6,
                    decoration: const InputDecoration(
                        labelText: 'О компании', hintText: 'С какого года работаете, город, команда')),
                const SizedBox(height: 12),
                TextField(
                    controller: _legalInfo,
                    decoration: const InputDecoration(labelText: 'Юр. лицо и БИН', hintText: 'ТОО «…» · БИН …')),
                const SizedBox(height: 12),
                TextField(
                    controller: _instagram,
                    keyboardType: TextInputType.url,
                    decoration: const InputDecoration(
                        labelText: 'Instagram', hintText: 'https://www.instagram.com/yug_sladosti')),
                const SizedBox(height: 12),
                TextField(
                    controller: _tiktok,
                    keyboardType: TextInputType.url,
                    decoration: const InputDecoration(
                        labelText: 'TikTok', hintText: 'https://www.tiktok.com/@yug_sladosti')),
                const SizedBox(height: 12),
                TextField(
                    controller: _whatsapp,
                    keyboardType: TextInputType.phone,
                    decoration: const InputDecoration(labelText: 'WhatsApp', hintText: '+7 775 005 02 30')),
                const SizedBox(height: 12),
                TextField(
                    controller: _workHours,
                    decoration: const InputDecoration(
                        labelText: 'Время работы (страница «Контакты»)',
                        hintText: 'Пн–Сб с 07:00 до 18:00, обед с 12:00 до 13:00')),
                const SizedBox(height: 12),
                TextField(
                    controller: _jobsPhone,
                    keyboardType: TextInputType.phone,
                    decoration: const InputDecoration(
                        labelText: 'Телефон по вакансиям', hintText: '+7 778 700 39 02')),
                const SizedBox(height: 12),
                TextField(
                    controller: _mapPoint,
                    decoration: const InputDecoration(
                        labelText: 'Ворота на карте: широта, долгота',
                        hintText: '42.300000, 69.600000',
                        helperText: 'Пусто — на «Контактах» без карты, кнопки маршрута ищут адрес')),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _loading ? null : _save,
                  child: _loading
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Сохранить'),
                ),
                if (_saved) ...[
                  const SizedBox(height: 8),
                  Text('Сохранено', style: TextStyle(color: context.pal.success)),
                ],
              ],
            ),
    );
  }
}
