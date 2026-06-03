# `local_stats2.0.sh` — прошивка android-test

Цветной bash-скрипт для сборки, установки и запуска ассистента на
подключённый Android-телефон. Замена старого `local_stast.sh`.

## Что делает

1. Грузит API-ключи из `../.env` (`OPENROUTER_API_KEY`, `GROQ_API_KEY`)
2. Проверяет, что телефон подключён по adb (показывает модель и Android)
3. Собирает `app-debug.apk` через `gradlew assembleDebug --no-daemon`
4. Ставит APK на устройство
5. Запускает `com.assistant.app/.MainActivity` и показывает `pid`
6. Опционально — коммитит и пушит изменения в `android-test/`
7. Опционально — показывает свежий logcat

## Использование

```bash
# Полный цикл: билд + установка + запуск
./local_stats2.0.sh

# Только переустановка (если APK уже собран)
./local_stats2.0.sh --no-build

# Свежий logcat после запуска
./local_stats2.0.sh --logs

# После билда — git commit + push (только android-test/)
./local_stats2.0.sh --push "fix: кнопка назад в под-табах"

# Если install завис — перезапустить adb-server
./local_stats2.0.sh --reboot-adb

# Комбинируй
./local_stats2.0.sh --no-build --logs --reboot-adb
```

## Опции

| флаг | что делает |
|---|---|
| `--no-build` | пропускает gradle assembleDebug |
| `--logs` | после запуска выводит `logcat -d --pid=<pid>` (последние 40 строк) |
| `--push "msg"` | после билда: `git add android-test/` → commit → `push origin master` |
| `--reboot-adb` | `adb kill-server` + `start-server` (лечит зависший `install`) |
| `-h`, `--help` | эта справка |

## Переменные окружения

| где | зачем |
|---|---|
| `../.env` | `OPENROUTER_API_KEY`, `GROQ_API_KEY` (опц.) — пробрасываются в `BuildConfig` |
| `OPENROUTER_API_KEY` | основной ключ, без него скрипт ругается и выходит |
| `GROQ_API_KEY` | опциональный, для второго провайдера |

Если `../.env` нет — скрипт всё равно работает, но ключи будут пустыми.

## Что лечит

- **`install` висит на `Performing Streamed Install`** — `--reboot-adb` чинит
- **Хочется быстро перезапустить** (APK не менялся) — `--no-build`
- **APK не обновляется, хотя код менял** — в кэше Gradle устарело; скрипт
  не делает `clean` чтобы не замедлять, вручную: `./gradlew clean assembleDebug`

## Пример вывода

```
   _      __    __       ___            __  __
  | | /| / /__ / /  ___ / _ \___ ___ __/ _\/ /  ___ ____
  | |/ |/ / -_) _ \/ _ \/ // / -_) _ `/ __/ _ \/ _ `(_-<
  |__/|__/\__/_.__/\___/____/\__/\_,_/\__/_//_/\_,_/___/

  прошивка android-test ассистента
───────────────────────────────────────────────────────────────
  ✓ ключи из ../.env подгружены

▸ проверяю adb и устройство
  ✓ устройство: qcbez5xcypscl7qo (Redmi Note 8 Pro, Android 11)

▸ собираю APK (gradle assembleDebug)
  > Task :app:assembleDebug
  BUILD SUCCESSFUL in 45s
  ✓ сборка прошла (52s)

▸ ставлю APK
  ✓ apk: app/build/outputs/apk/debug/app-debug.apk (9.1M)
  Performing Streamed Install
  Success
  ✓ установлено

▸ запускаю
  Starting: Intent { cmp=com.assistant.app/.MainActivity }
  ✓ запущено, pid=6844 (55s)
───────────────────────────────────────────────────────────────
✓ готово  общее время: 55s
```

## Связь с git

`--push` коммитит **только** `android-test/`. Родительские файлы
(`Dockerfile`, `codex-*.sh`, `attach-phone.sh`, `out/`, `misc/` и т.п.)
не трогает — они не относятся к приложению.

## Сравнение с `local_stast.sh`

| | `local_stast.sh` | `local_stats2.0.sh` |
|---|---|---|
| Цветной вывод | ✗ | ✓ |
| Баннер | ✗ | ✓ |
| Тайминг шагов | ✗ | ✓ |
| Проверка устройства | ✗ | ✓ (показ модели) |
| `--no-build` | ✗ | ✓ |
| `--logs` | ✗ | ✓ |
| `--push` | ✗ | ✓ |
| `--reboot-adb` (лечит зависший install) | ✗ | ✓ |
| `--no-daemon` для gradle | ✗ | ✓ |
| Помощь (`-h`) | ✗ | ✓ |
| Возврат на «Инфо» при back в под-табах | ✗ | ✓ (в самом приложении) |

## Сборка

```
docker build --build-arg API_KEY=sk-or-v1-... -t assistant .
```

Ключ читается в `BuildConfig.OPENROUTER_API_KEY` через gradle property.
