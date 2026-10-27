# Treadmill Control — состояние проекта

Android-приложение на Kotlin + Compose для управления беговой дорожкой
через Bluetooth Low Energy (FTMS). Совместимо с любой дорожкой, поддерживающей
стандарт FTMS (Fitness Machine Service, Bluetooth SIG): FitShow, Horizon,
Bowflex, Sole, NordicTrack и т.д.

Поддержка нагрудного пульсометра, авторегулировка скорости по пульсу,
голосовые подсказки, профили пользователей, генератор тренировок,
автоопределение возможностей железа, фоновая работа (Foreground Service),
резервное копирование данных.

Работает полностью офлайн — разрешение INTERNET намеренно не запрашивается.

---

## Стек

- Язык: Kotlin 1.9.22, JVM target 17
- UI: Jetpack Compose (BOM 2024.02.00, Material 3 БЕЗ MenuAnchorType)
- Архитектура: MVVM + StateFlow + `TreadmillSession` (ядро) + Foreground Service
- База: Room 2.6.1 (SQLite), версия БД **2** (миграция 1→2: колонка `hrSamples` в `workout_logs`)
- Сериализация: kotlinx.serialization 1.6.3
- BLE: android.bluetooth.le + собственные обёртки
- TTS: android.speech.tts (системный движок, язык из системы)
- Локализация: `values/strings.xml` (EN) + `values-ru/strings.xml` (RU)
- Сборка: Gradle 8.6, AGP 8.2.2, KSP 1.9.22-1.0.17
- SDK: min 21, target/compile 34
- CI/CD: GitHub Actions (автоматический versionCode)

---

## Структура проекта

    app/src/main/java/com/custom/treadmill/
    ├── TreadmillApp.kt              — Application, ручной DI (включая TtsManager)
    ├── MainActivity.kt              — точка входа, тема из настроек, перехват тапов/свайпов
    ├── DebugLog.kt                  — единый хаб логирования (memory + file)
    │
    ├── ble/
    │   ├── Uuids.kt                 — UUID сервисов и характеристик (FTMS + HR)
    │   ├── BleManager.kt            — сканирование BLE + кулдаун сканирования + защита от ALREADY_STARTED
    │   ├── BleConnection.kt         — обёртка BluetoothGatt (writeSuspend, корректный disconnect)
    │   ├── ITreadmillProtocol.kt    — интерфейс + TreadmillData + capabilities
    │   ├── TreadmillCapabilities.kt — парсер FTMS Feature / Speed/Incline Range
    │   ├── FTMSProtocol.kt          — стандартный FTMS (читает возможности)
    │   ├── TreadmillSession.kt      — ЯДРО: связь, AutoHR, TTS, метрики, реконнект, локализация
    │   └── HeartRateService.kt      — чтение пульса (0x180D)
    │
    ├── data/
    │   ├── database/
    │   │   ├── AppDatabase.kt       — Room, версия 2, MIGRATION_1_2, closeAndReset()
    │   │   ├── ProgramDao.kt        — DAO для программ и логов
    │   │   ├── ProgramEntity.kt     — ProgramEntity + ProgramData + ProgramSegment + WorkoutLogEntity
    │   │   └── HrSamplesCodec.kt    — компактное кодирование кривой пульса
    │   └── repository/
    │       ├── ProgramRepository.kt — CRUD, JSON, демо
    │       └── SettingsStore.kt     — SharedPreferences + JSON + профили + TTS + миграция
    │
    ├── logic/
    │   ├── WorkoutManager.kt        — программа + пауза/пропуск/onFinish (один copy() при смене)
    │   ├── HeartRateController.kt   — авторегулировка (AutoHR)
    │   ├── HeartRateZoneCalculator.kt — расчет пульсовых зон (Karvonen)
    │   ├── ProgramGenerator.kt      — генератор тренировок (волшебная палочка)
    │   ├── TtsManager.kt            — обёртка TextToSpeech
    │   └── BackupManager.kt         — экспорт/импорт ZIP (программы, журнал, настройки)
    │
    ├── service/
    │   └── TreadmillService.kt      — Foreground Service (уведомление в шторке с кнопками)
    │
    └── ui/
        ├── Theme.kt, AppNavHost.kt, Permissions.kt
        ├── components/
        │   ├── HeartRateChart.kt    — чистый Canvas, зоны, градиент, даты
        │   ├── HrSparkline.kt       — мини-график пульса для карточки журнала
        │   ├── ZoneLegend.kt        — компактная легенда зон Z1-Z5
        │   ├── CapabilitiesDialog.kt — диалог возможностей дорожки
        │   └── RollingText.kt
        ├── screens/
        │   ├── MainScreen.kt        — главный, HoldToConfirmButton, SegmentLine
        │   ├── WorkoutScreen.kt, SettingsScreen.kt
        │   ├── ProgramsScreen.kt    — списки, редактор, шаблоны, генератор, импорт/экспорт
        │   ├── HistoryScreen.kt     — список, статистика, Info-кнопка
        │   ├── DebugScreen.kt, AboutScreen.kt
        │   ├── ProfilesScreen.kt    — CRUD профилей спортсмена
        │   └── TtsSettingsScreen.kt — редактор TTS-фраз
        └── viewmodels/MainViewModel.kt, ProgramViewModel.kt

    app/src/main/res/
    ├── values/strings.xml           — английские строки (дефолт)
    └── values-ru/strings.xml        — русские строки

---

## BLE — детали

### UUID (Uuids.kt)

- FTMS Service: 0x1826
- FTMS Feature: 0x2ACC (read)
- FTMS Treadmill Data: 0x2ACD (notify)
- FTMS Supported Speed Range: 0x2AD4 (read)
- FTMS Supported Incline Range: 0x2AD5 (read)
- FTMS Control Point: 0x2AD9 (write, indicate)
- FTMS Control Point Alt: 0x2A66
- HR Service: 0x180D, Measurement: 0x2A37

### Протокол belt'а FS-E629DD (проверено на железе)

Команды:

- `00` — Request Control. Belt молча принимает.
- `07` — Start / resume.
- `02 XX XX` — Speed, км/ч × 100 LE.
- `03 XX XX` — Incline, % × 10 LE.
- `08 01` — **Stop / Pause.** Останавливает мотор И сбрасывает таймер.

### Критичные особенности протокола

1. **Правильный старт (Wake-up):** `00`, пауза 200 мс, `07`. Сразу после этого
   нужно отправить `02 XX XX` (скорость), чтобы "разбудить" дорожку.
   Если belt в глубоком сне — только физическая кнопка.
2. **Смена скорости:** только `02 XX XX`.
3. **Остановка:** `08 01`. Старый `02 00 00` останавливал мотор, но не таймер.
4. **Sleep mode:** belt подключён по BLE, но мотор не реагирует. Только
   физическая кнопка.
5. `parseTreadmillData`: если флаг incline отсутствует → incline = 0.0.
6. **Особенность BLE-чипа FitShow**: иногда «залипает» в состоянии «подключён»
   на 30-60 сек после отключения телефона. Приложение при этом перестаёт
   видеть дорожку в сканере. Лечится только выключением/включением питания
   дорожки. Не баг приложения. **Частично лечится** корректным `disconnect → wait callback → refresh() → close()`.

---

## Логика работы (TreadmillSession)

Вся BLE-логика вынесена из ViewModel в `TreadmillSession`.

- **Автопереподключение**: если связь теряется, 10 попыток с интервалом 3 сек
  к MAC-адресу. Отменяется при ручном подключении/отключении (`autoReconnectJob`).
- **AutoHR (автопульс)**: цикл каждые `intervalSec` секунд. Если `hrEnabled`
  и пульс выше верхней границы → `-stepKmh`. Если ниже нижней → `+stepKmh`.
  НЕ работает во время программы. Учитывает наклон, вес, ИМТ.
- **TTS**: озвучка старта, смены сегмента, паузы/продолжения, финиша.
  Шаблоны фраз редактируются в настройках. Словоформы через `plurals` в ресурсах.
- **Метрики**: `uiElapsedSec`, `uiDistanceKm`, `uiCalories` (формула MET).
  Вес из активного профиля.
- **Capabilities**: после подключения читает FTMS Feature / Speed Range /
  Incline Range. Если наклон не поддерживается — UI скрывает слайдеры.
- **Foreground Service**: `TreadmillService` держит процесс живым и показывает
  плашку в шторке (Сегмент · Скорость · Пульс · Время · Пауза). Кнопки действий.
- **WorkoutManager**: управляет программой. При пропуске сегмента время не
  накручивается. При окончании — останавливает belt и сохраняет лог.

### Важные защитные механизмы

- `startJob` — фоновый запуск belt. Отменяется при Stop/Pause, чтобы не
  спамить командами `00+07+02` после того, как пользователь уже нажал Стоп.
- `autoReconnectJob` — цикл реконнекта. Отменяется при ручном подключении,
  чтобы не «дрались» два параллельных BLE-подключения.
- **Кулдаун сканирования** (`BleManager.setScanCooldown(ms)`) — после ручного
  отключения на 5 сек блокирует повторное сканирование. Защищает от «залипания»
  BLE-чипа дорожки и гонки GATT-подключений.
- **`hasBeenReady`** в stateJob — предотвращает ложный `triggerAutoReconnect` на
  первой эмиссии StateFlow(DISCONNECTED). Иначе создавался «сирота» BleConnection
  с открытым GATT.
- **`isDisconnecting`** в `BleConnection` — защита от повторного `disconnect()`.

### Обработка потери связи (v2.3)

При `DISCONNECTED` во время активной тренировки/ручного режима:

1. Программа или ручной режим **ставится на паузу** (время/дистанция не накручиваются).
2. Запускается цикл автопереподключения (10 попыток × 3 сек).
3. После успешного реконнекта — сообщение «Связь восстановлена. Тренировка на
   паузе — нажмите «Продолжить»». Пользователь сам решает, продолжать ли.

### Кривая пульса (v2.3)

- В `WorkoutLogEntity` добавлено поле `hrSamples: String = ""`.
- Формат: `"0:120,5:125,10:130"` (offsetSec : bpm, через запятую).
- Кодирование/декодирование — `HrSamplesCodec`.
- Прореживание при сохранении: 1 точка в 5 секунд.
- Используется в `HistoryScreen`:
  - для точного расчёта пульсовых зон (`computeZoneDurations`);
  - для отрисовки sparkline (`HrSparkline`).
- Для старых записей (без `hrSamples`) — fallback на гауссову аппроксимацию
  с пометкой «≈ приближённо».

---

## Профили пользователей

`AppSettings.profiles: List<UserProfile>` + `activeProfileId`.

Поля `UserProfile`:

- `id`, `name` (по умолчанию `""` — при отображении подставляется placeholder)
- `weightKg`, `heightCm`, `age`, `isMale`
- `restingHr` — пульс покоя
- `level` — BEGINNER / INTERMEDIATE / ADVANCED

Профили используются:

- в расчёте калорий (вес),
- в пульсовых зонах (возраст, пол, restingHr),
- в генераторе тренировок (ИМТ, уровень, пол).

Экран: `ui/screens/ProfilesScreen.kt`, маршрут `Routes.PROFILES`.

---

## Голосовые подсказки (TTS)

`logic/TtsManager.kt` — обёртка `TextToSpeech`, язык из системы.

Шаблоны фраз в `AppSettings` (по умолчанию `""` — заполняются из ресурсов в `SettingsStore.load()`):

- `ttsStartTemplate`
- `ttsSegmentTemplate`
- `ttsFinishTemplate`
- `ttsPauseText`
- `ttsResumeText`

Доступные теги в шаблонах:

- `{name}` — имя программы
- `{segName}` — имя сегмента
- `{i}` / `{n}` — номер сегмента / всего сегментов
- `{speed}` (цифра) / `{speed_text}` (словами)
- `{angle}` / `{angle_text}` — наклон
- `{dist}` — дистанция (км)
- `{hr}` — средний пульс

Словоформы (`{speed_text}`, `{angle_text}`) — через `plurals` в ресурсах
(`tts_kmh_plural`, `tts_percent_plural`). На русском — «1 километр», «2 километра»,
«8 километров». На английском — «1 kilometer», «2 kilometers».

Экран: `ui/screens/TtsSettingsScreen.kt`, маршрут `Routes.TTS`.
Кнопка «Прослушать» под каждым полем.
Кнопка «Сбросить» — ставит значения из текущего языка системы.

---

## Генератор тренировок (волшебная палочка)

`logic/ProgramGenerator.kt` — генерирует программу по параметрам профиля.

Параметры:

- Цель: WEIGHT_LOSS / CARDIO / SPEED
- Длительность: 20-60 мин
- Интенсивность: 70-130%

Учитывает:

- ИМТ (штраф на скорость для высокого веса, защита коленей)
- Уровень подготовки (новичок/средний/профи → базовые скорости +30-40%)
- Плавное масштабирование наклона от уровня (новичок 4%, профи 12%)
- Реальные лимиты железа (min/max скорость и наклон из Capabilities)

Кнопка `AutoFixHigh` в шапке `ProgramsScreen`.

**Локализация (v2.3.1):** названия программ и сегментов берутся из
`strings.xml` через ключи `gen_name_*`, `gen_desc_*`, `gen_seg_*`,
`gen_level_*`. Генератор получает `Context`, а не `stringResource`
(он Composable-only).

---

## Возможности дорожки (Capabilities)

`ble/TreadmillCapabilities.kt` — парсер трёх характеристик FTMS:

- `FTMS Feature` (0x2ACC) — битовая маска поддерживаемых функций
- `Speed Range` (0x2AD4) — min/max/increment скорость
- `Incline Range` (0x2AD5) — min/max/increment наклон

`ui/components/CapabilitiesDialog.kt` — диалог, открывается **только по кнопке ℹ️**
рядом с именем дорожки (авто-показ при подключении убран — раздражал).

Если дорожка не поддерживает наклон — все слайдеры наклона скрываются
(MainScreen, WorkoutScreen, ProgramEditor). Если Speed Range уже, чем
настройки пользователя — используются меньшие значения.

---

## Настройки (AppSettings)

- `themeMode`: SYSTEM / LIGHT / DARK
- `keepScreenOn`: не гасить экран во время тренировки
- **TTS**: `ttsEnabled` + 5 шаблонов фраз (дефолты из ресурсов)
- **Профили**: `profiles: List<UserProfile>`, `activeProfileId`
- **Скорость**: minSpeedKmh / maxSpeedKmh (1.0 / 20.0), speedStepKmh
- **Наклон**: maxInclinePercent (15.0)
- **Автопульс**: `hrEnabled` / `hrMode` / `targetHr` / `zoneMin` / `zoneMax` /
  `stepKmh` / `intervalSec` / `thresholdHigh` / `thresholdLow`
- **Max HR**: `autoMaxHr` (Tanaka/Gulati) / `maxHr` (ручной)

Настройки хранятся в `SharedPreferences` под именем `treadmill_settings`
(ключ `settings_json`, значение — сериализованный `AppSettings`).
Файл prefs-а тоже попадает в резервную копию.

---

## Импорт / экспорт JSON-программ

**Формат — snake_case.** Это проверено на реальном экспорте из приложения
(файл `Скорость 60м.json`, октябрь 2026). README до v2.3.2 ошибочно
документировал camelCase.

### Схема (эталон)

    {
        "name": "Интервальная тренировка",
        "description": "5 интервалов высокой интенсивности",
        "segments": [
            { "duration_sec": 300, "speed_kmh": 5.0, "incline_percent": 0.0, "name": "Разминка" },
            { "duration_sec": 120, "speed_kmh": 10.0, "incline_percent": 0.0, "name": "Интервал 1" }
        ]
    }

### Правила

- `name` (String) — обязательно, не пустое.
- `description` (String) — обязательно, может быть пустым.
- `segments` (Array) — минимум 1.
- `segments[].duration_sec` (Int) — секунды, **> 0**.
- `segments[].speed_kmh` (Double) — км/ч, **> 0**. **Всегда с точкой** (`5.0`, `10.5`).
- `segments[].incline_percent` (Double) — %. **Всегда с точкой** (`0.0`, `1.0`, `1.87`).
  Никогда `0`, `1` без точки — парсер может упасть.
- `segments[].name` (String) — обязательно.

### Как работает импорт

`ProgramRepository.importJson()` → `json.decodeFromString(ProgramData.serializer(), text)`.

Парсер: `Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }`.

- `ignoreUnknownKeys = true` → лишние поля не ломают импорт (но и не сохраняются).
- Обязательные поля **без дефолтов** в `ProgramData` / `ProgramSegment` →
  отсутствие поля = `MissingFieldException`.
- Валидация после парсинга в `ProgramsScreen.kt`:
  `name.isNotBlank() && segments.isNotEmpty() && all(duration_sec > 0 && speed_kmh > 0)`.

### MIME-фильтр SAF

В `ProgramsScreen.kt` лончер импорта:

    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))

Файл с любым расширением будет виден в пикере. Проблема — только в содержимом.

### Как получить эталон

Приложение → Программы → любая программа → ⋮ → «Экспорт JSON».
Файл гарантированно валиден.

### Частые ошибки

- `durationSec` / `speedKmh` / `inclinePercent` (camelCase) → **не импортируется**.
- `"incline_percent": 1` (Int) → может упасть, пиши `1.0`.
- Smart quotes (`"…"`) → парсер не понимает.
- BOM (`EF BB BF`) в начале файла → парсер видит `\uFEFF{` вместо `{`.
- Комментарии `//` или `#` → JSON не поддерживает, парсер упадёт.

---

## Резервное копирование (v2.3.1)

`logic/BackupManager.kt` — экспорт/импорт ZIP через SAF (`CreateDocument` /
`OpenDocument`). Новых зависимостей нет: `java.util.zip` + `kotlinx.serialization`.

### Формат ZIP

    manifest.json
    database/treadmill.db
    database/treadmill.db-wal   (если есть)
    database/treadmill.db-shm   (если есть)
    shared_prefs/treadmill_settings.xml

### Поля manifest.json

    version         Int    — версия формата архива, сейчас 1
    appVersionName  String — versionName приложения на момент экспорта
    dbVersion       Int    — версия схемы Room (2)
    createdAt       Long   — миллисекунды Unix
    programCount    Int    — количество программ
    workoutCount    Int    — количество тренировок в журнале
    profileCount    Int    — количество профилей спортсменов

Пример содержимого (для наглядности, в самом файле — компактный JSON):

    {
      "version": 1,
      "appVersionName": "2.3.1",
      "dbVersion": 2,
      "createdAt": 1700000000000,
      "programCount": 5,
      "workoutCount": 42,
      "profileCount": 2
    }

### Экспорт

1. `PRAGMA wal_checkpoint(TRUNCATE)` — склеивает WAL в основной `.db`.
2. Сборка `manifest.json` (количество программ/логов через `COUNT(*)`, чтобы
   не зависеть от DAO).
3. Запись ZIP в URI, выбранный пользователем. Имя по умолчанию —
   `treadmill-backup-YYYY-MM-DD-HHmmss.zip`.

Экспорт **не блокируется** во время тренировки — просто снимает консистентный
снимок. Если checkpoint не удался (БД занята) — в ZIP попадут `.db` + `.db-wal`
+ `.db-shm`, что тоже валидно.

### Восстановление

1. `readManifest(uri)` — читает **только** `manifest.json`, не распаковывая
   остальное. Показывает диалог с превью (дата, кол-во программ/логов/профилей).
2. **Блокировка:** если запущен `TreadmillService` (значит, идёт тренировка
   или просто подключена дорожка) — показывается диалог «Сейчас нельзя
   восстановить». Сделано через `ActivityManager.getRunningServices()`
   (для собственных сервисов работает и на Android 12+).
3. После подтверждения:
   - `AppDatabase.closeAndReset()` — закрывает Room и обнуляет singleton.
   - Распаковка ZIP во временную папку → валидация (`manifest.json` + `.db`).
   - Замена `.db`, `.db-wal`, `.db-shm`, `treadmill_settings.xml` на диске.
   - `AppDatabase.get(context)` — переоткрытие (sanity check).
4. **Авто-перезапуск** процесса: `AlarmManager.set(RTC, now+150ms, pendingIntent)`
   + `Process.killProcess(myPid())` через 100 мс. Так новые данные гарантированно
   прочитаются в `Application.onCreate`. Если `AlarmManager` недоступен —
   fallback на `startActivity + finishAffinity()`.

### Важно

- `AppDatabase.closeAndReset()` вызывать **только** из `BackupManager.restoreFrom()`.
- После восстановления все in-memory синглтоны (`TreadmillApp.instance.session`,
  `settingsStore`, `programRepository`, `ttsManager`) **устаревают** — именно
  поэтому нужен полный перезапуск, а не «горячее» применение.
- SAF-пикер не требует `WRITE_EXTERNAL_STORAGE` и работает на API 21+.
- Восстановление во время активного сервиса запрещено.

---

## UI — визуальная структура

### Общая навигация

- **Нижняя панель** (NavigationBar) на 4 вкладки: **Главная**, **Программы**, **Журнал**, **Настройки**.
- **Свайп** между вкладками через `HorizontalPager`.
- **FAB с жучком** (Debug) — **удалён** из навигации. Доступ через «Настройки → О приложении → Debug».
- **Локализация**: все тексты через `stringResource()`. Язык берётся из системных настроек Android.

### Главный экран (MainScreen)

Вертикальная колонка сверху вниз (без прокрутки):

1. **Заголовок** «Treadmill Control».
2. **Имя дорожки** + кнопка **ℹ️** (при подключении) → диалог возможностей.
3. **Две карточки статуса** в ряд: **Дорожка** | **Пульсометр**.
4. **Строка текущего сегмента** (высота 22dp) — `Сегмент 4/12 · Подъём · ПАУЗА`.
5. **Две плитки управления**: **Скорость** | **Наклон**.
6. **Карточка метрик** (2×2): Пульс | Время | Дистанция | Калории.
7. **Автопульс**: Switch + «Настроить».
8. **Нижняя панель управления**: Старт | Пауза/Продолжить | Стоп (HoldToConfirm 0.8 сек).

### Экран тренировки (WorkoutScreen)

1. **Заголовок** «← Назад | Тренировка [имя] | 🐛».
2. **Карточка прогресса** (сегмент, бейдж ПАУЗА, LinearProgressIndicator).
3. **Две плитки управления**: Скорость | Наклон.
4. **Карточка графика пульса** (weight 1f).
5. **Карточка метрик** (3 колонки).
6. **Кнопки**: Пауза/Продолжить | Пропустить | Стоп. На финише — «Готово».
7. **Диалог подтверждения выхода**.

### Экран Программ (ProgramsScreen)

- **Scaffold** с `ExtendedFloatingActionButton` «Создать».
- **Заголовок** + иконки: ✨ (генератор), 📤 (импорт JSON), ◀️ (назад).
- **Поиск** + **Сортировка** (меню).
- **LazyColumn карточек**: название, описание, ▶️ и ⋮ (Изменить, Дублировать, Экспорт JSON, Удалить), мини-график, чипы `N сегм.` + `X мин`, раскрытие со списком сегментов.
- **Диалоги**: TemplatesDialog, WorkoutGeneratorDialog, ProgramEditorScreen (полноэкранный).

### Экран Журнала (HistoryScreen)

- **Заголовок** + ⋮ (демо, очистить).
- **Таб-чипы**: «Список» | «Статистика» + ℹ️.

**Список:** `CompactLogCard` с датой, программой, метриками; раскрытие — sparkline пульса, зоны (с пометкой «≈» для старых записей).

**Статистика:** сводка, пульс (средний/макс/лучший + тренд), время в зонах (бар + легенда), график среднего пульса (Безье), Info-диалог.

### Экран Настроек (SettingsScreen)

- **Спортсмены** (Person): «Профили тренировок» → ProfilesScreen.
- **Внешний вид и звук** (Palette): тема, «Не гасить экран», TTS + «Настроить фразы».
- **Скорость и наклон** (Speed): min/max скорость, шаг, max наклон.
- **Автопульс** (FavoriteBorder): режим, цель/зона, пороги, интервал, шаг, max HR (авто/ручной).
- **Данные** (Storage): экспорт/восстановление.
- **О приложении** (Info): Debug, О программе.

### Экран Профилей (ProfilesScreen)

- **FAB** «Добавить».
- Карточки `ProfileCard`: радио выбора, имя/вес/рост/возраст/пол, Edit/Delete, зоны Z1–Z5.
- **Диалог**: имя, вес, рост, возраст, пульс покоя, пол, уровень.

### TTS-настройки (TtsSettingsScreen)

- Карточка-справка с тегами.
- 5 полей (старт, сегмент, финиш, пауза, продолжение) + «Прослушать».
- «Сбросить настройки по умолчанию».

### About (AboutScreen)

- Версия, описание, офлайн-принцип.
- Совместимость, Возможности, Стек.
- Инструкция «Как создать тренировку» + копирование шаблона/инструкции.

### Debug (DebugScreen)

- Карточка статуса.
- Ввод hex.
- Три таба: Лог | Справочник | Конструктор.
- Кнопки: Очистить, Сохранить, Поделиться.

---

## Компоненты (ui/components/)

### HeartRateChart

Чистый Canvas. Ось Y слева, даты X снизу. 5 полупрозрачных зон (если `zoneBounds`). Сглаженная кривая Безье. Градиент под линией. Точки (если 2–20). Опциональная линия цели.

### HrSparkline

Мини-график для одной тренировки. Без осей и зон. Использует `durationSec` для нормализации X.

### ZoneLegend

Компактная легенда Z1–Z5. Формат: `Z1 <123`, `Z2 123-134`.

### CapabilitiesDialog

Только по кнопке ℹ️. Список с ✓ / ✗. Форматированные диапазоны.

### RollingText

Плавная анимация смены чисел.

---

## Сборка

### .github/workflows/build.yml

- Сборка APK при каждом push.
- `versionCode` из `github.run_number` (+10000).
- Шаги `Verify keystore` и `Show APK signature`.

### 🔑 Подпись APK

**Проблема:** каждый билд подписывался новым случайным ключом → «Конфликт пакетов» при установке поверх старого APK.

**Решение:**

- Постоянный файл `app/keystore/treadmill.keystore` в репо (пароли `treadmill123`, alias `treadmill`).
- В `app/build.gradle.kts` — явный `signingConfigs.create("stable")`.
- В CI — `Verify keystore` (падает при отсутствии) + `Show APK signature`.

**⚠️ Нельзя:**

- Удалять/заменять `app/keystore/treadmill.keystore`.
- Менять пароли/alias в `build.gradle.kts`.
- Полагаться на `~/.android/debug.keystore`.

**Резервная копия keystore** хранится вне GitHub. Потеря = разовая переустановка с потерей данных.

---

## Известные подводные камни (НЕ ПЕРЕДЕЛЫВАТЬ)

1. **MenuAnchorType** — нет в BOM 2024.02.00. Только `.menuAnchor()`.
2. **Gradle 9.x** несовместим с AGP 8.2.2. Только 8.6.
3. **Configuration cache** отключён.
4. **Wake-up**: если дорожка спит, нужно нажать физическую кнопку.
5. **`stop()`** использует `08 01`, а не `02 00 00`.
6. В `TreadmillSession` **нельзя** вызывать `viewModelScope`. Используется `scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`.
7. **`BleConnection.write()`** — старая версия, не ждёт ACK. Везде — `writeSuspend()`.
8. При переходе на новый сегмент всё обновляется **ОДНИМ** `copy()` в `WorkoutManager.launchLoop`.
9. **`startJob` / `autoReconnectJob`** ДОЛЖНЫ отменяться при Stop/Pause/ручном подключении.
10. **Кулдаун сканирования** (`BleManager.setScanCooldown`) блокирует `startScan` на уровне BLE.
11. **`%.Nf` в strings.xml**: нельзя `"$str %.1f".format(...)`, если в `str` есть `%`. Разделяй форматирование чисел и конкатенацию строк.
12. **`stringResource` в Canvas**: нельзя @Composable внутри `Canvas`. Вычисляй строки **до** `Canvas { ... }`.
13. **Android 14+**: требуется `android:foregroundServiceType="connectedDevice"` у `TreadmillService` + разрешение `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
14. **Ручная тренировка** сохраняется как `programName = ""`. При отображении подставляется `stringResource(R.string.manual_workout)`.
15. **`WorkoutMiniChart`**: `stringResource` нельзя звать внутри `Canvas {}`. Вычисляй `hourSuffix` до `Canvas`.
16. **`AppDatabase.closeAndReset()`** — вызывать **только** из `BackupManager.restoreFrom()`, иначе Room потеряет соединение и DAO начнут падать.
17. **Формат JSON-программ — snake_case**: `duration_sec`, `speed_kmh`, `incline_percent`. НЕ camelCase. README до v2.3.2 врал. Все double-поля только с точкой (`1.0`, `0.0`, `7.5`), иначе парсер может упасть на `incline_percent: 1`. Формат проверен на реальном экспорте из приложения (файл `Скорость 60м.json`). Импорт: `ProgramRepository.importJson()` → `json.decodeFromString(ProgramData.serializer(), text)` с `Json { ignoreUnknownKeys = true }`. MIME-фильтр в `ProgramsScreen.kt`: `arrayOf("application/json", "text/plain", "*/*")`. Самый надёжный способ получить эталон — «Программы → ⋮ → Экспорт JSON».

---

## Тесты — статус

### Пройдено (актуально на v2.3.2)

- A1, A2, B1-B7 (базовые команды, кроме наклона — принимается, но не двигается).
- C1-C6 (программы, пауза, стоп).
- D1-D2 (метрики).
- B6 (пауза/продолжить восстанавливает 1.0 км/ч).
- C4 (пауза в программе).
- Работа в фоне: связь не рвётся, метрики идут, уведомление висит.
- Автопереподключение: логи показывают цикл реконнекта.
- **Подпись APK**: keystore в репо, SHA-256 стабилен между билдами.
- **Обновление поверх**: новый APK ставится без удаления, данные сохраняются.
- **TTS**: все 5 шаблонов работают, кнопка «Прослушать» озвучивает фразу.
- **Автопульс**: скорость меняется при выходе пульса за границы.
- **Профили**: переключение активного профиля влияет на зоны/калории.
- **Генератор**: разница между новичком и профи — колоссальная. Названия локализованы (RU/EN).
- **Capabilities**: UNixFit отдаёт 1.0-20.0 км/ч, 0-15% наклон.
- **SOS-защита**: Stop во время startJob мгновенно прекращает команды.
- **HoldToConfirmButton**: удержание 0.8 сек с прогрессом и haptic.
- **Показ сегмента на главном**: `SegmentLine` отображается корректно.
- **Уведомление в шторке**: заголовок с сегментом, кнопки Пауза/Стоп работают.
- **Обработка потери связи**: тренировка ставится на паузу, после реконнекта — сообщение.
- **Кривая пульса в БД**: сохраняется, sparkline рисуется, зоны точные.
- **Локализация RU/EN**: экраны, шаблоны, кнопки, диалоги переключаются по системе.
- **Connect→disconnect→connect**: дорожка снова находится в сканере.
- **AndroidManifest**: `foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` присутствуют. `BLUETOOTH_ADVERTISE` удалён.
- **Резервная копия**: экспорт в ZIP, восстановление с превью манифеста, блокировка при активном сервисе, авто-перезапуск. SAF без разрешений.
- **Импорт JSON-программ**: формат snake_case подтверждён на реальном экспорте. `Probe 3 seg` (3 сегмента) и `Test 60 min` (27 сегментов) импортируются.

### Не проверено / требует внимания

- Android 12+ и `BLUETOOTH_SCAN` (манифест корректен, поведение на конкретных прошивках не тестировалось).
- Планшеты и складные устройства.
- Часовая диагностическая тренировка (в процессе — фазы 01–27, см. отдельный JSON).

---

## Backlog

- [x] Автопереподключение BLE.
- [x] Починить CI (Keystore).
- [x] TTS голосовые подсказки (с настройкой фраз).
- [x] Автопульс (AutoHR).
- [x] Профили пользователей.
- [x] Генератор тренировок.
- [x] Автоопределение возможностей дорожки (FTMS Capabilities).
- [x] Локализация RU/EN.
- [x] HoldToConfirmButton.
- [x] Показ текущего сегмента на главном.
- [x] Уведомление в шторке с кнопками.
- [x] Обработка потери связи во время тренировки.
- [x] Кривая пульса в БД.
- [x] Исправление «залипания» GATT-подключения.
- [x] Локализация названий программ в `ProgramGenerator.kt`.
- [x] Проверка `AndroidManifest.xml` на `foregroundServiceType`.
- [x] Резервное копирование БД (ZIP) с SAF-экспортом.
- [x] Фикс формата JSON в README (snake_case, v2.3.2).
- [ ] Экспорт в TCX (для Strava/Garmin) — следующий приоритет.
- [ ] Экспорт в FIT (требует сторонней библиотеки, отложено).
- [ ] Onboarding при первом запуске.
- [ ] Виджет на рабочий стол.
- [ ] Достижения / медали.
- [ ] Плавный разгон скорости (S-curve ramp).
- [ ] Адаптация под планшеты и складные.

---

## Changelog

### v2.3.2 (формат JSON программ — исправление доков)

- **README.md / NOTES.md**: исправлен формат JSON-программ. Ошибочно документировался camelCase (`durationSec`, `speedKmh`, `inclinePercent`), реальный парсер ждёт **snake_case** (`duration_sec`, `speed_kmh`, `incline_percent`). Проверено на реальном экспорте из приложения (файл `Скорость 60м.json`).
- **Добавлен раздел «Импорт / экспорт JSON-программ»** в NOTES.md с полной схемой, правилами, MIME-фильтром и частыми ошибками.
- **Пункт 17** в «Известных подводных камнях»: snake_case + double с точкой + эталон через экспорт.
- **Раздел «Для ИИ-ассистента»**: краткая шпаргалка для следующего ИИ-сеанса (запреты, формат, что сделано, что в бэклоге, как получить эталон).
- Код приложения **не менялся** — только документация. Парсер `ProgramRepository` работал корректно всё время; врал README.

### v2.3.1 (резервное копирование + синхронизация docs)

- **BackupManager** (`logic/BackupManager.kt`):
  - SAF-экспорт `CreateDocument("application/zip")`.
  - SAF-импорт `OpenDocument()` — выбор ZIP + превью манифеста.
  - Формат архива: `manifest.json` + `database/treadmill.db{,-wal,-shm}` + `shared_prefs/treadmill_settings.xml`.
  - `PRAGMA wal_checkpoint(TRUNCATE)` перед экспортом.
  - Блокировка восстановления при запущенном `TreadmillService`.
  - Авто-перезапуск через `AlarmManager` + `Process.killProcess`.
- **AppDatabase.closeAndReset()** — закрытие Room и сброс singleton для restore.
- **SettingsScreen**: секция «Данные» с кнопками экспорта/импорта.
- **AndroidManifest**: удалён `BLUETOOTH_ADVERTISE`.
- **Docs**: NOTES.md и README.md синхронизированы; исправлено противоречие про `08 01` vs `02 00 00` и про NordicTrack/iFit.

### v2.3 (локализация + итерации 1–5)

- **Локализация RU + EN**:
  - `values/strings.xml` (EN, дефолт) + `values-ru/strings.xml` (RU).
  - Все экраны на `stringResource`.
  - Шаблоны тренировок локализованы.
  - TTS-шаблоны по умолчанию — из ресурсов.
  - `Belt` → «дорожка» в интерфейсе.
- **Точка 1** — **HoldToConfirmButton**: удержание 0.8 сек с прогрессом и haptic.
- **Точка 2** — **SegmentLine**: показ текущего сегмента на главном.
- **Точка 3** — **Кривая пульса в БД**:
  - Миграция Room 1→2: `hrSamples`.
  - `HrSamplesCodec` — формат `"0:120,5:125"`.
  - `HrSparkline` — мини-график.
  - Зоны в истории — точные (с пометкой «≈» для старых).
- **Точка 4** — **Уведомление в шторке**: сегмент, скорость, пульс, время, `ПАУЗА`; кнопки Пауза/Стоп.
- **Точка 5** — **Обработка потери связи**: автопауза, после реконнекта — сообщение.
- **Дизайн**: убрана карточка-подсказка statusMessage; кнопки не «плывут» при подключении; ℹ️ — `FilledIconButton` 28dp; авто-показ CapabilitiesDialog убран; FAB с жучком убран; график максимального пульса убран; график среднего — Безье без зон; Info-кнопка в статистике.
- **BLE-фикс**: корректный `disconnect → wait DISCONNECTED callback → refresh() → close()` с таймаутом 3 сек; `isDisconnecting`; `hasBeenReady` в stateJob.

### v2.2 (TTS / AutoHR / Профили / Генератор / Capabilities)

- **TTS**: `TtsManager`, `TtsSettingsScreen` с 5 шаблонами, теги `{name}`, `{segName}`, `{speed}`, `{speed_text}`, `{angle}`, `{angle_text}`, `{i}`, `{n}`, `{dist}`, `{hr}`.
- **AutoHR**: цикл в `TreadmillSession` каждые `intervalSec` секунд.
- **Профили**: `UserProfile`, `ProfilesScreen` CRUD.
- **Генератор**: `ProgramGenerator.generate()` с параметрами цель/длительность/интенсивность.
- **Capabilities**: `TreadmillCapabilities` парсит FTMS Feature / Speed Range / Incline Range.
- **Графики пульса**: `HeartRateChart` на чистом Canvas, `ZoneLegend`, `ZoneStatsCard`.
- **SOS/стабильность BLE**: `startJob`/`autoReconnectJob` отменяются, `BleConnection.disconnect() → refresh() → close()`, `BleManager.setScanCooldown(5s)`.

### v2.1 (UI & BLE Fixes)

- **UI Редизайн**: Программы и Редактор переработаны. Карточки раскрывающиеся, +/- с авто-повтором, TimePicker (Wheel Picker). Debug сжат. About обновлён.
- **BLE Фиксы**: `write()` → `writeSuspend()`; `sendRaw` + логирование в `FTMSProtocol`.
- **Тренировки**: прогресс по сегментам, автосохранение в журнал, кнопка «Стоп» → «Готово».
- **DebugLog**: единый хаб логирования.

### v2.0+ (CI fix)

- **Подпись**: keystore в репо, `signingConfigs.create("stable")`, `Verify keystore` + `Show APK signature` в CI.
- `.gitignore` добавлен.

### v2.0+

- **Архитектура**: `TreadmillSession` + `TreadmillService`.
- **Протокол**: `08 01` для остановки.
- **Автопульс и зоны**: профиль спортсмена, зоны Карвонена.
- **Баги**: сохранение ручных тренировок, пропуск сегментов, скорость после паузы, утечка GATT.
- **UI**: без прокрутки главного, подтверждение выхода, Material 3.

---

## Для ИИ-ассистента (краткая шпаргалка)

Этот раздел — для следующего ИИ, который откроет NOTES.md в новом чате.
Прочитай до того, как что-то предлагать. Здесь — грабли, на которые уже
наступали.

### Стек и запреты

- Kotlin 1.9.22, Compose BOM 2024.02.00, Material 3 **без** `MenuAnchorType`
  (только `.menuAnchor()`), Room 2.6.1 (БД v2), Gradle 8.6 + AGP 8.2.2
  (Gradle 9.x **нельзя**).
- В `TreadmillSession` **нельзя** `viewModelScope` — только свой
  `CoroutineScope(Dispatchers.IO + SupervisorJob())`.
- `BleConnection.write()` — старая версия без ACK. Везде в протоколе —
  `writeSuspend()`.
- `stringResource` **нельзя** звать внутри `Canvas {}` — вычисляй до.
- `"%.Nf".format(...)` на строке с `%` в конце — опасная комбинация, см. п.11.
- Keystore `app/keystore/treadmill.keystore` — **не трогать**.

### Формат JSON-программ — snake_case (частый источник ошибок)

Поля: `duration_sec`, `speed_kmh`, `incline_percent`.

**Не** `durationSec` / `speedKmh` / `inclinePercent`. Все double — **с точкой**
(`1.0`, `0.0`), никогда `1`, `0`. Подробнее — раздел «Импорт / экспорт
JSON-программ». Эталон — «Программы → ⋮ → Экспорт JSON».

### BLE-протокол belt'а (коротко)

- Старт: `00` → 200 мс паузы → `07` → `02 XX XX` (скорость, LE, км/ч × 100).
- Скорость: только `02 XX XX`. Наклон: `03 XX XX` (% × 10 LE).
- Стоп: **`08 01`** (сбрасывает таймер). `02 00 00` — не использовать.
- Защиты: `startJob`/`autoReconnectJob` отменяются при Stop/Pause/ручном
  подключении; `hasBeenReady` в stateJob; `isDisconnecting` в BleConnection;
  кулдаун сканирования 5 сек.

### Перед тем как предлагать код — спроси или проверь

- **Не доверяй README/NOTES про формат JSON** — они уже врали. Проверь
  `ProgramEntity.kt` (`@Serializable` / `@SerialName`) или попроси экспорт.
- **Не давай «воздушные» интерфейсы** — дизайн плотный, без больших отступов.
- **Полные файлы, не куски** — пользователь работает в веб-редакторе GitHub
  (Ctrl+A → вставить). Если файл большой — предупреди и уточни.
- **Кавычки в XML** — прямые `"`, не smart. **Markdown-файлы** — не оборачивай
  в код-блок с ` ``` `, если внутри уже есть ` ``` ` — рассыпется. Используй
  отступ в 4 пробела для внутренних блоков.

### Что уже сделано (не предлагать заново)

- TTS с редактируемыми шаблонами, AutoHR, профили, генератор тренировок,
  FTMS Capabilities, точные пульсовые зоны (миграция Room 1→2), HoldToConfirm,
  уведомление в шторке, локализация RU/EN, резервное копирование ZIP (SAF),
  фикс GATT-залипания, `foregroundServiceType="connectedDevice"`.

### Что в бэклоге (приоритет сверху вниз)

1. Экспорт TCX (для Strava/Garmin) — следующий.
2. Экспорт FIT (требует сторонней библиотеки).
3. Onboarding.
4. Виджет на рабочий стол.
5. Достижения / медали.
6. S-curve ramp (плавный разгон скорости).
7. Адаптация под планшеты.

### Как получить эталон формата

- JSON программы: «Программы → ⋮ → Экспорт JSON».
- Backup ZIP: «Настройки → Данные → Экспорт резервной копии».
- BLE-лог: «Debug → 💾 Сохранить».
