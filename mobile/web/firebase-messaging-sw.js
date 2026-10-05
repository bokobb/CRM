// Фоновый обработчик push для веб-версии (iPhone, «На экран Домой»). Сервер отдаёт этот файл
// из корня сайта (/firebase-messaging-sw.js, см. WebPushApiController) — там его ищет Firebase.
// Уведомление показывает сам Firebase по блоку webpush.notification из сообщения сервера.
importScripts('https://www.gstatic.com/firebasejs/11.9.1/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/11.9.1/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: 'AIzaSyBql5_12fIf2Aku8lVAE-lREA8H1zztFlo',
  authDomain: 'crm-confectionery.firebaseapp.com',
  projectId: 'crm-confectionery',
  storageBucket: 'crm-confectionery.firebasestorage.app',
  messagingSenderId: '399095690242',
  appId: '1:399095690242:web:f7b77531d3fdd6c9de631b',
});

firebase.messaging();

// Нажатие на уведомление — открыть приложение (или переключиться на уже открытое).
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = '/app/index.html';
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const c of list) {
        if (c.url.includes('/app/') && 'focus' in c) return c.focus();
      }
      return clients.openWindow(url);
    })
  );
});
