---
name: parallel-helper
description: Промпт-словарь для параллельной "глупой" модели, которая правит код, но не умеет шить. Фиксирует какие файлы трогать, какие НЕ трогать, и команду сборки/установки.
---

# Параллельный помощник — контекст для "глупой" модели

## Что ты делаешь
Правишь код в `/home/tru60/workspace/test/android-test/`.
Прошивку/установку делает основная модель (`./local_stats3.0.sh`).
Ты **не запускаешь** сборку и **не пушишь** в git.

## Что ТРОГАТЬ можно
Только эти файлы (90% задач):
- `app/src/main/java/com/assistant/app/NutritionController.kt` — главный файл UI
- `app/src/main/java/com/assistant/app/NutritionDatabase.kt` — SQLite схема
- `app/src/main/java/com/assistant/app/MainActivity.kt` — точка входа
- `app/src/main/res/layout/*.xml` — layouts
- `app/src/main/res/drawable/*.xml`, `*.png` — иконки/фоны

Перед правкой: `rg "имя_функции"` или `rg "id/что_ищу"` вместо `cat`/`find`.

## Что НЕ ТРОГАТЬ
- `build/`, `app/build/`, `.gradle/`, `out/` — артефакты
- `local.properties`, `*.keystore`, `*.jks` — секреты
- `gradle/wrapper/`, `gradlew*` — обёртка
- `.claude/`, `AGENT.md`, `CLAUDE.md` — мета

## Правила правки
1. Минимум изменений. Не рефакторь "заодно".
2. Не переименовывай классы/методы без явной просьбы.
3. После правки — НИЧЕГО не коммить, не пушь, не билди.
4. Сообщи пользователю список изменённых файлов и кратко что сделал (1-2 строки на файл).

## Сборка и прошивка (это делаю НЕ я)
```bash
cd /home/tru60/workspace/test/android-test
./local_stats3.0.sh              # билд + установка + запуск
./local_stats3.0.sh --no-build   # только переустановка
./local_stats3.0.sh --push "msg" # билд + commit + push
./local_stats3.0.sh --logs       # logcat после запуска
```

Если модель не может что-то — прямо скажи "не могу", не выдумывай.

## Структура (быстро найти что менять)

| Хочу изменить                | Файл                                                |
|------------------------------|-----------------------------------------------------|
| UI в карточке продукта       | `NutritionController.kt` → `showProductView()`      |
| Список "Продукты/Блюда"      | `NutritionController.kt` → `renderProductsTab()`    |
| Зелёный FAB "+" (снизу-справа) | `app/src/main/res/layout/activity_main.xml` → `fabCreate` |
| Цвета/тема                   | `app/src/main/res/values/colors.xml`                |
| БЖУ/ккал логика              | `NutritionController.kt` → `dishMacrosPer100`       |
| База SQLite (схема, миграция) | `NutritionDatabase.kt` → `onCreate`/`onUpgrade`    |
| Ассеты (иконки)              | `app/src/main/res/drawable/`                        |

## Типичные баги
- ImageView с PNG без `layoutParams` → растягивается на весь экран. Всегда задавай `LinearLayout.LayoutParams(w*d, h*d)`.
- Новая колонка в SQLite: bump `VERSION` + `ALTER TABLE` в `onUpgrade`, иначе крашнет на старых БД.
- `popup_*` всегда через `PopupWindow` с inflate, не через `AlertDialog.setView`.

## Конец работы
Скажи пользователю: "закончил пожалуйста" + список файлов.
