---
name: flash-xiaomi
description: Прошивка Xiaomi/Redmi телефонов — bootloader unlock, EDL/Fastboot/Recovery, кастомные ROM, откат на сток. Использовать, когда пользователь просит прошить телефон, разблокировать загрузчик, поставить TWRP/MIUI/HyperOS/LineageOS, или когда adb/fastboot не видит устройство в нужном режиме.
---

# Прошивка Xiaomi/Redmi

Пошаговые инструкции и проверенные рецепты для Xiaomi (включая Redmi, POCO). Особенности: HyperOS/MIUI блокировка загрузчика, EDL-режим через test-point, WSL2-проброс USB.

## Когда вызывать

- «прошей телефон», «перепрошей», «поставь ROM», «обнови до HyperOS», «откати на MIUI»
- «разблокируй загрузчик», «unlock bootloader»
- «поставь TWRP», «root», «Magisk»
- «не видит fastboot», «adb не видит», «телефон в EDL», «Qualcomm HS-USB QDLoader 9008»
- «Xiaomi/Redmi/POCO»

## Среда

- Хост: **WSL2 + Windows**, либо нативный Linux
- Телефон: Xiaomi/Redmi/POCO с заблокированным или разблокированным загрузчиком
- Утилиты: `adb`, `fastboot`, `miflash_unlock`, `MiFlashTool` (Windows), `python` (для `edl.py`)

## Режимы загрузки Xiaomi (как попасть)

| Режим | Как попасть | Что видно на ПК | Что делает |
|---|---|---|---|
| **Android (нормальный)** | Включение | Redmi/MTP+ADB, `lsusb` 2717:ff48 | Обычная работа, отладка |
| **Fastboot** | Выкл → `Vol−` + питание, держать 3 сек | `lsusb` 2717:ee8d (или `18d1:4ee0` для ADB-интерфейса) | Прошивка разделов, unlock |
| **Recovery** | Выкл → `Vol+` + питание | 2717:ee8d | Wipe, sideload |
| **EDL (Download)** | Выкл → `Vol+`+`Vol−` + питание, **или test-point** | `lsusb` 05c6:9008 (Qualcomm HS-USB QDLoader) | Аварийная прошивка через MiFlash |
| **Fastbootd (dynamic)** | `adb reboot fastboot` из Android | 2717:ee8d | Прошивка dynamic-разделов (`--slot=all`) |

**Test-point** (если телефон «окирпичился» и не входит ни в один режим): см. [edl_point.png](https://xiaomiui.eu/test-point-database) — замыкаете контакт на плате при подключении USB.

## Шаг 1. Подготовка (что КАЖДЫЙ раз должно быть)

1. На телефоне (если включается):
   - **Настройки → О телефоне** → 7 тапов по «Версия ОС» (включает режим разработчика)
   - **Для разработчиков → Отладка по USB** = Вкл
   - **Отладка по USB (настройки безопасности)** = Вкл *(без неё MIUI/HyperOS блокирует adb)*
   - **Разблокировка OEM** = Вкл
   - Включить «Стирание данных при разблокировке» (на некоторых)
2. **SIM-карта**: многие Xiaomi блокируют unlock, пока аккаунт привязан < 72/720 часов. На POCO обычно проще.
3. **Mi-аккаунт**: для официального unlock нужно добавить телефон в аккаунт и подать заявку на unlock в `miflash_unlock`.

## Шаг 2. USB-проброс (WSL2)

Без этого `adb`/`fastboot` ничего не увидит.

**Алиас в `~/.bashrc`:**
```bash
alias usbipd="/mnt/c/Program\ Files/usbipd-win/usbipd.exe"
```

**Проброс (запускать скрипт `./attach-phone.sh`):**
```bash
./attach-phone.sh
# или
./attach-phone.sh "Xiaomi"
```

Если Xiaomi показывает **несколько интерфейсов** в `usbipd list` (MTP, ADB, RNDIS) — скрипт выбирает тот, где в имени есть «ADB».

## Шаг 3. Разблокировка загрузчика (официальная, через Mi)

Только для официально купленных в своём регионе. На серых/китайских версиях — см. ниже bypass.

```powershell
# Windows, PowerShell от админа — miflash_unlock
miflash_unlock.exe
# логинитесь в Mi-аккаунт → Apply
# телефон перезагрузится в fastboot, покажет «Unlock the bootloader», кнопкой Vol+ подтверждаете
```

**Сроки ожидания:** для телефонов с привязкой < 72 ч — сразу. Для 72–720 ч — счётчик в MiFlash. Для > 720 ч — обычно проходит.

## Шаг 3b. Альтернативный unlock (бесплатный, китайские версии)

Через `python3 -m一键解锁` или вручную:
1. Получить токен unlock с сайта `https://miunlock.avlyun.com/` (если ещё работает)
2. Или через локальный bypass (`unlock_token` через EDL)
3. На POCO F-серии обычно unlock без ожидания

## Шаг 4. Прошивка через Fastboot

```bash
# проверить, что телефон в fastboot виден
fastboot devices
# должен быть 2717:ee8d

# разблокировать (если ещё нет)
fastboot oem unlock                 # старый
fastboot flashing unlock            # новый

# скачать стоковую прошивку (TGZ) с https://xiaomiui.net/stock-rom или https://miui.com
# распаковать — там будут images/*.img и scripts/flash_all.sh

cd <распакованная прошивка>
bash scripts/flash_all.sh           # либо
fastboot flash boot boot.img
fastboot flash system system.img    # для A/B устройств добавьте --slot=a (или _a, _b)
# ...
fastboot reboot
```

**Слоты A/B:** современные Xiaomi (Redmi Note 8 Pro — НЕ A/B; POCO F4 — A/B). Узнать:
```bash
fastboot getvar current-slot
# "current-slot: a" — A/B устройство
```
Для A/B — `--slot=all` или добавляйте суффиксы: `boot_a.img`, `boot_b.img`.

## Шаг 5. Кастомная прошивка (LineageOS / crDroid / PixelExperience)

1. Установить кастомный recovery (TWRP / OrangeFox / PitchBlack) — обычно через fastboot:
   ```bash
   fastboot flash recovery twrp-xiaomi-begonia-3.7.0_12-1.img
   fastboot boot twrp-xiaomi-begonia-3.7.0_12-1.img
   ```
2. Из recovery → Wipe → Format Data (сбросит внутреннюю память)
3. ADB Sideload → залить ZIP:
   ```bash
   adb sideload lineage-21.0-begonia-signed.zip
   adb sideload gapps-...zip        # если нужен Google
   ```
4. Перезагрузка → первый запуск долгий (5–10 мин, оптимизация приложений).

## Шаг 6. EDL-прошивка (если телефон «окирпичился»)

Нужен когда телефон не входит ни в fastboot, ни в recovery. Использует Qualcomm HS-USB QDLoader 9008.

```bash
# проверить, что телефон в EDL
lsusb | grep -i "05c6:9008"

# MiFlash (Windows) — выбрать rawprogram0.xml + patch0.xml из прошивки
# Или edl.py (Linux):
git clone https://github.com/bkerler/edl.git
cd edl
python3 edl.py --loader=prog_emmc_firehose_*.elf w boot boot.img
# или
python3 edl.py w all                # прошить всё (ОСТОРОЖНО)
```

**Драйверы для EDL на Windows:** `Qualcomm USB Driver V1.0` или `QPST`. На Linux — `edl.py` сам подхватывает.

## Частые проблемы

### «fastboot devices» пусто
- Проверить, что телефон действительно в fastboot: на экране должен быть заяц/кролик MI. Если чёрный экран с надписью «Fastboot» — это он
- WSL2: запустить `./attach-phone.sh`
- Кабель: должен быть data-кабель
- Попробовать `fastboot -i 0x2717 devices` (явно сказать vid)

### «adb unauthorized»
1. На телефоне: Настройки → Для разработчиков → **Отозвать все разрешения**
2. Выкл/вкл отладку
3. Переткнуть кабель
4. На экране телефона должен появиться диалог «Разрешить отладку»

### «FAILED (remote: 'Bootloader is locked')»
Загрузчик заблокирован. Нужно сначала сделать `fastboot oem unlock` (потеря всех данных).

### «FAILED (remote: 'Not allowed to flash')»
`fastboot flashing unlock_critical` (только для critical-разделов вроде bootloader) — обычно не нужно.

### MiFlash: «error: Flash aboot error» / «Sparse file is invalid»
Качаете прошивку под **свой регион** (CN / Global / EEA / RU) и **свой codename**. Узнать codename:
```bash
adb shell getprop ro.product.device        # напр. "begonia" для Redmi Note 8 Pro
adb shell getprop ro.product.model         # напр. "Redmi Note 8 Pro"
```

### Телефон не входит ни в один режим (bootloop, чёрный экран)
1. Подержать питание 30 сек — должен перезагрузиться
2. Подключить к ПК, держать `Vol−` → если появился заяц — fastboot, прошивайте
3. Если чёрный — test-point для EDL
4. Сервисный центр Xiaomi (платно, но без потери гарантии)

### После прошивки: «This device is corrupted and cannot be trusted»
Включите «OEM unlocking» обратно в Для разработчиков. Или:
```bash
adb shell settings put global device_provisioned 1
adb reboot
```

## Полезные ссылки

- Стоковые прошивки: https://xiaomiui.net/stock-rom/
- TWRP: https://twrp.me/xiaomi/
- LineageOS: https://wiki.lineageos.org/devices/begonia
- EDL tool: https://github.com/bkerler/edl
- Codenames: https://github.com/XiaomiFirmwareUpdater/xiaomi-mtk-imei-codes
- USBipd: https://github.com/dorssel/usbipd-win

## Артефакты проекта (в этом репо)

- `debug.sh` — собрать APK, установить, запустить, logcat
- `attach-phone.sh` — проброс USB в WSL2 (через usbipd alias)
- `attach-phone.ps1` — то же, но для Windows PowerShell
- `build_and_send.sh` — собрать и отправить APK в Telegram

## Связанные скиллы / память

- `~/.claude/projects/.../memory/wsl2-xiaomi-adb-pitfalls.md` — типичные грабли WSL2 + adb
- `~/.claude/projects/.../memory/android-test-project.md` — структура android-test проекта
