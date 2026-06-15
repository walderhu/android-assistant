Be extremely concise.

Output only:
- commands
- final result

Do not explain reasoning.
Do not narrate actions.
Do not summarize unless asked.
Do not write plans unless asked.

If code changed:
- return minimal unified diff only

Keep responses under 80 tokens unless explicitly requested.

Git rules:
- never commit without explicit approval in the current turn
- never push without explicit approval in the current turn
- do not touch unrelated files or local secrets

И ГОВОРИ ПО РУССКИ
буть максимально эконопмным я плачу за каждый твой лишний токен 


# Token Efficiency

Be extremely concise.

- Do not explain changes unless explicitly asked.
- Do not print diffs.
- Do not print file contents.
- Do not summarize code changes.
- Do not describe implementation details after completion.
- Reply with at most 1-3 short sentences.
- Use "Done.", "Fixed.", "Implemented." when sufficient.
- Do not create plans unless requested.
- Do not restate the task.
- Do not provide progress updates for simple tasks.
- Do not list files that were modified.
- Do not show command output unless it contains an error.
- Never paste logs, stack traces, or test output unless requested.
- Read the minimum amount of code required.
- Prefer targeted searches over reading entire files.
- Avoid re-reading files already inspected.
- When editing, modify only the necessary lines.
  
  
  
  по завершении работы пиши закончил пожалуйста
  
  если ты чтото не можешь правда, просто скажи не надо бесконечно галлюцинировать мучать меня и себя, и мои деньги 
  просто скажи напрямую что не можешь это сделать потому потому
  я не растроюсь все норм 
  не пиши мне что ты меняешь, считай что все изменения я могу посмотреть сам через git diff, максимально бережно относись к out токенам своим, они мне очень дорого обходятся 
  


  # WALDERHU Android Project

## Context

Native Android application.

Main stack:

* Kotlin
* Android Views (XML)
* RecyclerView
* SQLite / local storage
* OpenRouter API

## Token efficiency rules

Never read the entire repository.

Never run:

```bash
cat **/*.kt
cat **/*.xml
find . -type f
tree -a
```

Use ripgrep first:

```bash
rg "symbol_name"
rg "class SomeClass"
rg "layout_name"
```

Open only files directly related to the task.

Maximum initial context:

* 3 Kotlin files
* 2 XML files

Read additional files only if required.

## Ignore generated code

Never inspect:

* build/
* app/build/
* .gradle/
* .idea/
* generated/
* intermediates/
* outputs/

These directories contain generated artifacts and are never useful for feature work.

## Project areas

### Nutrition

Files:

* NutritionController.kt
* NutritionViewModel.kt
* NutritionDatabase.kt
* MacroBarView.kt
* MacroGaugeView.kt
* CalorieRingView.kt

Layouts:

* activity_main.xml
* item_meal.xml

Drawables:

* dish_*.xml
* meal_card_bg.xml

### Chat

Files:

* ChatAdapter.kt
* MessageAdapter.kt
* ChatRepository.kt
* OpenRouterClient.kt
* VoiceRecorder.kt
* TranscriptionClient.kt

Layouts:

* item_chat.xml
* item_message.xml

### Settings

Files:

* Settings.kt
* SettingsActivity.kt

Layouts:

* activity_settings.xml

### Photos

Files:

* PortraitCaptureActivity.kt
* PhotoCache.kt
* PreviewPagerAdapter.kt
* AttachAdapter.kt

## Modification policy

Make minimal changes.

Do not refactor unrelated code.

Do not rename classes unless explicitly requested.

Do not rewrite architecture.

Return only modified files and reasoning.
