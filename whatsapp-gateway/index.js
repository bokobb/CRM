import 'dotenv/config';
import fs from 'node:fs';
import {
  makeWASocket,
  useMultiFileAuthState,
  DisconnectReason,
  fetchLatestBaileysVersion,
} from '@whiskeysockets/baileys';
import { Boom } from '@hapi/boom';
import qrcode from 'qrcode-terminal';
import QRCode from 'qrcode';
import express from 'express';
import axios from 'axios';
import pino from 'pino';

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || '';
const PORT = process.env.PORT || 3001;
const AUTH_DIR = process.env.AUTH_DIR || './auth_info';

if (!WEBHOOK_SECRET) {
  console.warn(
    'ВНИМАНИЕ: WEBHOOK_SECRET не задан в .env — backend будет отклонять запросы этого шлюза ' +
      '(и /send тоже не будет работать). Задайте одинаковый секрет здесь и в crm.whatsapp.webhook-secret.',
  );
}

const logger = pino({ level: 'warn' });
let sock;

async function startSock() {
  const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
  const { version } = await fetchLatestBaileysVersion();

  sock = makeWASocket({
    version,
    auth: state,
    logger,
  });

  sock.ev.on('creds.update', saveCreds);

  sock.ev.on('connection.update', (update) => {
    const { connection, lastDisconnect, qr } = update;
    if (qr) {
      console.log('\nОтсканируйте QR в WhatsApp: Настройки -> Связанные устройства -> Привязать устройство\n');
      qrcode.generate(qr, { small: true });
      QRCode.toFile('./qr.png', qr, { width: 400 }).catch((e) => console.error('Не удалось сохранить qr.png:', e));
    }
    if (connection === 'close') {
      const statusCode = new Boom(lastDisconnect?.error)?.output?.statusCode;
      const shouldReconnect = statusCode !== DisconnectReason.loggedOut;
      console.log('Соединение с WhatsApp закрыто, код:', statusCode, '— переподключение:', shouldReconnect);
      if (shouldReconnect) {
        startSock();
      } else {
        console.log(`Сессия разлогинена. Удалите папку ${AUTH_DIR} и перезапустите шлюз, чтобы отсканировать QR заново.`);
      }
    } else if (connection === 'open') {
      console.log('WhatsApp подключён и готов принимать заказы.');
      fs.rm('./qr.png', { force: true }, () => {});
    }
  });

  sock.ev.on('messages.upsert', async ({ messages, type }) => {
    if (type !== 'notify') return;
    for (const msg of messages) {
      try {
        await handleIncomingMessage(msg);
      } catch (e) {
        console.error('Ошибка обработки входящего сообщения:', e);
      }
    }
  });
}

function extractText(msg) {
  const m = msg.message;
  if (!m) return null;
  return (
    m.conversation ||
    m.extendedTextMessage?.text ||
    m.imageMessage?.caption ||
    m.videoMessage?.caption ||
    null
  );
}

async function handleIncomingMessage(msg) {
  if (msg.key.fromMe) return;
  if (msg.key.remoteJid === 'status@broadcast') return;
  if (msg.key.remoteJid?.endsWith('@g.us')) return; // групповые чаты не обрабатываем — только личные заказы

  const text = extractText(msg);
  if (!text) return;

  // WhatsApp в новых чатах шлёт remoteJid в приватном формате "<id>@lid" вместо номера
  // телефона — реальный номер лежит в senderPn (тоже в виде JID). Без этого клиент не
  // находится по телефону и на каждое сообщение создаётся новый дубликат клиента.
  const phoneJid = msg.key.senderPn || msg.key.remoteJid;
  const phone = phoneJid.replace(/@s\.whatsapp\.net$/, '');
  const name = msg.pushName || '';

  console.log(`Входящее от ${phone} (${name}): ${text}`);

  try {
    await axios.post(
      `${BACKEND_URL}/api/whatsapp/incoming`,
      { from: phone, text, name },
      { headers: { 'X-Webhook-Secret': WEBHOOK_SECRET }, timeout: 30000 },
    );
  } catch (e) {
    console.error('Не удалось передать сообщение в backend:', e.response?.status, e.response?.data || e.message);
  }
}

function toJid(to) {
  return to.includes('@') ? to : `${to.replace(/\D/g, '')}@s.whatsapp.net`;
}

function checkAuth(req, res) {
  if (!WEBHOOK_SECRET || req.header('X-Webhook-Secret') !== WEBHOOK_SECRET) {
    res.status(403).json({ error: 'forbidden' });
    return false;
  }
  if (!sock) {
    res.status(503).json({ error: 'WhatsApp ещё не подключён' });
    return false;
  }
  return true;
}

// --- HTTP API для backend: сюда CRM стучится, чтобы отправить ответ клиенту ---
const app = express();
app.use(express.json({ limit: '20mb' })); // накладные в base64 могут быть тяжелее дефолтного лимита 100kb

app.post('/send', async (req, res) => {
  if (!checkAuth(req, res)) return;
  const { to, text } = req.body || {};
  if (!to || !text) {
    return res.status(400).json({ error: 'to и text обязательны' });
  }
  try {
    await sock.sendMessage(toJid(to), { text });
    console.log(`Отправлено сообщение -> ${to}`);
    res.json({ ok: true });
  } catch (e) {
    console.error('Ошибка отправки сообщения:', e);
    res.status(500).json({ error: 'send failed' });
  }
});

// Документ (например PDF-накладная) — см. InvoicePdfService/OrderService.ship на backend.
app.post('/send-document', async (req, res) => {
  if (!checkAuth(req, res)) return;
  const { to, filename, caption, dataBase64 } = req.body || {};
  if (!to || !filename || !dataBase64) {
    return res.status(400).json({ error: 'to, filename и dataBase64 обязательны' });
  }
  try {
    await sock.sendMessage(toJid(to), {
      document: Buffer.from(dataBase64, 'base64'),
      fileName: filename,
      mimetype: 'application/pdf',
      caption,
    });
    console.log(`Отправлен документ ${filename} -> ${to}`);
    res.json({ ok: true });
  } catch (e) {
    console.error('Ошибка отправки документа:', e);
    res.status(500).json({ error: 'send failed' });
  }
});

app.listen(PORT, () => console.log(`WhatsApp-шлюз слушает на порту ${PORT} (backend: ${BACKEND_URL})`));

startSock();
