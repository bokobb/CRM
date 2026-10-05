import 'package:firebase_core/firebase_core.dart';

/// Настройки Firebase для веб-версии (iPhone, «На экран Домой») — веб-приложение «CRM iPhone» в
/// проекте crm-confectionery. Не секрет: эти значения в любом случае видны в коде веб-страницы.
/// Android берёт свои из android/app/google-services.json.
const firebaseWebOptions = FirebaseOptions(
  apiKey: 'AIzaSyBql5_12fIf2Aku8lVAE-lREA8H1zztFlo',
  authDomain: 'crm-confectionery.firebaseapp.com',
  projectId: 'crm-confectionery',
  storageBucket: 'crm-confectionery.firebasestorage.app',
  messagingSenderId: '399095690242',
  appId: '1:399095690242:web:f7b77531d3fdd6c9de631b',
  measurementId: 'G-JVYET29GC8',
);

/// Ключ Web Push (Firebase › Cloud Messaging › Web Push certificates). null — Firebase
/// использует свой стандартный ключ.
const String? firebaseWebVapidKey = null;
