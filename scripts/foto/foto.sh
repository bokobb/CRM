#!/usr/bin/env bash
# Выравнивание фото товаров (товар по центру, единый фон) — ZADANIE-foto.md, программа foto_tovara.py.
#
#   scripts/foto/foto.sh podgotovit
#       берёт с сервера обложки, которые ещё не обрабатывались (product_photos.retouched_at пусто),
#       прогоняет их через foto_tovara.py и собирает rabota/proverka.html. На сайте ничего не меняет.
#   scripts/foto/foto.sh zamenit [--tolko 24,31] [--kadr 24] [--ostavit 21]
#       после того как владелец посмотрел proverka.html: резервная копия фото и таблицы фото, затем
#       замена на сервере (вариант fon; --kadr — вариант kadr; --ostavit — оставить как было).
#       --tolko — только эти товары (для пробы на двух-трёх).
#
# Замену делает само приложение (ProductPhotoInbox → ProductPhotoService.replace): фото остаётся на
# своём месте, обработанное получает отметку retouched_at и второй раз не берётся.
# Нужно: ssh-ключ сервера, Python 3.11+; окружение .venv-foto создаётся при первом запуске (~2 ГБ памяти).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$ROOT/scripts/foto"
WORK="$ROOT/rabota"
VENV="$ROOT/.venv-foto"
KEY=${FOTO_SSH_KEY:-$HOME/.ssh/crm_contabo}
HOST=${FOTO_HOST:-bakhodir@169.58.214.142}
APP=${FOTO_APP_DIR:-CRM}                       # папка приложения на сервере, от домашней
SSH=(ssh -i "$KEY" "$HOST")

sql() { printf '%s' "$1" | "${SSH[@]}" "docker exec -i crm-postgres psql -U crm -d crm -At -F \$'\\t'"; }

podgotovit() {
  mkdir -p "$WORK/ishodniki" "$WORK/orig"
  # обложки (position 0) без отметки, только загруженные в CRM (не внешние ссылки)
  sql "select f.product_id, f.id, regexp_replace(f.url,'^.*/product-images/',''), replace(p.name,E'\\t',' '), coalesce(p.category,'')
       from product_photos f join products p on p.id=f.product_id
       where f.position=0 and f.retouched_at is null and f.url like '%/product-images/%' order by f.product_id" > "$WORK/spisok.tsv"
  local n; n=$(grep -c . "$WORK/spisok.tsv" || true)
  if [ "$n" = 0 ]; then echo "Новых обложек нет — всё уже обработано."; exit 0; fi
  echo "Новых обложек: $n"
  cut -f3 "$WORK/spisok.tsv" > "$WORK/files.txt"
  rsync -a -e "ssh -i $KEY" --files-from="$WORK/files.txt" "$HOST:$APP/data/product-images/" "$WORK/orig/"
  rm -rf "$WORK/partiya"; mkdir -p "$WORK/partiya"
  while IFS=$'\t' read -r pid photo file _; do
    cp "$WORK/orig/$file" "$WORK/partiya/$pid-$photo.${file##*.}"
    cp -n "$WORK/orig/$file" "$WORK/ishodniki/$pid-$photo.${file##*.}"
  done < "$WORK/spisok.tsv"
  if [ ! -x "$VENV/bin/python" ]; then
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install -q "rembg[cpu]" opencv-python-headless pillow numpy
  fi
  # результат копится в rezultat/; --skip-existing не обрабатывает уже готовое
  "$VENV/bin/python" -I "$HERE/foto_tovara.py" "$WORK/partiya" "$WORK/rezultat" --format png --skip-existing
  python3 -I "$HERE/proverka.py" "$WORK"
}

zamenit() {
  local tolko="" kadr="" ostavit=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --tolko) tolko=",$2,"; shift 2 ;;
      --kadr) kadr=",$2,"; shift 2 ;;
      --ostavit) ostavit=",$2,"; shift 2 ;;
      *) echo "Неизвестный ключ $1"; exit 1 ;;
    esac
  done
  [ -s "$WORK/spisok.tsv" ] || { echo "Сначала: scripts/foto/foto.sh podgotovit"; exit 1; }
  # уже заменённые (например, пробные через --tolko) второй раз не отправляем
  local done_ids; done_ids=",$(sql "select string_agg(id::text, ',') from product_photos where retouched_at is not null"),"
  local out="$WORK/inbox"; rm -rf "$out"; mkdir -p "$out"
  local sent=0
  while IFS=$'\t' read -r pid photo _ name _; do
    [ -n "$tolko" ] && [[ "$tolko" != *",$pid,"* ]] && continue
    [[ "$done_ids" == *",$photo,"* ]] && continue
    local key="$pid-$photo"
    local st; st=$(grep -m1 "^$key\." "$WORK/rezultat/otchet.csv" | cut -d';' -f2 || true)
    if [ "$st" = "as_is" ] || [[ "$ostavit" == *",$pid,"* ]]; then
      : > "$out/$key.skip"; echo "  как было  №$pid $name"
    elif [ "$st" = "ok" ]; then
      local v=fon; [[ "$kadr" == *",$pid,"* ]] && v=kadr
      cp "$WORK/rezultat/$v/$key.png" "$out/$key.png"; echo "  $v       №$pid $name"
    else
      echo "  пропуск   №$pid $name (статус: ${st:-нет результата})"; continue
    fi
    sent=$((sent + 1))
  done < "$WORK/spisok.tsv"
  [ "$sent" -gt 0 ] || { echo "Нечего отправлять"; exit 0; }
  local stamp; stamp=$(date +%Y%m%d_%H%M%S)
  "${SSH[@]}" "mkdir -p ~/backups/foto && cd ~/$APP && tar czf ~/backups/foto/product-images_$stamp.tar.gz data/product-images \
    && docker exec crm-postgres pg_dump -U crm -d crm -Fc -t product_photos -t products > ~/backups/foto/photos_$stamp.dump \
    && mkdir -p data/photo-inbox"
  echo "Резервная копия на сервере: ~/backups/foto/product-images_$stamp.tar.gz и photos_$stamp.dump"
  # .part → переименование на месте: приложение не возьмёт недокачанный файл
  for f in "$out"/*; do
    scp -q -i "$KEY" "$f" "$HOST:$APP/data/photo-inbox/$(basename "$f").part"
    "${SSH[@]}" "mv ~/$APP/data/photo-inbox/$(basename "$f").part ~/$APP/data/photo-inbox/$(basename "$f")"
  done
  echo "Отправлено: $sent. Жду, пока приложение заменит фото…"
  for _ in $(seq 1 60); do
    left=$("${SSH[@]}" "find ~/$APP/data/photo-inbox -maxdepth 1 -type f ! -name '*.part' | wc -l")
    [ "$left" = 0 ] && break
    sleep 5
  done
  "${SSH[@]}" "ls ~/$APP/data/photo-inbox/failed/*.txt 2>/dev/null | while read t; do echo \"  ОШИБКА \$(basename \$t .txt): \$(cat \$t)\"; done" || true
  echo "Готово."
}

case "${1:-}" in
  podgotovit) shift; podgotovit "$@" ;;
  zamenit) shift; zamenit "$@" ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
