"""Страница проверки rabota/proverka.html: по каждому фото из rabota/spisok.tsv — название товара и
картинка «как было / kadr / fon» из rabota/rezultat/_proverka. Запускает scripts/foto/foto.sh."""
import csv
import html
import sys
from pathlib import Path

base = Path(sys.argv[1])
rows = [l.split('\t') for l in (base / 'spisok.tsv').read_text(encoding='utf-8').splitlines() if l.strip()]
status = {}
with open(base / 'rezultat/otchet.csv', encoding='utf-8-sig') as f:
    for r in csv.reader(f, delimiter=';'):
        if r and r[0] != 'file':
            status[r[0].rsplit('.', 1)[0]] = (r[1], r[2] if len(r) > 2 else '')

items, kept = [], []
for pid, photo, _url, name, cat in rows:
    key = f'{pid}-{photo}'
    st, note = status.get(key, ('error', 'нет в отчёте'))
    if st == 'as_is':
        kept.append(pid)
    badge = {'as_is': '<span class="badge warn">программа не тронула</span>',
             'error': '<span class="badge err">ошибка</span>'}.get(st, '')
    note_html = f'<p class="note">{html.escape(note)}</p>' if note else ''
    pic = f'<div class="pic"><img src="rezultat/_proverka/{key}.jpg" alt="{html.escape(name)}: как было, kadr, fon" loading="lazy"></div>' if st != 'error' else ''
    items.append(f'<article class="item"><header><span class="num">№ {pid}</span><h2>{html.escape(name)}</h2>{badge}'
                 f'<span class="cat">{html.escape(cat)}</span></header>{note_html}{pic}</article>')

(base / 'proverka.html').write_text(f'''<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Проверка фото товаров</title>
<style>
:root{{--bg:#f7f4ee;--fg:#2a2420;--muted:#7a6f66;--line:#e2dbd0;--accent:#b5541c;--warn:#fbe3b8;--warnfg:#7a4a00}}
body{{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 "PT Sans","Segoe UI",Arial,sans-serif}}
.wrap{{max-width:1100px;margin:0 auto;padding-inline:16px;padding-block:28px 60px}}
h1{{font:700 32px/1.2 Georgia,serif;margin:0 0 8px}}
.legend{{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:18px 0 26px;position:sticky;top:0;background:var(--bg);padding-block:8px;border-bottom:1px solid var(--line)}}
.legend div{{text-align:center;font-weight:700;font-size:14px;text-transform:uppercase;color:var(--muted)}}
.list{{display:flex;flex-direction:column;gap:34px}}
.item header{{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 12px;margin-bottom:8px}}
.num,.cat{{color:var(--muted);font-size:14px}} .cat{{margin-left:auto}}
.item h2{{font:700 20px Georgia,serif;margin:0}}
.badge{{font-size:13px;font-weight:700;padding:2px 10px;border-radius:999px;background:var(--warn);color:var(--warnfg)}}
.badge.err{{background:#f6c9c9;color:#7a1010}}
.note{{margin:0 0 8px;color:var(--muted);font-size:14px}}
.pic{{overflow-x:auto}} .pic img{{display:block;width:100%;min-width:560px;height:auto;border-radius:4px;background:#222}}
</style>
<div class="wrap">
<h1>Проверка фото товаров</h1>
<p>Фото: {len(rows)}, программа не тронула: {len(kept)}{(" (№ " + ", ".join(kept) + ")") if kept else ""}. На сайте пока ничего не изменено.
Назовите номера товаров, где <b>fon</b> плохой (срезан край упаковки, пропал прозрачный контейнер, ореол) — для них возьмём <b>kadr</b>.</p>
<div class="legend"><div>Как было</div><div>kadr</div><div>fon</div></div>
<div class="list">
{chr(10).join(items)}
</div></div>
''', encoding='utf-8')
print(f'Страница проверки: {base / "proverka.html"} ({len(rows)} фото)')
