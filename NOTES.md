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

Репозиторий **публичный** — GitHub Actions без лимитов на минуты и хранилище
артефактов (для private-репо был лимит 500 МБ, намучились).

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
- CI/CD: GitHub Actions (автоматический versionCode, публичный репо)

---

## Структура проекта

    app/src/main/java/com/custom/treadmill/
    ├── TreadmillApp.kt              — Application, ручной DI (включая TtsManager)
    ├── MainActivity.kt              — точка входа, тема из настроек, перехват тапов/свайпов, запрос POST_NOTIFICATIONS
    ├── DebugLog.kt                  — единый хаб логирования (memory + file)
    │
    ├── ble/
    │   ├── Uuids.kt                 — UUID сервисов и характеристик (FTMS + HR)
    │   ├── BleManager.kt            — сканирование BLE + кулдаун + защита от ALREADY_STARTED
    │   ├── BleConnection.kt         — обёртка BluetoothGatt (gattMutex, setNotifySuspend, writeSuspend)
    │   ├── ITreadmillProtocol.kt    — интерфейс + TreadmillData + capabilities
    │   ├── TreadmillCapabilities.kt — парсер FTMS Feature / Speed/Incline Range
    │   ├── FTMSProtocol.kt          — стандартный FTMS (строгий парсер, incline 4 байта)
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
    │       ├── ProgramRepository.kt — CRUD, JSON, демо (БЕЗ deleteAll)
    │       └── SettingsStore.kt     — SharedPreferences + JSON + профили + TTS + битый JSON
    │
    ├── logic/
    │   ├── WorkoutManager.kt        — программа + пауза/пропуск/onFinish (safeSetSpeed/Incline)
    │   ├── HeartRateController.kt   — авторегулировка (AutoHR)
    │   ├── HeartRateZoneCalculator.kt — расчет пульсовых зон (Karvonen)
    │   ├── ProgramGenerator.kt      — генератор тренировок (волшебная палочка)
    │   ├── TtsManager.kt            — обёртка TextToSpeech
    │   └── BackupManager.kt         — экспорт/импорт ZIP (атомарное восстановление)
    │
    ├── service/
    │   └── TreadmillService.kt      — Foreground Service (уведомление в шторке с кнопками)
    │
    └── ui/
        ├── Theme.kt, AppNavHost.kt, Permissions.kt
        ├── components/
        │   ├── HeartRateChart.kt, HrSparkline.kt, ZoneLegend.kt
        │   ├── CapabilitiesDialog.kt, RollingText.kt
        ├── screens/
        │   ├── MainScreen.kt        — главный, HoldToConfirmButton, SegmentLine, BLE-разрешения при тапе «Подкл.»
        │   ├── WorkoutScreen.kt, SettingsScreen.kt
        │   ├── ProgramsScreen.kt    — списки, редактор, шаблоны (clamp по лимитам), генератор, импорт/экспорт
        │   ├── HistoryScreen.kt     — список, статистика, Info-кнопка, диалог подтверждения демо
        │   ├── DebugScreen.kt       — 3 таба, фильтры FTMS + UI (компактные switch)
        │   ├── AboutScreen.kt       — без шаблона/инструкции, короткая подсказка
        │   ├── ProfilesScreen.kt, TtsSettingsScreen.kt
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
- FTMS Control Point Alt: 0x2A66 — **ВНИМАНИЕ: это Cycling Power Control Point, а не FTMS** (см. подводные камни)
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
5. `parseTreadmillData`: если флаг incline отсутствует → incline = prev.inclinePercent
   (раньше было 0.0 — баг, пакет без флага обнулял наклон).
6. **Особенность BLE-чипа FitShow**: иногда «залипает» в состоянии «подключён»
   на 30-60 сек после отключения телефона. Приложение при этом перестаёт
   видеть дорожку в сканере. Лечится только выключением/включением питания
   дорожки. Не баг приложения. **Частично лечится** корректным `disconnect → wait callback → refresh() → close()`.

### Парсер FTMS — строгий (v2.3.3)

`FTMSProtocol.parseTreadmillData()`:

- Все чтения проверяют `o + size <= b.size`, иначе `return` (не пытаться парсить обрезанный пакет).
- `Inclination (bit 3)` = **4 байта**: Inclination s16 + Ramp Angle s16. Было 2 байта — критический баг, сдвигал всё после наклона.
- `Elevation Gain (bit 4)` = **4 байта**: Pos uint16 + Neg uint16. Было 2 байта.
- `Total Energy (bit 7)` = 5 байт: Total Energy uint16 + Energy Per Hour uint16 + Energy Per Minute uint8.
- Если `prev.inclinePercent` был ненулевым, а в новом пакете нет флага incline — сохраняем предыдущее значение.

---

## Логика работы (TreadmillSession)

Вся BLE-логика вынесена из ViewModel в `TreadmillSession`.

- **Автопереподключение**: если связь теряется, 10 попыток с интервалом 3 сек
  к MAC-адресу. Отменяется при ручном подключении/отключении (`autoReconnectJob`).
- **AutoHR (автопульс)**: цикл каждые `intervalSec` секунд. Если `hrEnabled`
  и пульс выше верхней границы → `-stepKmh`. Если ниже нижней → `+stepKmh`.
  НЕ работает во время программы. Учитывает наклон, вес, ИМТ.
- **TTS**: озвучка старта, смены сегмента, паузы/продолжения, финиша.
- **Метрики**: `uiElapsedSec`, `uiDistanceKm`, `uiCalories` (формула MET).
- **Capabilities**: после подключения читает FTMS Feature / Speed Range / Incline Range.
- **Foreground Service**: `TreadmillService` держит процесс живым + плашка в шторке.
- **WorkoutManager**: управляет программой. При пропуске сегмента время не накручивается.

### Важные защитные механизмы

- `startJob` — фоновый запуск belt. Отменяется при Stop/Pause.
- `autoReconnectJob` — цикл реконнекта. Отменяется при ручном подключении.
- **Кулдаун сканирования** — 5 сек после ручного отключения.
- **`hasBeenReady`** в stateJob — предотвращает ложный `triggerAutoReconnect` на первой эмиссии.
- **`isDisconnecting`** в `BleConnection` — защита от повторного `disconnect()`.
- **`gattMutex`** в `BleConnection` (v2.3.3) — все GATT-операции строго сериализованы, один `activeDeferred: CompletableDeferred<Any?>`.

### Обработка потери связи (v2.3)

При `DISCONNECTED` во время активной тренировки/ручного режима:

1. Программа или ручной режим **ставится на паузу**.
2. Запускается цикл автопереподключения (10 попыток × 3 сек).
3. После успешного реконнекта — сообщение «Связь восстановлена. Тренировка на паузе».

### Кривая пульса (v2.3)

- В `WorkoutLogEntity` поле `hrSamples: String = ""`.
- Формат: `"0:120,5:125,10:130"` (offsetSec : bpm).
- Прореживание: 1 точка в 5 секунд.
- Для старых записей — гауссова аппроксимация с пометкой «≈ приближённо».

---

## Профили пользователей

`AppSettings.profiles: List<UserProfile>` + `activeProfileId`. Поля:
`id`, `name`, `weightKg`, `heightCm`, `age`, `isMale`, `restingHr`, `level`.

Используется: калории (вес), зоны пульса (возраст/пол/restingHr), генератор (ИМТ/уровень).

Экран: `ui/screens/ProfilesScreen.kt`, маршрут `Routes.PROFILES`.

---

## Голосовые подсказки (TTS)

Шаблоны в `AppSettings`, дефолты из ресурсов в `SettingsStore.load()`:
`ttsStartTemplate`, `ttsSegmentTemplate`, `ttsFinishTemplate`, `ttsPauseText`, `ttsResumeText`.

Теги: `{name}`, `{segName}`, `{i}`, `{n}`, `{speed}`, `{speed_text}`, `{angle}`,
`{angle_text}`, `{dist}`, `{hr}`.

Словоформы — через `plurals` (`tts_kmh_plural`, `tts_percent_plural`).

Экран: `ui/screens/TtsSettingsScreen.kt`, `Routes.TTS`.

---

## Генератор тренировок

`logic/ProgramGenerator.kt` — по профилю (ИМТ, уровень, пол), цель (WEIGHT_LOSS/CARDIO/SPEED),
длительность 20–60 мин, интенсивность 70–130%. Учитывает реальные лимиты `Capabilities`.
Названия программ/сегментов — через `strings.xml` (`gen_name_*`, `gen_desc_*`, `gen_seg_*`).

---

## Возможности дорожки (Capabilities)

`ble/TreadmillCapabilities.kt` — парсер FTMS Feature / Speed Range / Incline Range.

`ui/components/CapabilitiesDialog.kt` — только по кнопке ℹ️ рядом с именем дорожки.

Если дорожка не поддерживает наклон — все слайдеры наклона скрываются.

**v2.3.3**: шаблоны программ (`buildTemplates`) теперь clamp'ят свои скорости и наклоны
по `effectiveMaxSpeed` / `effectiveMaxIncline`. Раньше шаблон «Интервалы» создавал
сегменты со скоростью 15 км/ч, даже если в настройках max = 12.

---

## Настройки (AppSettings)

- `themeMode`: SYSTEM / LIGHT / DARK
- `keepScreenOn`, `ttsEnabled` + 5 шаблонов, `profiles` + `activeProfileId`
- `minSpeedKmh` / `maxSpeedKmh` / `speedStepKmh` / `maxInclinePercent`
- `hrEnabled` / `hrMode` / `targetHr` / `zoneMin` / `zoneMax` / `stepKmh` / `intervalSec`
- `thresholdHigh` / `thresholdLow`, `autoMaxHr` / `maxHr`

Prefs-файл: `treadmill_settings` (ключ `settings_json`).

**v2.3.3**: если JSON повреждён — сохраняется в `settings_json_broken_<timestamp>`,
пользователь не теряет профили молча.

---

## Импорт / экспорт JSON-программ

**Формат — snake_case** (проверено на реальном экспорте):

    {
        "name": "Интервальная тренировка 5×2 мин",
        "description": "5 интервалов по 2 минуты с отдыхом 1 минута",
        "segments": [
            { "duration_sec": 300, "speed_kmh": 5.0,  "incline_percent": 0.0, "name": "Разминка" },
            { "duration_sec": 120, "speed_kmh": 10.0, "incline_percent": 0.0, "name": "Интервал 1" },
            { "duration_sec": 60,  "speed_kmh": 6.0,  "incline_percent": 0.0, "name": "Отдых 1" },
            { "duration_sec": 120, "speed_kmh": 10.0, "incline_percent": 0.0, "name": "Интервал 2" },
            { "duration_sec": 60,  "speed_kmh": 6.0,  "incline_percent": 0.0, "name": "Отдых 2" },
            { "duration_sec": 120, "speed_kmh": 10.0, "incline_percent": 0.0, "name": "Интервал 3" },
            { "duration_sec": 60,  "speed_kmh": 6.0,  "incline_percent": 0.0, "name": "Отдых 3" },
            { "duration_sec": 120, "speed_kmh": 10.0, "incline_percent": 0.0, "name": "Интервал 4" },
            { "duration_sec": 60,  "speed_kmh": 6.0,  "incline_percent": 0.0, "name": "Отдых 4" },
            { "duration_sec": 120, "speed_kmh": 10.0, "incline_percent": 0.0, "name": "Интервал 5" },
            { "duration_sec": 300, "speed_kmh": 4.0,  "incline_percent": 0.0, "name": "Заминка" }
        ]
    }

Правила:

- `duration_sec` (Int) > 0, `speed_kmh` (Double) > 0, `incline_percent` (Double)
- **Double всегда с точкой** (`5.0`, `0.0`), никогда `5`, `0`
- `segments[].name` — обязателен, если пусто — подставится дефолт

Парсер: `Json { ignoreUnknownKeys = true; encodeDefaults = true }` в `ProgramRepository.importJson()`.
MIME-фильтр в SAF: `arrayOf("application/json", "text/plain", "*/*")`.

**v2.3.3**: если в импортированном файле `speed_kmh` или `incline_percent` превышают
`effectiveMaxSpeed` / `effectiveMaxIncline` — импорт проходит, но показывается
warning `programs_import_overflow`. Импорт **не блокируется**.

Эталон: «Программы → ⋮ → Экспорт JSON».

---

## Резервное копирование (v2.3.1 + v2.3.3)

`logic/BackupManager.kt` — экспорт/импорт ZIP через SAF.

Формат ZIP:

    manifest.json
    database/treadmill.db
    database/treadmill.db-wal   (если есть)
    database/treadmill.db-shm   (если есть)
    shared_prefs/treadmill_settings.xml

`manifest.json`: `version` (1), `appVersionName`, `dbVersion` (2), `createdAt`,
`programCount`, `workoutCount`, `profileCount`.

### Экспорт
1. `PRAGMA wal_checkpoint(TRUNCATE)`.
2. Сборка `manifest.json` (COUNT(*) через raw SQL).
3. Запись ZIP в URI из SAF `CreateDocument`.

Экспорт не блокирует тренировку.

### Восстановление (v2.3.3 — атомарное)
1. `readManifest(uri)` — читает только `manifest.json`, показывает превью.
2. **Блокировка**, если запущен `TreadmillService` (тренировка активна).
3. Распаковка во временную папку → валидация.
4. `AppDatabase.closeAndReset()`.
5. **Текущие `.db/.db-wal/.db-shm/prefs.xml` переименовываются в `.bak`** (не удаляются).
6. Новые копируются поверх.
7. При ошибке любого шага — `.bak` возвращаются на место (откат).
8. При успехе — `.bak` удаляются, `AppDatabase.get()` для sanity.
9. Авто-перезапуск через `AlarmManager` + `Process.killProcess`.

---

## UI — визуальная структура

### Общая навигация
- Нижняя панель на 4 вкладки: Главная, Программы, Журнал, Настройки.
- Свайп через `HorizontalPager`.
- Локализация RU + EN через `stringResource`.

### Главный экран (MainScreen)
1. Заголовок + имя дорожки + ℹ️ (Capabilities).
2. Две карточки статуса: Дорожка | Пульсометр.
3. `SegmentLine` (сегмент/всего · название · ПАУЗА).
4. Две плитки: Скорость | Наклон.
5. Карточка метрик 2×2: Пульс | Время | Дистанция | Калории.
6. Автопульс: Switch + Настроить.
7. Кнопки: Старт | Пауза/Продолжить | Стоп (HoldToConfirm 0.8 сек).

**v2.3.3**: BLE-разрешения запрашиваются **при тапе «Подкл.»**, а не на старте.
Сначала показывается POST_NOTIFICATIONS (из MainActivity), потом при первом
подключении — BLE. Не блокирует первый запуск. При отказе — `ble_permission_denied`.

### Экран тренировки
Прогресс, плитки скорости/наклона, график пульса (weight 1f), метрики, кнопки
Пауза/Пропустить/Стоп (или Готово), диалог выхода.

### Экран программ
Scaffold + FAB «Создать». Поиск + сортировка. Карточки раскрывающиеся
(мини-график, чипы, список сегментов). Диалоги: Templates, Generator, Editor.

**v2.3.3**: шаблоны clamp'ятся по `effectiveMaxSpeed`/`effectiveMaxIncline`.
Импорт с превышением — warning, но не блок.

### Экран журнала
Табы «Список» | «Статистика» + ℹ️. Раскрывающиеся карточки, sparkline, зоны
пульса (с пометкой «≈» для старых записей).

**v2.3.3**: пункт «Загрузить демо» — добавлен диалог подтверждения. Сами демо-записи
**больше не стирают историю**, а добавляются с префиксом `(demo)` в названии.

### Экран настроек
Спортсмены (→Профили), Внешний вид и звук (тема, keepScreenOn, TTS),
Скорость и наклон (min/max, шаг, max incline), Автопульс (режим, цель/зона,
пороги, интервал, шаг, max HR), Данные (экспорт/восстановление), О приложении.

### Экран профилей
Список `ProfileCard`: радио выбора, имя/вес/рост/возраст/пол, Edit/Delete, зоны Z1–Z5.

### TTS-настройки
Справка по тегам, 5 полей с «Прослушать», сброс на дефолт.

### About (v2.3.3)
Карточка с версией, описанием, офлайн. Совместимость, Возможности, Стек.
**Карточка «Создать свою тренировку»**: короткая подсказка (`about_custom_workout_hint`).
Кнопки «Шаблон» и «Инструкция» **удалены** — не имели практического смысла,
плюс шаблон был в старом формате. Текст инструкции (`about_instructions`)
остался в ресурсах, но не отображается.

### Debug (v2.3.3)
3 таба: Лог | Справочник | Конструктор. В шапке лога — **два switch**:
- **FTMS** — скрыть кадры 2ACD (по умолчанию скрыто).
- **UI** — скрыть `[UI]` события тапов/свайпов (по умолчанию скрыто, но **в файл пишутся всегда**).

Компактные switch реализованы через `Box(Modifier.size(34.dp, 24.dp)) { Switch(Modifier.scale(0.7f)) }`,
потому что `Modifier.size` на Switch в Material3 не работает корректно.

---

## Компоненты (ui/components/)

- **HeartRateChart** — Canvas, зоны, градиент, Безье, точки (2–20), опциональная цель.
- **HrSparkline** — мини-график пульса для одной тренировки.
- **ZoneLegend** — легенда Z1–Z5.
- **CapabilitiesDialog** — возможности дорожки.
- **RollingText** — плавная анимация смены чисел.

---

## Сборка

### .github/workflows/build.yml
- Триггер: push в main/master + `workflow_dispatch`.
- `versionCode` из `github.run_number` (+10000), `versionName` из run_number.
- Шаги: Checkout → JDK 17 → Gradle 8.6 → Verify keystore → Inject version → Build → Show signature → Rename → Upload artifact (**retention-days: 1, compression-level: 0**) → GitHub Release.
- Основное место раздачи APK — **Releases** (бесплатно, бессрочно). Artifacts — только для удобства на 1 день.
- **Публичный репо**: нет лимитов на минуты/хранилище.

### 🔑 Подпись APK

Постоянный keystore `app/keystore/treadmill.keystore` (пароли `treadmill123`,
alias `treadmill`) — **закоммичен в публичный репо**. Это потенциальная
уязвимость: любой может подписать этим ключом поддельное «обновление».
Решение принимается пользователем — пока оставляем.

**⚠️ Нельзя:**
- Удалять/заменять keystore.
- Менять пароли/alias в `build.gradle.kts`.
- Полагаться на `~/.android/debug.keystore`.

---

## Известные подводные камни (НЕ ПЕРЕДЕЛЫВАТЬ)

1. **MenuAnchorType** — нет в BOM 2024.02.00. Только `.menuAnchor()`.
2. **Gradle 9.x** несовместим с AGP 8.2.2. Только 8.6.
3. **Configuration cache** отключён.
4. **Wake-up**: если дорожка спит, нужно нажать физическую кнопку.
5. **`stop()`** использует `08 01`, а не `02 00 00`.
6. В `TreadmillSession` **нельзя** `viewModelScope` — только свой scope.
7. **`BleConnection.write()`** — старая версия без ACK. Везде `writeSuspend()`.
8. При переходе на новый сегмент — **ОДИН** `copy()` в `WorkoutManager.launchLoop`.
9. **`startJob` / `autoReconnectJob`** отменяются при Stop/Pause/ручном подключении.
10. **Кулдаун сканирования** — 5 сек после ручного отключения.
11. **`%.Nf` в strings.xml**: нельзя `"$str %.1f".format(...)`, если в `str` есть `%`.
12. **`stringResource` в Canvas**: нельзя @Composable внутри `Canvas`. Вычисляй до.
13. **Android 14+**: `foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
14. **Ручная тренировка** сохраняется как `programName = ""`, отображается через `manual_workout`.
15. **`WorkoutMiniChart`**: `stringResource` нельзя звать внутри `Canvas {}`.
16. **`AppDatabase.closeAndReset()`** — только из `BackupManager.restoreFrom()`.
17. **JSON-программы — snake_case**, double всегда с точкой.
18. **FTMS Control Point Alt (0x2A66)** — это **Cycling Power Control Point**, не FTMS. Сейчас используется как fallback, но если встретится велотренажёр с этим UUID, уйдут команды в чужой сервис. Кандидат на удаление.
19. **`Switch` в Material3 не уважает `Modifier.size(width, height)`** — использовать `Box + scale(0.7f)`.
20. **`CompletableDeferred<Boolean>` не совместим с `CompletableDeferred<Any?>`** — использовать `CompletableDeferred<Any?>` и приводить результат через `as? Boolean ?: false`.
21. **`setNotify` в `BleConnection`** — старая синхронная версия остаётся для `HeartRateService`, новая `setNotifySuspend` ждёт ACK дескриптора.
22. **Публичный репо + keystore в нём** — любой может подписать поддельный APK. При переходе в Play — переделать на Secrets.

---

## Тесты — статус

### Пройдено (актуально на v2.3.3)
- Все базовые команды BLE, кроме наклона (принимается, но на FS-E629DD мотор не двигается).
- Программы, пауза, стоп, метрики.
- Подпись APK, обновление поверх.
- TTS, AutoHR, профили, генератор, Capabilities.
- HoldToConfirmButton, SegmentLine, уведомление в шторке.
- Обработка потери связи, кривая пульса в БД.
- Локализация RU/EN.
- Connect→disconnect→connect (GATT не залипает).
- AndroidManifest: `foregroundServiceType` на месте.
- Резервная копия: экспорт/восстановление, блокировка при активном сервисе, авто-перезапуск.
- **Импорт JSON-программ**: probe + Test 60 min проходят с snake_case.
- **Сборка через публичный репо**: скомпилировалось без quota error.
- **BLE-разрешения на тапе «Подкл.»**: не блокируют первый запуск, диалог при первом подключении.
- **Debug с двумя switch**: FTMS + UI, оба фильтра работают.
- **About без шаблона**: карточка-подсказка вместо кнопок.
- **Демо не стирает**: добавляет с префиксом (demo), диалог подтверждения.
- **Атомарный restore**: старые `.bak` при сбое возвращаются.

### Не проверено / требует внимания
- Android 12+ и `BLUETOOTH_SCAN` на конкретных прошивках.
- Планшеты и складные.
- Часовая диагностическая тренировка (JSON готов, но ещё не проводилась).

---

## Backlog

### Сделано
- [x] Автопереподключение BLE, TTS, AutoHR, профили, генератор, Capabilities.
- [x] Локализация RU/EN, HoldToConfirm, SegmentLine, уведомление в шторке.
- [x] Кривая пульса в БД, GATT-фикс, Manifest `foregroundServiceType`.
- [x] Резервная копия (SAF), фикс README (snake_case).
- [x] **Батч 1**: демо не стирает, атомарный restore, битый JSON в settings.
- [x] **Батч 1.5**: clamp шаблонов по лимитам, warning на импорт, About без шаблона, Debug UI-фильтр, BLE-разрешения на «Подкл.».
- [x] **FTMS-парсер**: incline 4 байта, elevation gain 4 байта, строгие проверки, prev.inclinePercent.
- [x] **BleConnection**: gattMutex, setNotifySuspend с ожиданием ACK.
- [x] **WorkoutManager**: safeSetSpeed/safeSetIncline (try/catch, но пока без CancellationException).

### В работе / следующий батч
- [ ] **WorkoutManager**: `catch (e: CancellationException) { throw e }` перед общим catch.
- [ ] **Uuids**: убрать или ограничить `FTMS_CONTROL_POINT_ALT = 0x2A66` проверкой сервиса 0x1826.
- [ ] **TreadmillSession**: средний пульс и средняя скорость — по таймеру (1 Гц), а не по StateFlow.
- [ ] **TreadmillSession**: `speedBeforePause` сбрасывать при старте новой программы.
- [ ] **TreadmillSession**: `_hrHistory` не обрезать до 30 минут (для тренировок > 30 мин теряется начало).
- [ ] **TreadmillSession**: `coerceIn(minS, maxS)` — проверить `minS <= maxS` перед вызовом.
- [ ] **Автопереподключение после первого реконнекта** — проверить код, есть подозрение, что второй обрыв не обрабатывается.

### Отложено / идеи
- [ ] Экспорт TCX (для Strava/Garmin) — приоритет.
- [ ] Экспорт FIT (нужна сторонняя библиотека).
- [ ] Onboarding при первом запуске.
- [ ] Виджет на рабочий стол.
- [ ] Достижения / медали.
- [ ] S-curve ramp (плавный разгон скорости).
- [ ] Единый актор/канал команд к дорожке (`TreadmillSession` — командный контроллер).
- [ ] Отрицательный наклон (для дорожек с уклоном вниз).
- [ ] Дистанция с дорожки (`FTMS Treadmill Data`) вместо интегрирования.
- [ ] Подписка на `FTMS_STATUS (0x2ADA)` для событий остановки/ошибки.
- [ ] Логирование в release: уровни, маска MAC, ограничение размера файла.
- [ ] Keystore → GitHub Secrets (при публикации в Play).
- [ ] Тесты: `FTMSProtocol.parseTreadmillData`, `ProgramGenerator`, `BackupManager`, `HrSamplesCodec`, `SettingsStore`.
- [ ] Локализация русского текста в `TreadmillService` и логах BLE.
- [ ] `ProgramRepository.toData()` — валидация при парсинге.
- [ ] `importJson()` — валидация сегментов (отрицательная длительность, пустой список).
- [ ] `ProgramGenerator`: `durationMin < 6` даёт отрицательный `mainMin`; `heightCm == 0` даёт деление на 0.
- [ ] Адаптация под планшеты и складные.
- [ ] Actions pinned to SHA (supply chain).
- [ ] Отдельный release workflow (не на каждый push в main).

---

## Changelog

### v2.3.3 (баг-фиксы + UX)

**Батч 1 (безопасность данных)**
- `ProgramRepository.addDemoLogs()`: больше **не стирает** существующие логи, а добавляет с префиксом `(demo)`.
- `ProgramViewModel.seedDemoLogs()` → `addDemoLogs()`.
- `HistoryScreen`: диалог подтверждения перед загрузкой демо.
- `BackupManager.restoreFrom()`: атомарное восстановление через `rename` в `.bak` + откат при ошибке.
- `SettingsStore.load()`: битый JSON сохраняется в `settings_json_broken_<ts>`, профили не теряются.

**Батч 1.5 (UX)**
- `ProgramsScreen.buildTemplates`: clamp по `effectiveMaxSpeed` / `effectiveMaxIncline`. Раньше шаблон «Интервалы» создавал сегменты 15.0 км/ч при max = 12.
- `ProgramsScreen.tryImportText`: warning `programs_import_overflow`, если импорт превышает лимиты (не блокирует).
- `AboutScreen`: убраны кнопки «Шаблон» / «Инструкция», карточка сокращена до подсказки.
- `DebugScreen`: второй switch «UI», компактный (`Box + scale(0.7f)`), `import androidx.compose.ui.draw.scale`.
- `MainScreen`: BLE-разрешения запрашиваются **при тапе «Подкл.»**, а не на старте. Добавлен `ble_permission_denied`.

**BLE-парсер**
- `FTMSProtocol.parseTreadmillData`: `Inclination` = 4 байта (Inclination + Ramp Angle), `Elevation Gain` = 4 байта, все чтения защищены `o + size <= b.size` с `return` при нехватке. При отсутствии флага incline — сохраняется `prev.inclinePercent`, а не 0.0.

**BleConnection**
- `gattMutex: Mutex` для сериализации всех GATT-операций.
- Один `activeDeferred: CompletableDeferred<Any?>` вместо Map по UUID.
- Новая `setNotifySuspend()` ждёт `onDescriptorWrite` с таймаутом 2 сек.
- Старая `setNotify(): Boolean` оставлена для `HeartRateService` (fire-and-forget, но возвращает `true`).

**WorkoutManager**
- `safeSetSpeed` / `safeSetIncline` с try/catch — исключение команды не убивает `launchLoop`, тренировка ставится на паузу.

**Сборка**
- Публичный репо → без лимитов Actions.
- `upload-artifact`: `retention-days: 1`, `compression-level: 0`.
- Основное место раздачи APK — GitHub Releases.

### v2.3.2 (формат JSON)
- README/NOTES исправлены на snake_case.
- `FTMS_CONTROL_POINT_ALT` задокументирован как Cycling Power Control Point.

### v2.3.1 (резервное копирование)
- BackupManager: экспорт/импорт ZIP через SAF.
- AppDatabase.closeAndReset().
- SettingsScreen: секция «Данные».
- Manifest: удалён `BLUETOOTH_ADVERTISE`.

### v2.3 (локализация + итерации 1–5)
- Полная локализация RU/EN.
- HoldToConfirmButton, SegmentLine, кривая пульса в БД, уведомление в шторке, обработка потери связи, GATT-фикс.

### v2.2 (TTS / AutoHR / Профили / Генератор / Capabilities)
- TTS с редактируемыми шаблонами.
- AutoHR (цикл в TreadmillSession).
- Профили пользователей.
- Генератор тренировок.
- TreadmillCapabilities.
- Графики пульса на Canvas.
- SOS-защита BLE.

### v2.1 (UI & BLE Fixes)
- UI-редизайн Программ и Редактора.
- write() → writeSuspend().
- DebugLog.

### v2.0+ (CI fix)
- Keystore в репо, signingConfigs.create("stable").
- .gitignore.

### v2.0+
- TreadmillSession + TreadmillService.
- `08 01` для остановки.
- Автопульс и зоны Карвонена.
- Исправления: сохранение ручных тренировок, пропуск сегментов, скорость после паузы, утечка GATT.

---

## Для ИИ-ассистента (краткая шпаргалка)

Раздел для следующего ИИ в новом чате. Прочитай **до** любых предложений.

### Стек и запреты
- Kotlin 1.9.22, Compose BOM 2024.02.00 (MenuAnchorType **нет**, только `.menuAnchor()`), Room 2.6.1 (БД v2), Gradle 8.6 + AGP 8.2.2 (**Gradle 9.x нельзя**).
- `TreadmillSession`: только свой `CoroutineScope`, `viewModelScope` **нельзя**.
- `BleConnection.write()` — старая, без ACK. Везде `writeSuspend()`.
- `stringResource` **нельзя** внутри `Canvas {}`.
- `"%.Nf".format(...)` на строке с `%` в конце — опасно.
- Keystore `app/keystore/treadmill.keystore` — **не трогать** (в публичном репо — см. риск в подводных камнях).
- `Switch` в Material3 не уважает `Modifier.size(width, height)` — использовать `Box + Modifier.scale(0.7f)`.
- `CompletableDeferred<T1>` не совместим с `CompletableDeferred<Any?>` — использовать `CompletableDeferred<Any?>` + `as? T ?: fallback`.

### Формат JSON-программ — snake_case
`duration_sec`, `speed_kmh`, `incline_percent`. НЕ camelCase.
Double **всегда с точкой** (`5.0`, `0.0`). Эталон: «Программы → ⋮ → Экспорт JSON».

### BLE-протокол belt'а (коротко)
- Старт: `00` → 200 мс → `07` → `02 XX XX`.
- Скорость: `02 XX XX` (км/ч × 100 LE). Наклон: `03 XX XX` (% × 10 LE).
- Стоп: **`08 01`** (сбрасывает таймер). `02 00 00` — не использовать.
- FTMS Control Point Alt `0x2A66` — **Cycling Power Control Point**, не FTMS.
- Inclination в Treadmill Data = **4 байта** (не 2). Elevation Gain = 4 байта. Total Energy = 5 байт.

### Перед предложением кода — проверь
- **Не доверяй README/NOTES про формат JSON** — они уже врали. Проверь `ProgramEntity.kt` или попроси экспорт.
- **Не давай «воздушные» интерфейсы** — дизайн плотный.
- **Полные файлы, не куски** — пользователь работает в веб-редакторе GitHub (Ctrl+A → вставить).
- **Кавычки в XML** — прямые `"`, не smart.
- **Markdown-файлы** — не оборачивай в ` ``` `, если внутри уже есть ` ``` `. Используй отступ в 4 пробела.

### Что уже сделано (не предлагать заново)
TTS, AutoHR, профили, генератор, Capabilities, точные зоны пульса (Room 1→2), HoldToConfirm, уведомление в шторке, локализация RU/EN, backup ZIP (SAF, атомарный), фикс GATT-залипания, `foregroundServiceType`, snake_case JSON, clamp шаблонов, warning на импорт, About без шаблона, Debug с двумя switch, BLE-разрешения на «Подкл.», FTMS-парсер (incline 4 байта), gattMutex + setNotifySuspend, safeSetSpeed/Incline.

### Что в бэклоге (приоритет сверху вниз)
1. `WorkoutManager`: `catch (e: CancellationException) { throw e }`.
2. `Uuids`: убрать/ограничить `FTMS_CONTROL_POINT_ALT = 0x2A66`.
3. `TreadmillSession`: средний пульс/скорость — по таймеру (1 Гц), а не StateFlow.
4. `TreadmillSession`: `speedBeforePause` сброс при старте.
5. `TreadmillSession`: HR history не обрезать до 30 мин.
6. `coerceIn(minS, maxS)` — проверка `minS <= maxS`.
7. Автопереподключение после первого реконнекта.
8. Экспорт TCX.
9. Единый актор/канал команд.
10. Дистанция с дорожки (Treadmill Data).
11. FTMS_STATUS подписка.

### Как получить эталон
- JSON-программа: «Программы → ⋮ → Экспорт JSON».
- Backup ZIP: «Настройки → Данные → Экспорт резервной копии».
- BLE-лог: «Debug → 💾 Сохранить».
