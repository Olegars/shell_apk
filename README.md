# CompClub TV Shell

Android TV / Google TV APK: вход по телефону + PIN (как PC shell), экран сессии, LAN HTTP-команды.

## Открыть в Android Studio

1. File → Open → `C:\Qt\shell_apk`
2. Sync Gradle
3. Подключить ТВ по ADB (`adb connect IP:5555`) или эмулятор TV
4. Run `app`

## Настройка на ТВ

При первом запуске → **Настройки**:
- URL сервера, например `https://club.example.com` (без `/` в конце)
- `terminal_id` = `computers.id` этой ТВ-станции в booking

## API (как PC shell)

- `POST /api/shell/login` `{ phone, pin, terminal_id }`
- `POST /api/shell/logout` `{ terminal_id }`

## LAN команды (порт 8787)

Фоновый сервис слушает на ТВ:

```http
GET  http://TV_IP:8787/health
POST http://TV_IP:8787/command
Content-Type: application/json

{"action":"show_message","text":"Добро пожаловать"}
{"action":"clear_message"}
{"action":"session_end"}
{"action":"open_login"}
{"action":"ping"}
```

## Дальше

- Автозапуск после BOOT_COMPLETED
- Kiosk / lock task mode
- Привязка ТВ как `Computer` kind=tv в админке
- Wake через Android TV Remote при необходимости
