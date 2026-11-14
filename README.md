# Upshift

Render less, see more. Upshift рисует мир в пониженном внутреннем разрешении,
поднимает его настоящими апскейлерами (FSR 1 EASU/RCAS, NVIDIA Image Scaling или
встроенный временной накопитель TAAU) и может показывать дополнительные
интерполированные кадры на любой видеокарте — 2x, 3x или 4x.

Работает на **Fabric** и **NeoForge** для Minecraft 26.2. Настройки живут внутри
страницы видеонастроек Sodium (мод **опционален**, но именно через него появляется UI).

## Структура репозитория

| Папка | Загрузчик | Содержимое |
|-------|-----------|------------|
| `fabric/` | Fabric Loader 0.19.5+ | Основной мод + VR Edition аддон (`upshift-vr-edition/`) |
| `neoforge/` | NeoForge 26.2.0.93+ | Порт основного мода + VR Edition аддон (`upshift-vr-edition/`) |
| `CHANGELOG.md` | — | История изменений для обеих версий |

## Сборка

```powershell
# Fabric (основной мод)
cd fabric
.\gradlew.bat build

# Fabric (VR Edition аддон)
cd fabric\upshift-vr-edition
.\gradlew.bat build

# NeoForge (основной мод)
cd ..\..\neoforge
.\gradlew.bat build

# NeoForge (VR Edition аддон)
cd upshift-vr-edition
.\gradlew.bat build
```

Готовые JAR появляются в `build/libs/`. Для отладки:

```powershell
.\gradlew.bat runClient   # работает внутри нужной папки
```

## Требования

| Компонент | Версия |
|-----------|--------|
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.19.5+ |
| NeoForge | 26.2.0.93+ |
| Gradle | 9.5.1 (wrapper в комплекте) |
| Sodium | 0.9.2+mc26.2 — опционально, нужен только для страницы настроек |

Без Sodium рендерер продолжает работать со значениями по умолчанию; страница
настроек просто не показывается.

## Что внутри

### Апскейлинг
| Апскейлер | Статус | Примечание |
|---|---|---|
| FSR 1 (EASU + RCAS) | ✅ Работает | AMD MIT kernels, реализованы во фрагмент-шейдерах |
| NIS (NVIDIA Image Scaling) | ✅ Работает | MIT шейдер + таблицы коэффициентов |
| TAAU (Temporal Accumulate) | ✅ Работает | Встроенный, джиттер проекции, ротация репроекта |
| DLSS / FSR FG / XeSS FG | ❌ Зависит от нативного runtime | Появятся при наличии вендорской библиотеки |

### Генерация кадров
Встроенный генератор живёт в Sodium и не требует нативных библиотек:
работает на OpenGL и Vulkan. Кадр = предыдущее изображение, повёрнутое на
предсказанный угол камеры. Поворот в clip-space — гомография, достаточно одного
чтения текстуры, буфер глубины не нужен.

### Оптимизации (страница «Optimizations» в Sodium)
- Culling сущностей и частиц по дистанции
- Кэширование текстового лейаута HUD
- Пропуск AI дальних мобов
- Заморозка анимаций текстур вне фокуса

## Публикация

Проекты на CurseForge:

- **Upshift** (базовый мод): https://www.curseforge.com/minecraft/mc-mods/upshift (ID `1759097`)
- **Upshift VR Edition**: https://www.curseforge.com/minecraft/mc-mods/upshift-vr-edition (ID `1797892`)

Токен задаётся в окружении один раз:

```powershell
$env:CF_API_TOKEN = "<token>"
```

Заливка:
```powershell
# Основной мод (Fabric / NeoForge)
cd fabric       ; .\scripts\publish.ps1
cd ..\..\neoforge ; .\scripts\publish.ps1

# VR Edition (NeoForge)
cd neoforge\upshift-vr-edition ; .\scripts\publish.ps1
```

Подробнее в [CURSEFORGE_SUMMARY.txt](CURSEFORGE_SUMMARY.txt).

## Лицензия

MIT — см. [LICENSE](LICENSE).
