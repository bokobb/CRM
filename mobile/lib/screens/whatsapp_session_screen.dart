import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../models/whatsapp_session.dart';
import '../services/api_client.dart';
import '../theme/app_theme.dart';

/// Настройки -> WhatsApp-сессия: статус подключения шлюза, QR для нового подключения, разрыв
/// текущей сессии. Опрашивает статус каждые несколько секунд — соединение и QR меняются
/// асинхронно на стороне Node-шлюза (Baileys), см. whatsapp-gateway/index.js.
class WhatsAppSessionScreen extends StatefulWidget {
  const WhatsAppSessionScreen({super.key});

  @override
  State<WhatsAppSessionScreen> createState() => _WhatsAppSessionScreenState();
}

class _WhatsAppSessionScreenState extends State<WhatsAppSessionScreen> {
  final _api = ApiClient();
  Timer? _timer;
  WhatsAppSession? _session;
  Uint8List? _qrBytes;
  bool _loggingOut = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _poll();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) => _poll());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _poll() async {
    try {
      final session = await _api.getWhatsAppSessionStatus();
      Uint8List? qr = _qrBytes;
      if (session.status == 'qr') {
        try {
          qr = await _api.getWhatsAppQrCode();
        } catch (_) {
          qr = null;
        }
      } else {
        qr = null;
      }
      if (mounted) {
        setState(() {
          _session = session;
          _qrBytes = qr;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
  }

  Future<void> _logout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Разорвать соединение?'),
        content: const Text(
          'Приём заказов из WhatsApp остановится до тех пор, пока кто-нибудь не отсканирует '
          'новый QR-код. Действие нельзя отменить.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: context.pal.danger),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Разорвать'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _loggingOut = true);
    try {
      await _api.logoutWhatsAppSession();
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loggingOut = false);
    }
    await _poll();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('WhatsApp-сессия')),
      body: RefreshIndicator(
        onRefresh: _poll,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: Text(_error!, style: TextStyle(color: context.pal.danger)),
              ),
            _buildBody(),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    final session = _session;
    if (session == null) {
      return const Center(child: Padding(padding: EdgeInsets.all(40), child: CircularProgressIndicator()));
    }
    switch (session.status) {
      case 'open':
        return _buildOpen(session);
      case 'qr':
        return _buildQr();
      case 'unavailable':
        return _buildUnavailable();
      default:
        return _buildConnecting();
    }
  }

  Widget _buildOpen(WhatsAppSession session) {
    return Column(
      children: [
        const SizedBox(height: 24),
        Icon(Icons.check_circle, color: context.pal.success, size: 64),
        const SizedBox(height: 16),
        const Text('Подключено', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        if (session.phone != null) ...[
          const SizedBox(height: 6),
          Text('+${session.phone}', style: TextStyle(color: context.pal.muted, fontSize: 15)),
        ],
        const SizedBox(height: 8),
        Text(
          'Заказы из WhatsApp обрабатываются автоматически.',
          textAlign: TextAlign.center,
          style: TextStyle(color: context.pal.muted),
        ),
        const SizedBox(height: 28),
        OutlinedButton.icon(
          onPressed: _loggingOut ? null : _logout,
          style: OutlinedButton.styleFrom(foregroundColor: context.pal.danger),
          icon: _loggingOut
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.link_off),
          label: const Text('Отключить'),
        ),
      ],
    );
  }

  Widget _buildQr() {
    return Column(
      children: [
        const SizedBox(height: 12),
        const Text('Ожидает подключения', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
        const SizedBox(height: 16),
        if (_qrBytes != null)
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(border: Border.all(color: context.pal.line), borderRadius: BorderRadius.circular(12)),
            child: Image.memory(_qrBytes!, width: 260, height: 260),
          )
        else
          const SizedBox(height: 260, child: Center(child: CircularProgressIndicator())),
        const SizedBox(height: 20),
        Text(
          'На телефоне, который будет принимать заказы, откройте WhatsApp → Настройки → '
          'Связанные устройства → Привязка устройства и отсканируйте этот QR-код.',
          textAlign: TextAlign.center,
          style: TextStyle(color: context.pal.muted),
        ),
      ],
    );
  }

  Widget _buildConnecting() {
    return Column(
      children: [
        SizedBox(height: 40),
        CircularProgressIndicator(),
        SizedBox(height: 16),
        Text('Подключение к WhatsApp...', style: TextStyle(color: context.pal.muted)),
      ],
    );
  }

  Widget _buildUnavailable() {
    return Column(
      children: [
        const SizedBox(height: 24),
        Icon(Icons.error_outline, color: context.pal.danger, size: 56),
        const SizedBox(height: 16),
        const Text('WhatsApp-шлюз недоступен', style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        Text(
          'Не удалось связаться со шлюзом на сервере — проверьте, что он запущен.',
          textAlign: TextAlign.center,
          style: TextStyle(color: context.pal.muted),
        ),
        const SizedBox(height: 16),
        OutlinedButton(onPressed: _poll, child: const Text('Повторить')),
      ],
    );
  }
}
