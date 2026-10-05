import 'package:flutter/material.dart';

import '../models/tech_card.dart';
import '../services/api_client.dart';
import '../widgets/tech_card_style.dart';
import '../theme/app_theme.dart';

class _StageForm {
  final TextEditingController title;
  final TextEditingController description;
  final TextEditingController temperature;
  final TextEditingController duration;
  bool ccp;

  _StageForm([TechCardStage? s])
    : title = TextEditingController(text: s?.title ?? ''),
      description = TextEditingController(text: s?.description ?? ''),
      temperature = TextEditingController(text: s?.temperature ?? ''),
      duration = TextEditingController(text: s?.duration ?? ''),
      ccp = s?.ccp ?? false;

  void dispose() {
    title.dispose();
    description.dispose();
    temperature.dispose();
    duration.dispose();
  }

  Map<String, dynamic> toJson() => {
    'title': title.text,
    'description': description.text,
    'temperature': temperature.text,
    'duration': duration.text,
    'ccp': ccp,
  };
}

/// Правка техкарты (ADMIN): паспорт, этапы техпроцесса с ККТ, органолептика. Рецептура здесь не
/// правится — она общая с заявками на сырьё и 1С, для неё есть экран «Рецептура».
class TechCardEditScreen extends StatefulWidget {
  final TechCard card;

  const TechCardEditScreen({super.key, required this.card});

  @override
  State<TechCardEditScreen> createState() => _TechCardEditScreenState();
}

class _TechCardEditScreenState extends State<TechCardEditScreen> {
  final _api = ApiClient();
  late final _shelfLife = TextEditingController(text: widget.card.shelfLife ?? '');
  late final _storage = TextEditingController(text: widget.card.storageConditions ?? '');
  late final _packaging = TextEditingController(text: widget.card.packaging ?? '');
  late final _look = TextEditingController(text: widget.card.qualityLook ?? '');
  late final _color = TextEditingController(text: widget.card.qualityColor ?? '');
  late final _crumb = TextEditingController(text: widget.card.qualityCrumb ?? '');
  late final _taste = TextEditingController(text: widget.card.qualityTaste ?? '');
  late final List<_StageForm> _stages = [for (final s in widget.card.stages) _StageForm(s)];
  bool _saving = false;

  @override
  void dispose() {
    for (final c in [_shelfLife, _storage, _packaging, _look, _color, _crumb, _taste]) {
      c.dispose();
    }
    for (final s in _stages) {
      s.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await _api.saveTechCard(widget.card.productId, {
        'shelfLife': _shelfLife.text,
        'storageConditions': _storage.text,
        'packaging': _packaging.text,
        'qualityLook': _look.text,
        'qualityColor': _color.text,
        'qualityCrumb': _crumb.text,
        'qualityTaste': _taste.text,
        'stages': [for (final s in _stages) s.toJson()],
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() => _saving = false);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось сохранить: $e')));
      }
    }
  }

  void _move(int i, int delta) {
    setState(() {
      final s = _stages.removeAt(i);
      _stages.insert(i + delta, s);
    });
  }

  Widget _field(TextEditingController c, String label, {int maxLines = 1, String? hint}) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TKLabel(label),
        const SizedBox(height: 4),
        TextField(
          controller: c,
          minLines: 1,
          maxLines: maxLines,
          style: TK.text,
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(fontFamily: TK.body, color: Color(0xFFA7AEA8)),
          ),
        ),
      ],
    ),
  );

  Widget _heading(String text) => Padding(
    padding: const EdgeInsets.fromLTRB(0, 16, 0, 8),
    child: Text(text, style: TK.h2),
  );

  @override
  Widget build(BuildContext context) {
    return Theme(
      data: TK.theme(Theme.of(context)),
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.card.name),
          actions: [
            _saving
                ? const Padding(
                    padding: EdgeInsets.all(16),
                    child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
                  )
                : IconButton(icon: const Icon(Icons.check), tooltip: 'Сохранить', onPressed: _save),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
          children: [
            _heading('Паспорт изделия'),
            _field(_shelfLife, 'Срок годности', hint: 'напр. 30 суток'),
            _field(_storage, 'Условия хранения', maxLines: 3, hint: 'напр. t 18±5 °C, влажность не более 75 %'),
            _field(_packaging, 'Упаковка', maxLines: 3),
            _heading('Технологический процесс'),
            for (var i = 0; i < _stages.length; i++) _stageCard(i),
            OutlinedButton.icon(
              onPressed: () => setState(() => _stages.add(_StageForm())),
              icon: const Icon(Icons.add),
              style: OutlinedButton.styleFrom(
                foregroundColor: context.pal.ink,
                backgroundColor: context.pal.card,
                side: BorderSide(color: context.pal.line),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
              label: const Text('Добавить этап'),
            ),
            _heading('Органолептические показатели'),
            _field(_look, 'Внешний вид', maxLines: 3),
            _field(_color, 'Цвет', maxLines: 2),
            _field(_crumb, 'Мякиш / излом', maxLines: 3),
            _field(_taste, 'Вкус и запах', maxLines: 2),
          ],
        ),
      ),
    );
  }

  Widget _stageCard(int i) {
    final s = _stages[i];
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: context.pal.card,
        border: Border.all(color: s.ccp ? context.pal.warn : context.pal.line),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 4, 12),
        child: Column(
          children: [
            Row(
              children: [
                Container(
                  width: 26,
                  height: 26,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: s.ccp ? context.pal.warn : context.pal.seg, shape: BoxShape.circle),
                  child: Text('${i + 1}', style: TK.num.copyWith(fontSize: 12.5, fontWeight: FontWeight.w600)),
                ),
                const SizedBox(width: 8),
                const TKLabel('Этап'),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.arrow_upward, size: 20),
                  tooltip: 'Выше',
                  onPressed: i == 0 ? null : () => _move(i, -1),
                ),
                IconButton(
                  icon: const Icon(Icons.arrow_downward, size: 20),
                  tooltip: 'Ниже',
                  onPressed: i == _stages.length - 1 ? null : () => _move(i, 1),
                ),
                IconButton(
                  icon: Icon(Icons.delete_outline, size: 20, color: context.pal.danger),
                  tooltip: 'Удалить этап',
                  onPressed: () => setState(() => _stages.removeAt(i).dispose()),
                ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Column(
                children: [
                  _field(s.title, 'Название'),
                  _field(s.description, 'Описание операции', maxLines: 4),
                  Row(
                    children: [
                      Expanded(child: _field(s.temperature, 't°', hint: '180–190 °C')),
                      const SizedBox(width: 10),
                      Expanded(child: _field(s.duration, 'Время', hint: '35–40 мин')),
                    ],
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    title: const Text('Критическая контрольная точка (ККТ)'),
                    value: s.ccp,
                    activeThumbColor: context.pal.warn,
                    onChanged: (v) => setState(() => s.ccp = v),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
