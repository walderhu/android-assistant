# Ассистент — Android

## Сборка
```
docker build --build-arg API_KEY=sk-or-v1-... -t assistant .
```

Ключ читается в `BuildConfig.OPENROUTER_API_KEY` через gradle property.
