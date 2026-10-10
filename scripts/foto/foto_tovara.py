#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Фото товара -> один вид для карточек сайта «Юг Сладости».

Из каждого снимка программа делает два варианта, оба в кадре 3:4, с товаром по центру и одного размера:

  fon/   единый фон. Товар остаётся вашей фотографией, а всё вокруг него — складки ткани, край стола,
         тёмные полосы, чужие упаковки — плавно уходит в один и тот же ровный светлый фон.
  kadr/  только кадрирование. Снимок не меняется, из него вырезан кадр вокруг товара.
         Запасной вариант для тех фото, где единый фон получился плохо.

В обоих вариантах убран синеватый или желтоватый оттенок съёмки (по цвету стены за товаром).
Сам товар программа не перерисовывает: это те же пиксели вашей фотографии.

Установка (один раз; нужен Python 3.11 или новее и около 2 ГБ свободной памяти):
    pip install "rembg[cpu]" opencv-python-headless pillow numpy
Запуск:
    python foto_tovara.py папка_с_фото папка_для_результата
Настройки:
    --size auto        размер результата. auto (по умолчанию) — как у исходника, но не больше 1200x1600
                       и не меньше 600x800, всегда 3:4. Можно задать явно, например --size 900x1200
    --fill 0.80        какую долю высоты кадра занимает товар
    --format webp      webp (по умолчанию), jpg или png. png — без потерь: берите его, если результат
                       потом загружается на сайт и сайт сам сжимает фото
    --skip-existing    не обрабатывать снимки, для которых результат в папке уже есть (для повторных запусков)
При первом запуске скачивается модель (около 180 МБ), дальше программа работает без интернета.

Как пользоваться результатом. В подпапке _proverka на каждое фото лежит картинка из трёх частей:
слева — как было, в середине — kadr, справа — fon. Просмотрите их. Обычно годится fon; если у него
срезан край упаковки или пропал прозрачный контейнер — берите для этого товара файл из kadr.

Снимок, на котором товар найден ненадёжно (белое на белом, товар во весь кадр), программа
не трогает — только подгоняет под размер кадра — и называет его в конце работы.
Итог по каждому файлу записывается в otchet.csv в папке результата (ok / as_is / error / skipped).

Лучший исходник — оригинал с камеры. Годятся и фото, уже загруженные на сайт: результат получится
того же размера, что исходник. Товар, снятый мелко, программа увеличивает до общего размера,
поэтому на таком фото он будет менее резким, чем на остальных.
"""
import argparse
import csv
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps

# Модель, которая находит товар на снимке: IS-Net из проекта DIS (код под Apache-2.0, эту модель авторы выложили
# «for general use»). Не меняйте её на bria-rmbg, которую библиотека rembg берёт по умолчанию: та модель
# бесплатна только для некоммерческого использования.
MODEL = 'isnet-general-use'
WORK = 1600                            # товар ищем на уменьшенной копии снимка (длинная сторона, px): быстро и одинаково для любого исходника
BACKDROP_TOP = (233, 230, 224)         # единый фон: ровный светло-серый, чуть тёплый; вверху немного темнее,
BACKDROP_BOTTOM = (244, 242, 237)      # внизу («стол») светлее
SHADOW = (72, 68, 64)                  # цвет тени под товаром
EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tif', '.tiff', '.heic', '.heif'}

_session = None


def session():
    global _session
    if _session is None:
        from rembg import new_session
        _session = new_session(MODEL)
    return _session


def to_rgb(img):
    """Снимок как обычная цветная картинка: с учётом поворота камеры, прозрачность — на белом."""
    img = ImageOps.exif_transpose(img)
    if img.mode in ('RGBA', 'LA') or (img.mode == 'P' and 'transparency' in img.info):
        img = img.convert('RGBA')
        img = Image.alpha_composite(Image.new('RGBA', img.size, (255, 255, 255, 255)), img)
    return img.convert('RGB')


# ---------------------------------------------------------------- поиск товара на снимке (на уменьшенной копии)

def model_mask(rgb):
    """Что считает товаром нейросеть: маска 0..1 того же размера, что и снимок."""
    from rembg import remove
    return np.asarray(remove(Image.fromarray(rgb), session=session(), only_mask=True)).astype(np.float32) / 255


def colour_mask(rgb, net):
    """Товар по цвету: на светлом однотонном фоне выпечка и этикетки заметно ярче по цвету, чем фон.
    Возвращает None, если фон не студийный (цветной или тёмный) — тогда верим только нейросети."""
    h, w = rgb.shape[:2]
    c = (rgb.max(-1).astype(np.float32) - rgb.min(-1)) / 255          # насыщенность цвета, не зависящая от яркости
    v = rgb.max(-1).astype(np.float32) / 255
    band = max(4, round(0.04 * min(h, w)))
    edge = np.zeros((h, w), bool)
    edge[:band] = edge[-band:] = True
    edge[:, :band] = edge[:, -band:] = True
    edge &= net < 0.1
    if edge.sum() < 200:
        return None
    bg_c, bg_v = float(np.median(c[edge])), float(np.median(v[edge]))
    if bg_c > 0.16 or bg_v < 0.45:
        return None
    c = cv2.GaussianBlur(c, (0, 0), max(1.0, 0.002 * max(h, w)))     # гасим цветной шум сжатия
    return c > bg_c + 0.10


def ellipse(h, w, share):
    r = max(1, round(share * max(h, w)))
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))


def main_parts(body):
    """Сам товар без обрывков соседних упаковок, зашедших в кадр с края."""
    h, w = body.shape
    n, lab, stats, _ = cv2.connectedComponentsWithStats(body.astype(np.uint8), connectivity=8)
    if n <= 1:
        return None
    areas = stats[1:, cv2.CC_STAT_AREA]
    main = 1 + int(np.argmax(areas))
    keep = np.zeros(n, bool)
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        sliver = (x == 0 or y == 0 or x + bw == w or y + bh == h) and area < 0.25 * areas.max()
        keep[i] = i == main or (area > 0.06 * areas.max() and not sliver)
    return keep[lab]


def fill_holes(body):
    """Дыры внутри силуэта (фон, видный сквозь плёнку между изделиями) остаются частью упаковки."""
    h, w = body.shape
    outside = np.pad(1 - body.astype(np.uint8), 1, constant_values=1)       # рамка в 1 px: «снаружи» связано через край кадра
    cv2.floodFill(outside, np.zeros((h + 4, w + 4), np.uint8), (0, 0), 2)
    return body | (outside[1:-1, 1:-1] == 1)


def doubt(rgb, core):
    """Причина не доверять найденному контуру (пустая строка, если контур похож на правду)."""
    h, w = core.shape
    cnts, _ = cv2.findContours(core.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    hull = cv2.contourArea(cv2.convexHull(np.vstack(cnts)))
    if hull <= 0 or core.sum() / hull < 0.5:
        return 'контур товара получился рваным (светлый товар на светлом фоне?)'
    # заметные детали — края, рисунок выпечки — за пределами контура значат, что часть товара в него не попала
    g = cv2.GaussianBlur(cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32), (0, 0), max(1.0, 0.002 * max(h, w)))
    e = np.hypot(cv2.Sobel(g, cv2.CV_32F, 1, 0), cv2.Sobel(g, cv2.CV_32F, 0, 1))
    strong = e > max(18.0, 3 * float(np.median(e)))
    around = cv2.dilate(core.astype(np.uint8), ellipse(h, w, 0.035)) > 0
    if strong.sum() and (strong & ~around).sum() / strong.sum() > 0.35:
        return 'заметная часть товара осталась за контуром'
    return ''


def wrapping(rgb, core):
    """Прозрачная упаковка вокруг товара: верх пакета с завязкой, края плёнки, бортик контейнера.
    Нейросеть её часто не видит, но на ровном фоне она выдаёт себя складками и бликами."""
    h, w = core.shape
    g = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    s1 = max(1.0, 0.003 * max(h, w))
    mean = cv2.GaussianBlur(g, (0, 0), s1)
    tex = np.sqrt(np.maximum(cv2.GaussianBlur(g * g, (0, 0), s1) - mean * mean, 0))      # местный разброс яркости
    far = cv2.distanceTransform((~core).astype(np.uint8), cv2.DIST_L2, 5) / (0.14 * max(h, w))     # 0 у товара, 1 на краю зоны поиска
    calm = ~cv2.dilate(core.astype(np.uint8), ellipse(h, w, 0.02)).astype(bool)
    level = max(4.0, 3.0 * float(np.median(tex[calm]))) if calm.sum() > 500 else 4.0
    # вплотную к товару хватает слабой фактуры (край плёнки), а чем дальше, тем она должна быть резче:
    # блики на верхушке пакета проходят, мягкие складки ткани и скатерть на столе — нет
    cand = ((tex > level * (1 + 1.5 * far)) & (far < 1) & ~core).astype(np.uint8)
    cand = cv2.morphologyEx(cand, cv2.MORPH_CLOSE, ellipse(h, w, 0.008))          # складки плёнки сливаются в пятно,
    cand = cv2.morphologyEx(cand, cv2.MORPH_OPEN, ellipse(h, w, 0.006))           # а одиночные линии (край стола) отпадают
    n, lab = cv2.connectedComponents((cand > 0).astype(np.uint8) | core.astype(np.uint8), connectivity=8)
    if n <= 1:
        return np.zeros_like(core)
    touching = np.zeros(n, bool)
    touching[np.unique(lab[core])] = True
    touching[0] = False
    return touching[lab] & ~core


def find_product(rgb):
    """(маска товара 0..1 с мягким краем, прозрачная упаковка вокруг него, причина сомнения).
    Маска None, если товар не найден."""
    h, w = rgb.shape[:2]
    net = model_mask(rgb)
    body = main_parts(net > 0.4)
    col = colour_mask(rgb, net)
    if col is not None:
        col = cv2.morphologyEx(col.astype(np.uint8), cv2.MORPH_OPEN, ellipse(h, w, 0.004)) > 0
        col = main_parts(cv2.morphologyEx(col.astype(np.uint8), cv2.MORPH_CLOSE, ellipse(h, w, 0.02)) > 0)
        # на блестящей плёнке нейросеть иногда принимает за фон половину упаковки; цвет выпечки её не обманывает:
        # если по цвету товар заметно больше, чем увидела нейросеть, берём оба контура вместе
        if col is not None and (body is None or body.sum() < 0.72 * col.sum()):
            body = col if body is None else main_parts(cv2.morphologyEx((body | col).astype(np.uint8), cv2.MORPH_CLOSE, ellipse(h, w, 0.012)) > 0)
    if body is None:
        return None, None, 'товар на снимке не найден'
    core = fill_holes(body)
    why = doubt(rgb, core)
    wrap = wrapping(rgb, core)
    # мягкий край, чуть подтянутый внутрь, чтобы не тянуть за собой кайму старого фона
    r = max(1.0, 0.0015 * max(h, w))
    alpha = np.clip((cv2.GaussianBlur(core.astype(np.float32), (0, 0), r) - 0.5) * 2.2 + 0.4, 0, 1)
    # полупрозрачная плёнка вокруг товара — там, где её отметила нейросеть
    near = cv2.dilate(core.astype(np.uint8), ellipse(h, w, 0.03)) > 0
    film = np.where(near, np.clip((net - 0.12) / 0.8, 0, 1), 0)
    return np.maximum(alpha, film), wrap, why


def cast_gain(rgb, m):
    """Поправка цвета, убирающая оттенок съёмки: стена за товаром должна быть того же тона, что единый фон,
    а не синеватой. Три множителя (R, G, B) или None, если фон не студийный и трогать цвет нельзя."""
    h = rgb.shape[0]
    wall = (m < 0.02)
    wall[int(h * 0.62):] = False                         # низ кадра — ткань и стол, по ним цвет не меряем
    if wall.sum() < 500:
        return None
    px = rgb[wall].astype(np.float32)
    lum = px.mean(1)
    mid = px[(lum >= np.percentile(lum, 30)) & (lum <= np.percentile(lum, 95))]
    b = np.median(mid if len(mid) else px, axis=0)
    if not np.isfinite(b).all() or b.min() < 1:
        return None
    if b.mean() < 120 or (b.max() - b.min()) > 40:       # фон тёмный или цветной — это не студийный фон
        return None
    tone = np.array(BACKDROP_TOP, np.float32)
    return np.clip(b.mean() / b * tone / tone.mean(), 0.9, 1.1)


# ---------------------------------------------------------------- сборка кадра (из снимка в полном размере)

def warp(img, M, size, border=cv2.BORDER_REPLICATE):
    """Перенос снимка в новый кадр. При сильном уменьшении снимок сначала усредняется, иначе мелкий рисунок
    (сетка печенья, штрихкод) пошёл бы рябью."""
    s, tx, ty = float(M[0, 0]), float(M[0, 2]), float(M[1, 2])
    if s < 0.7:
        p = s / 0.85
        img = cv2.resize(img, None, fx=p, fy=p, interpolation=cv2.INTER_AREA)
        tx, ty = tx + s * (0.5 / p - 0.5), ty + s * (0.5 / p - 0.5)
        s = s / p
    M = np.array([[s, 0, tx], [0, s, ty]], np.float32)
    return cv2.warpAffine(img, M, size, flags=cv2.INTER_CUBIC if s < 1 else cv2.INTER_LANCZOS4, borderMode=border)


def from_full(full, k, s, tx, ty, size, gain):
    """Кадр из полного снимка. s, tx, ty заданы для уменьшенной копии (она в 1/k раз меньше полного снимка)."""
    M = np.array([[s * k, 0, tx + s * (0.5 * k - 0.5)], [0, s * k, ty + s * (0.5 * k - 0.5)]], np.float32)
    out = warp(full, M, size).astype(np.float32)
    return out * gain if gain is not None else out


def reframe(full, k, shape, box, size, fill, gain):
    """kadr: окно 3:4 вокруг товара, вырезанное из самого снимка."""
    h, w = shape
    W, H = size
    x0, x1, y0, y1 = box
    bw, bh, aspect = x1 - x0, y1 - y0, W / H
    want = max(bw / min(0.88, fill * 1.1), bh / fill * aspect)        # ширина окна, при которой товар нужного размера
    least = max(bw, bh * aspect) / 0.97                               # уже этого нельзя: товар перестанет помещаться
    room = min(w, h * aspect)                                         # шире этого окно в снимок не влезет
    ww = min(want, room) if room >= least else least
    wh = ww / aspect
    left, top = (x0 + x1) / 2 - ww / 2, (y0 + y1) / 2 - 0.515 * wh     # товар чуть ниже середины: «стоит», а не висит
    left = min(max(left, 0), w - ww) if ww <= w else (w - ww) / 2
    top = min(max(top, 0), h - wh) if wh <= h else (h - wh) / 2
    s = W / ww
    out = from_full(full, k, s, -s * left, -s * top, size, gain)
    if ww > w + 0.5 or wh > h + 0.5:
        # снимок горизонтальный, а товар широкий: в вертикальный кадр он не помещается, поэтому фон
        # сверху и снизу (или по бокам) продолжен размытым краем самого снимка
        M = np.array([[s, 0, -s * left], [0, s, -s * top]], np.float32)
        inside = cv2.warpAffine(np.ones((h, w), np.uint8), M, (W, H), flags=cv2.INTER_NEAREST, borderValue=0)
        far = cv2.distanceTransform((1 - inside).astype(np.uint8), cv2.DIST_L2, 3)
        t = np.clip(far / (0.03 * H), 0, 1)[..., None]
        out = out * (1 - t) + cv2.GaussianBlur(out, (0, 0), 0.035 * H) * t
    return np.clip(out + 0.5, 0, 255).astype(np.uint8)


def backdrop(w, h):
    y = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    x = np.linspace(-1, 1, w, dtype=np.float32)[None, :]
    t = np.clip((y - 0.30) / 0.62, 0, 1)
    t = t * t * (3 - 2 * t)
    top, bot = np.array(BACKDROP_TOP, np.float32), np.array(BACKDROP_BOTTOM, np.float32)
    img = top + (bot - top) * t[..., None]
    return img * (1 - 0.05 * (x ** 2 + (2 * y - 1) ** 2) / 2)[..., None]     # едва заметное затемнение к краям


def on_backdrop(full, k, m, wrap, box, cut, size, fill, gain):
    """fon: товар на едином ровном фоне с мягкой тенью."""
    h, w = m.shape
    W, H = size
    x0, x1, y0, y1 = box
    bw, bh = x1 - x0, y1 - y0
    s = min(min(0.92, fill * 1.12) * W / bw, fill * H / bh)     # широкая упаковка занимает кадр почти на всю ширину
    # товар, обрезанный краем снимка, прижимаем к тому же краю нового кадра — иначе будет виден ровный срез
    if cut['l'] and cut['r']:
        s = max(s, W / bw)
    if cut['t'] and cut['b']:
        s = max(s, H / bh)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    tx, ty = W / 2 - s * cx, H * 0.515 - s * cy
    if cut['l'] and not cut['r']:
        tx = 0
    if cut['r'] and not cut['l']:
        tx = W - s * w
    if cut['b'] and not cut['t']:
        ty = H - s * h
    if cut['t'] and not cut['b']:
        ty = 0
    M = np.array([[s, 0, tx], [0, s, ty]], np.float32)
    fg = from_full(full, k, s, tx, ty, size, gain)
    a = np.clip(cv2.warpAffine(m, M, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0), 0, 1)
    seen = cv2.warpAffine(np.ones((h, w), np.float32), M, (W, H), flags=cv2.INTER_LINEAR, borderValue=0)     # где в новом кадре есть сам снимок
    film = cv2.warpAffine(wrap.astype(np.float32), M, (W, H), flags=cv2.INTER_LINEAR, borderValue=0) > 0.5     # прозрачная упаковка вокруг товара
    bg = backdrop(W, H)
    yy = np.linspace(0, 1, H, dtype=np.float32)[:, None]
    under = np.clip((yy - ((s * cy + ty) / H - 0.10)) / 0.25, 0, 1)
    under = under * under * (3 - 2 * under)                      # тень лежит в основном под товаром, над ним её почти нет
    low = cv2.warpAffine(a, np.array([[1, 0, 0.006 * W], [0, 1, 0.016 * H]], np.float32), (W, H))
    tight, wide = cv2.GaussianBlur(low, (0, 0), 0.012 * H), cv2.GaussianBlur(low, (0, 0), 0.05 * H)

    # Старый фон вокруг товара не срезается по контуру, а плавно уходит в новый: рядом с товаром остаётся сам снимок —
    # края плёнки, бортик контейнера, настоящая тень под упаковкой, — а дальше складки, край стола и полосы растворяются.
    # Чтобы переход не было видно, старый фон сначала выравнивается по яркости под новый.
    lum = fg.max(-1)
    back = ((a < 0.05) & (seen > 0.99)).astype(np.float32)
    r = max(1, round(0.02 * H))
    k2 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    top, share = cv2.dilate(lum * back, k2), cv2.dilate(back, k2)   # светлая огибающая: тени и тёмные стороны складок не занижают оценку
    field = cv2.GaussianBlur(top, (0, 0), 0.06 * H) / np.maximum(cv2.GaussianBlur(share, (0, 0), 0.06 * H), 1e-3)
    lift = bg.max(-1) / np.maximum(field, 1)
    known = cv2.GaussianBlur(share, (0, 0), 0.06 * H) > 0.05
    rr = max(1, round(0.06 * H))
    near = (cv2.dilate((a > 0.5).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * rr + 1, 2 * rr + 1))) > 0) & known
    if near.any() and 0.8 < float(np.median(lift[near])) < 1.4:          # фон светлый и ровный: его можно подтянуть к новому
        lifted = fg * np.clip(lift, 0.75, 1.5)[..., None]
        local = fg * a[..., None] + lifted * (1 - a[..., None])
        far = cv2.distanceTransform(((a < 0.5) & ~film).astype(np.uint8), cv2.DIST_L2, 5)
        keep = 1 - np.clip((far - 0.012 * H) / (0.055 * H), 0, 1)
        keep = (keep * keep * (3 - 2 * keep) * np.clip(seen * 4 - 3, 0, 1))[..., None]
        sh = np.clip((0.10 * tight + 0.20 * wide) * (0.4 + 0.6 * under), 0, 0.4)[..., None]      # своя тень у товара уже есть, добавляем только рассеянную
        bg = bg * (1 - sh) + np.array(SHADOW, np.float32) * sh
        out = local * keep + bg * (1 - keep)
    else:
        # фон тёмный или цветной — подтянуть его нельзя, поэтому товар вырезается по контуру
        sh = np.clip((0.30 * tight + 0.24 * wide) * (0.4 + 0.6 * under), 0, 0.5)[..., None]
        bg = bg * (1 - sh) + np.array(SHADOW, np.float32) * sh
        out = fg * a[..., None] + bg * (1 - a[..., None])
    return np.clip(out + 0.5, 0, 255).astype(np.uint8)


def clean(img, size=(1200, 1600), fill=0.80):
    """PIL.Image -> (kadr, fon, пометка). Пометка пустая, если снимок обработан; иначе в ней причина,
    по которой снимок оставлен как есть (оба варианта — исходный снимок, подогнанный под размер кадра)."""
    img = to_rgb(img)
    full = np.asarray(img)

    def as_is(why):
        same = ImageOps.fit(img, size, Image.LANCZOS)
        return same, same, why

    k = min(1.0, WORK / max(full.shape[:2]))
    small = cv2.resize(full, (max(1, round(full.shape[1] * k)), max(1, round(full.shape[0] * k))), interpolation=cv2.INTER_AREA) if k < 1 else full
    k = small.shape[1] / full.shape[1]
    m, wrap, why = find_product(small)
    if m is None or (m > 0.5).sum() < 0.01 * m.size:
        return as_is('товар на снимке не найден')
    if why:
        return as_is(why)
    h, w = m.shape
    gain = cast_gain(small, m)
    frames = []
    for level in (0.25, 0.5):                           # для кадрирования контур берём щедрее, вместе с краями плёнки
        solid = m > level
        # габариты товара вместе с упаковкой; узкие выступы (завязка пакета, случайная складка рядом) их не раздувают
        whole = solid | wrap
        cols, rows = whole.sum(0), whole.sum(1)
        xs, ys = np.where(cols >= 0.06 * cols.max())[0], np.where(rows >= 0.06 * rows.max())[0]
        x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
        # товар обрезан краем снимка, если он сам (не плёнка и не сосед) касается края на заметной длине
        band = max(3, round(0.012 * max(h, w)))
        cut = {'l': solid[:, :band].any(1).sum() >= 0.04 * h, 'r': solid[:, -band:].any(1).sum() >= 0.04 * h,
               't': solid[:band].any(0).sum() >= 0.04 * w, 'b': solid[-band:].any(0).sum() >= 0.04 * w}
        if sum(cut.values()) >= 3:
            return as_is('товар занимает весь кадр и обрезан краями')
        box = (0 if cut['l'] else x0, w if cut['r'] else x1, 0 if cut['t'] else y0, h if cut['b'] else y1)
        frames.append((box, cut))
    kadr = reframe(full, k, (h, w), frames[0][0], size, fill, gain)
    fon = on_backdrop(full, k, m, wrap, frames[1][0], frames[1][1], size, fill, gain)
    return Image.fromarray(kadr), Image.fromarray(fon), ''


# ---------------------------------------------------------------- запуск

def auto_size(img):
    """Размер кадра 3:4 по исходнику: такой же, но не больше 1200x1600 и не меньше 600x800."""
    w, h = img.size
    if getattr(img, 'getexif', None) and img.getexif().get(0x0112) in (5, 6, 7, 8):     # снимок повёрнут камерой набок
        w, h = h, w
    side = max(600, min(1200, round(min(w, h * 3 / 4))))
    side = 3 * round(side / 3)
    return side, side // 3 * 4


def main():
    for stream in (sys.stdout, sys.stderr):                 # русские буквы в консоли Windows
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(errors='replace')
    ap = argparse.ArgumentParser(description='Фото товара: кадр 3:4, товар по центру и одного размера, в двух вариантах — fon (единый фон) и kadr (только кадрирование).')
    ap.add_argument('src', help='папка с фотографиями или один файл')
    ap.add_argument('dst', help='папка для результата')
    ap.add_argument('--size', default='auto', help='auto (как у исходника, от 600x800 до 1200x1600) или ШxВ, например 900x1200')
    ap.add_argument('--fill', type=float, default=0.80)
    ap.add_argument('--format', choices=('webp', 'jpg', 'png'), default='webp')
    ap.add_argument('--skip-existing', action='store_true', help='не обрабатывать снимки, для которых результат уже есть')
    args = ap.parse_args()
    fixed = None if args.size.lower() == 'auto' else tuple(int(v) for v in args.size.lower().split('x'))
    ext = {'webp': '.webp', 'jpg': '.jpg', 'png': '.png'}[args.format]
    src, dst = Path(args.src), Path(args.dst)
    files = [src] if src.is_file() else sorted(p for p in src.iterdir() if p.suffix.lower() in EXTS)
    if not files:
        sys.exit('В папке нет фотографий (jpg, png, webp).')
    for sub in ('fon', 'kadr', '_proverka'):
        (dst / sub).mkdir(parents=True, exist_ok=True)

    def save(picture, path):
        if args.format == 'webp':
            picture.save(path, 'WEBP', quality=88, method=6)
        elif args.format == 'png':
            picture.save(path, 'PNG')
        else:
            picture.save(path, 'JPEG', quality=90, optimize=True)

    before = {}                                             # итоги прошлого запуска: у пропущенных файлов они сохраняются
    if args.skip_existing and (dst / 'otchet.csv').exists():
        with open(dst / 'otchet.csv', newline='', encoding='utf-8-sig') as old:
            before = {r[0]: tuple(r[:3]) for r in csv.reader(old, delimiter=';') if len(r) >= 3 and r[1] in ('ok', 'as_is')}
    done, kept, failed, skipped, rows = 0, [], [], 0, []
    for f in files:
        name = f.stem + ext
        if args.skip_existing and (dst / 'fon' / name).exists() and (dst / 'kadr' / name).exists():
            skipped += 1
            rows.append(before.get(f.name, (f.name, 'skipped', 'результат уже есть')))
            continue
        try:
            img = Image.open(f)
            W, H = fixed or auto_size(img)
            kadr, fon, note = clean(img, (W, H), args.fill)
            was = ImageOps.fit(to_rgb(img), (W, H), Image.LANCZOS)
        except Exception as e:                              # один плохой файл не останавливает всю пачку
            failed.append(f.name)
            hint = ' (снимки HEIC с айфона сначала сохраните как JPG)' if f.suffix.lower() in ('.heic', '.heif') else ''
            rows.append((f.name, 'error', f'{e}{hint}'))
            print(f'  ошибка   {f.name}: {e}{hint}')
            continue
        save(kadr, dst / 'kadr' / name)
        save(fon, dst / 'fon' / name)
        three = Image.new('RGB', (W * 3 + 40, H), (34, 34, 34))
        for i, picture in enumerate((was, kadr, fon)):
            three.paste(picture, (i * (W + 20), 0))
        if H > 1000:
            three = three.reduce(2)
        three.save(dst / '_proverka' / (f.stem + '.jpg'), 'JPEG', quality=85)
        if note:
            kept.append(f.name)
            rows.append((f.name, 'as_is', note))
            print(f'  как есть {f.name}: {note}')
        else:
            done += 1
            rows.append((f.name, 'ok', ''))
            print(f'  готово   {f.name}')
    with open(dst / 'otchet.csv', 'w', newline='', encoding='utf-8-sig') as out:        # открывается в Excel
        table = csv.writer(out, delimiter=';')
        table.writerow(('file', 'status', 'note'))
        table.writerows(rows)
    print(f'Обработано {done} из {len(files)}. Результат: {dst} (подпапки fon и kadr, итог по файлам — otchet.csv)')
    print('Проверка глазами: подпапка _proverka — слева как было, в середине kadr, справа fon.')
    if skipped:
        print(f'Пропущено, потому что результат уже есть: {skipped}')
    if kept:
        print('Оставлены как есть (только подогнаны под размер кадра): ' + ', '.join(kept))
    if failed:
        print('Не открылись: ' + ', '.join(failed))


if __name__ == '__main__':
    main()
