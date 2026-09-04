# Архитектура MVP 1

Дата фиксации: 2026-09-04.

## Задача

MVP 1 доказывает передачу текста между Android и браузером Windows в обе стороны. Файлы сознательно исключены до успешной проверки всего текстового цикла и фоновой работы сервера.

## Проверенная среда

- Google Pixel 7.
- Android 17 / API 37.
- Windows-клиент без устанавливаемого ПО.
- Microsoft Edge.
- Телефон и ПК в одной Wi-Fi/LAN.
- Karing может работать в TUN-режиме; Local Bridge исключается из его проксирования.
- APK распространяется через GitHub.

## Уже подтверждено на реальном устройстве

1. APK устанавливается и приложение запускается без падения.
2. Android 17 выдаёт Local Bridge доступ к локальной сети.
3. Edge на другом устройстве открывает HTTP-сервер Pixel 7 по локальному IPv4.
4. Текст передаётся ПК → Android clipboard.
5. Текст передаётся Android clipboard → ПК через кнопку приложения и SSE без F5.
6. Кнопка копирования в Edge работает в тестовом сценарии.

Выявленный дефект 0.3.0-dev: HTTP-сервер принадлежал `MainActivity` и останавливался при уничтожении Activity. Версия 0.4.0-dev переносит сервер в отдельный foreground service.

## Текущий стек

- Kotlin.
- Android SDK / targetSdk 37.
- Jetpack Compose.
- `BridgeService` — foreground service, владеющий HTTP-сервером.
- Минимальный встроенный HTTP-сервер на `ServerSocket`.
- Чистые HTML/CSS/JavaScript для Windows-клиента.
- Server-Sent Events (SSE) для push Android → браузер.
- HTTP POST для браузер → Android.

Ktor рассматривался на этапе проектирования, но для текущего MVP не используется: после первого сетевого smoke-test минимальный `ServerSocket` оказался достаточен и уменьшил зависимости.

## Жизненный цикл сервера

`MainActivity` больше не владеет HTTP-сервером.

Схема:

```text
MainActivity
  ├─ запрашивает ACCESS_LOCAL_NETWORK
  ├─ запускает / останавливает BridgeService
  ├─ читает Android clipboard только по кнопке
  └─ временно bind'ится к BridgeService для UI и отправки текста

BridgeService (foreground service)
  ├─ LocalHttpServer
  ├─ запись ПК → Android clipboard
  ├─ SSE Android → Edge
  ├─ хранение текущего состояния сервера
  └─ постоянное системное уведомление
```

После запуска `BridgeService` продолжает работу при сворачивании Activity и при удалении Activity из recent apps. `android:stopWithTask="false"` задан явно. Сервис использует `START_STICKY`, поэтому Android может восстановить его после обычного убийства процесса системой.

Принудительный `Force stop` приложения пользователем/системой остаётся абсолютным стоп-сценарием — обходить его приложение не должно.

## Управление сервером

При открытии Local Bridge сохраняется удобное поведение MVP: сервер пытается стартовать автоматически после проверки разрешения локальной сети.

В Activity есть одна явная кнопка состояния:

- `ОСТАНОВИТЬ СЕРВЕР`, когда сервер работает;
- `ЗАПУСТИТЬ СЕРВЕР`, когда он остановлен;
- `РАЗРЕШИТЬ И ЗАПУСТИТЬ СЕРВЕР`, если Android 17 permission отсутствует.

Foreground notification показывает, что сервер продолжает жить после закрытия Activity, отображает адрес при его наличии и содержит действие `Остановить`.

## Foreground service

Тип: `connectedDevice`.

Manifest permissions:

- `FOREGROUND_SERVICE`;
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`;
- `CHANGE_NETWORK_STATE` как сетевой prerequisite для типа `connectedDevice`;
- `INTERNET`;
- `ACCESS_LOCAL_NETWORK`.

`dataSync` сознательно не используется: Local Bridge должен иметь возможность работать весь рабочий день, а не быть задачей ограниченной длительности.

## Android clipboard

### ПК → телефон

1. Edge делает `POST /api/clipboard`.
2. `LocalHttpServer` передаёт текст `BridgeService`.
3. `BridgeService` вызывает `ClipboardManager.setPrimaryClip()`.
4. Это направление не требует открытой Activity.

### Телефон → ПК

1. Пользователь копирует текст в ChatGPT.
2. Открывает Local Bridge.
3. Нажимает `ОТПРАВИТЬ БУФЕР НА ПК`.
4. Только видимая `MainActivity` читает `ClipboardManager.getPrimaryClip()`.
5. Текст передаётся в работающий `BridgeService`.
6. Сервер отправляет SSE-событие в Edge.

Фоновое чтение Android clipboard не используется.

## Локальная сеть Android 17

Приложение таргетит API 37 и запрашивает runtime permission `android.permission.ACCESS_LOCAL_NETWORK` до старта сервера.

Адрес для UI определяется не через абстрактную active network, чтобы не выбрать TUN/VPN Karing. `LanAddressFinder` ищет подходящий локальный IPv4 и отбрасывает VPN/туннельные интерфейсы.

Отдельная реакция на смену Wi-Fi/IP через `NetworkCallback` ещё не реализована и остаётся дальнейшей задачей после проверки foreground service.

## HTTP API текущего MVP

- `GET /` — web UI.
- `GET /health` — простой health-check.
- `POST /api/clipboard` — ПК → Android clipboard.
- `GET /events` — SSE Android → Edge.

PIN/session пока специально не включены: сначала должен быть подтверждён устойчивый текстовый цикл вместе с foreground service.

## Безопасность — следующий этап после PASS 0.4.0-dev

Минимальная локальная авторизация:

- случайный PIN;
- session cookie после успешного PIN;
- API и SSE недоступны без сессии;
- CORS не разрешается;
- HTTPS в MVP не вводится.

## Что сознательно не входит в MVP 1

- Передача файлов.
- База данных и история.
- WebSocket.
- HTTPS / собственный CA.
- mDNS.
- QR pairing.
- Root / Shizuku / Accessibility.
- Фоновое слежение за clipboard.
- Автозапуск после перезагрузки Android.

## Порядок оставшихся проверок

1. Установить 0.4.0-dev поверх 0.3.0-dev.
2. Подтвердить, что сервер остаётся доступным после сворачивания Local Bridge и перехода в ChatGPT.
3. Подтвердить, что сервер остаётся доступным после удаления Activity из recent apps.
4. Проверить кнопку старт/стоп и остановку из foreground notification.
5. Повторно проверить текст в обе стороны после фонового перехода.
6. После PASS добавить PIN + session.
7. Отдельно проверить hotspot Pixel 7 + Karing.
8. Только после этого переходить к MVP 2 с файлами.
