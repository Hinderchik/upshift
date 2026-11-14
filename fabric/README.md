# Upshift

Render less, see more. Upshift renders the world at a lower internal resolution, then
resolves it back with a real upscaler (FSR 1 EASU/RCAS, NVIDIA Image Scaling, or the
built-in temporal accumulate), and can present extra interpolated frames on any GPU at
2x, 3x or 4x.

Client-side Fabric mod. The settings page lives inside Sodium's video settings
("FSR + DLSS") when Sodium is installed; without Sodium the renderer still works.

## Требования

| Компонент | Версия |
|-----------|--------|
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.19.5+ |
| Fabric Loom | 1.17.21 |
| Gradle | 9.5.1 (wrapper в комплекте) |
| Sodium | 0.9.2+mc26.2 (опционально, для страницы настроек) |

## Сборка

```powershell
.\gradlew.bat build
```

Готовый мод: `build/libs/upshift-1.6.5.jar`.
Исходники: `build/libs/upshift-1.6.5-sources.jar`.

Запуск клиента для отладки:

```powershell
.\gradlew.bat runClient
```

## Структура

```
src/main/java/dev/upshift/
  Upshift.java              константы мода, логгер
  UpshiftClient.java        client entrypoint
  config/                   FsrConfig, Quality, QualityMode, Upscaler, FrameGen
  render/                   UpscaleManager, Fsr1Pass, FastUpscalePass,
                            NisPass, TaauPass, FrameGenPresenter
  gpu/                      Capabilities, CapabilityReport, GpuProfile,
                            Backend, NativeLibraries
  perf/                     CpuTuner, PerfProfile
  overlay/                  PerfStats, PerformanceOverlay, HardwareMonitor, OverlayMode
  mixin/                    GameRendererMixin, MinecraftMixin,
                            RenderPipelinesMixin,
                            HudMixin, SkyRendererMixin,
                            EntityCullMixin, ParticleCullMixin,
                            BlockEntityCullMixin, TextureAtlasMixin,
                            TextCacheMixin, FontWidthMixin,
                            MobAiThrottleMixin
  optim/                    ClientOptimizations, TextCache, AiThrottle
  sodium/                   SodiumConfigEntry (Sodium config API)

src/main/resources/
  fabric.mod.json
  upshift.mixins.json
  assets/upshift/
    icon.png
    lang/en_us.json
    shaders/core/*.fsh|*.vsh       EASU/RCAS/NIS/TAAU шейдеры
    nis/coef_scaler.bin            таблицы коэффициентов NIS
    nis/coef_usm.bin
    textures/gui/icon.png
```

## Генерация кадров

Встроенный генератор живёт на странице «Frame generation» и не требует нативных
библиотек: он работает и на OpenGL, и на Vulkan.

| Опция | По умолчанию | Что делает |
|---|---|---|
| Frame generator | выкл. | `Built-in` включает встроенный генератор. Вендорские варианты (FSR-FG, DLSS-FG, XeSS-FG) требуют свой runtime. |
| Frame generation multiplier | 2x | Сколько изображений получает дисплей на каждый отрисованный кадр: 1x — только настоящий кадр, 2x — один дополнительный, 3x — два, 4x — три. |
| Force exact multiplier | выкл. | Отключает адаптивное ограничение и всегда показывает выбранное число кадров. |

Дополнительный кадр — это последнее готовое изображение (вместе с HUD),
повёрнутое на предсказанный угол камеры. Поворот камеры в clip-space является
гомографией, поэтому хватает одного чтения текстуры: буфер глубины не нужен, а
изображение не рассыпается на краях. Предсказание берётся из двух предыдущих
поз камеры (`current * slerp(identity, previous⁻¹ * current, fraction)`), а само
изображение выдаётся через обычную поверхность окна — тем же путём, что и
настоящий кадр, на обоих бэкендах.

По умолчанию количество дополнительных кадров адаптивное: генератор вычитает
время, потраченное на их показ, из измеренного интервала между настоящими
кадрами и добавляет столько, сколько ещё помещается в частоту обновления. Если
дисплей уже заполнен настоящими кадрами, лишние не показываются. «Force exact
multiplier» снимает это ограничение.

Генератор не работает при открытом экране, на паузе и в свёрнутом окне; при
слишком маленьком или слишком большом повороте камеры (телепорт) кадр
пропускается вместо смазывания. Если показ дополнительного кадра всё же
сорвался, поверхность освобождается, а генератор ставится на паузу и через
несколько секунд пробует снова — игра тем временем продолжает рисовать обычные
кадры.

## Оптимизации

Настройки живут на отдельной странице Sodium («Optimizations»).

### Этап 1 — culling и частицы (готово)

| Опция | По умолчанию | Что делает |
|---|---|---|
| Entity distance | 128 блоков | `EntityRenderDispatcher.shouldRender` отбрасывает сущность до извлечения render-state. 0 = ванильное поведение. |
| Hide invisible mobs | выкл. | Пропускает невидимых не-игровых мобов (броня и предметы невидимых игроков всё равно рисуются). |
| Limit particles | вкл. | Бюджет создания частиц на тик. |
| Particle distance | 32 блока | Совпадает с ванильным лимитом для обычных спавнов; можно снизить. |
| Particle budget | 1500 / тик | При исчерпании новые частицы не создаются до следующего тика. |

Миксины: `EntityCullMixin`, `ParticleCullMixin`. Оба используют `@Inject` с
`cancellable`, без `@Overwrite`, поэтому совместимы с другими модами.

### Этап 2 — block entities, анимации и аллокации (готово)

| Опция | По умолчанию | Что делает |
|---|---|---|
| Block entity distance | 64 блока | `BlockEntityRenderDispatcher.tryExtractRenderState` возвращает `null` до создания render-state. 0 = ванильные лимиты (у сундуков 64, у маяков и порталов 256). |
| Freeze animations when unfocused | вкл. | `TextureAtlas.tick` не выполняется, пока окно не в фокусе: анимации всё равно не видны. |
| Animation update rate | Every frame | «Every 2 frames» вдвое снижает стоимость анимаций текстур ценой чуть более медленной лавы/огня. |

Проверка дистанции для block entities использует позицию камеры, которую
ванильный `prepare` и так передаёт раз в кадр, и целочисленные координаты
`BlockPos`: `Vec3.atCenterOf` в горячем цикле больше не создаётся.

Миксины этапа 2: `BlockEntityCullMixin`, `TextureAtlasMixin` — тоже `@Inject`,
без `@Overwrite`.

### Этап 3 — текст и HUD (готово)

| Опция | По умолчанию | Что делает |
|---|---|---|
| Cache text layout | вкл. | Повторяющиеся строки HUD раскладываются один раз: `Language.getVisualOrder` не перебирает кодовые точки каждый кадр. Кэш ограничен 4096 записями и сбрасывается при смене языка. |

Дополнительно кэшируется `Font.width(String)` на экземпляре шрифта — центрирование
и тултипы больше не проходят по всем глифам каждый кадр. Перезагрузка ресурсов
создаёт новый `Font`, и кэш исчезает вместе с ним.

Миксины этапа 3: `TextCacheMixin` (через `@WrapOperation` из Mixin Extras,
поэтому другие моды могут обернуть тот же вызов), `FontWidthMixin`.

### Этап 4 — AI интегрированного сервера (готово)

| Опция | По умолчанию | Что делает |
|---|---|---|
| Throttle distant mob AI | вкл. | Мобы дальше заданной дистанции от всех игроков пропускают `Sensing.tick` и полный перебор целей; вместо него выполняется дешёвый проход по уже запущенным целям. |
| AI distance | 48 блоков | Порог: если хотя бы один игрок ближе, моб тикает полностью. 0 отключает троттлинг. |
| AI update rate | каждые 4 тика | Как часто дальний моб получает полный AI-тик. Навигация, движение, взгляд и прыжки тикают каждый тик, поэтому мобы не замирают. |

Не троттлятся: мобы с `isPersistenceRequired`, именованные мобы, пассажиры и
транспорт, Дракон Края и Иссушитель. Фаза полного тика совпадает с той, что
ваниль и так отводит под обновление целей, поэтому чётный интервал всегда
попадает в полный тик. Решение считается один раз за игровой тик и кэшируется
в самом мобе; проверка дистанции идёт по списку игроков уровня и примитивной
математике, без `Vec3`.

Миксин этапа 4: `MobAiThrottleMixin` — `@WrapOperation` на `Sensing.tick` и
`GoalSelector.tick` внутри `Mob.serverAiStep`, без `@Overwrite`.

Кэш рецептов для печей не добавлен: в 26.2 ваниль уже использует
`RecipeManager.createCheck(...)` / `CachedCheck` в
`AbstractFurnaceBlockEntity.quickCheck`, так что отдельный миксин ничего бы не
дал.

### Дальше по этапам

5. **Профилирование**: счётчики culled-сущностей/частиц в оверлее, замер выигрыша.

Серверные оптимизации (AI, рецепты) в клиентском моде влияют только на
встроенный сервер — на выделенном сервере мод не установлен.

## Публикация на CurseForge

Проект: [curseforge.com/minecraft/mc-mods/upshift](https://www.curseforge.com/minecraft/mc-mods/upshift) (ID `1759097`).

Токен не хранится в репозитории. Задайте его один раз в окружении:

```powershell
$env:CF_API_TOKEN = "<upload token из authors.curseforge.com>"
.\scripts\publish.ps1
```

Скрипт читает `mod_version` из `gradle.properties`, собирает мод, берёт
changelog из `CHANGELOG.md`, и загружает JAR через `/upload-file`.
Версию, тип релиза и ID проекта можно переопределить параметрами.

## Зависимости

- `sodium-fabric-0.9.2+mc26.2.jar` в корне проекта подключён как `modCompileOnly`
  (нужен только для компиляции `SodiumConfigEntry`, в JAR мода не попадает).
- JNA (`net.java.dev.jna:jna:5.14.0`) — для NVML-мониторинга GPU.
- Нативные библиотеки (`upshift_core`, `nvngx`, `xess`, `ffx`) опциональны:
  без них работают FSR 1, NIS и встроенный temporal accumulate.

## Маппинги

Проект использует Yarn `1.21.4+build.1:v2`, так как официальных маппингов для
26.2 в публичных репозиториях нет. Loom предупреждает, что маппинги собраны для
другой версии — это ожидаемо.

## Лицензия

MIT, см. `LICENSE`.
