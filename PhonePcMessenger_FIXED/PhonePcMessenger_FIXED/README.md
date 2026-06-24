# PhonePcMessenger FIXED

Это проект для общения телефон ↔ ПК.

## Что исправлено

В прошлой версии Windows могла ломать `.bat` из-за кириллицы/кодировки. В этой версии все `.bat` файлы сделаны только на английском и используют относительные пути.

## ПК

1. Распакуй архив.
2. Запусти:

```bat
build-pc.bat
run-pc.bat
```

В окне ПК будет показан IP. Его надо ввести в приложении на телефоне.

## Android

Сборка APK:

```bat
build-android.bat
```

APK будет тут:

```text
android-app\app\build\outputs\apk\debug\app-debug.apk
```

Для локальной сборки APK нужен Android Studio / Android SDK + Gradle.
Если этого нет, залей проект на GitHub — workflow `.github/workflows/android-apk.yml` соберёт APK автоматически.

## Порты

- TCP чат: 5555
- UDP звонок: 5556

Телефон и ПК должны быть в одной Wi-Fi сети. В Windows Firewall разреши Java доступ к частной сети.
