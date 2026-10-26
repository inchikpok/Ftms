=== НАЧАЛО ФАЙЛА NOTES.md ===

# Treadmill Control — состояние проекта

Android-приложение на Kotlin + Compose для управления беговой дорожкой
через Bluetooth Low Energy (FTMS). Совместимо с любой дорожкой, поддерживающей
стандарт FTMS (Fitness Machine Service, Bluetooth SIG): FitShow, Horizon,
Bowflex, Sole, NordicTrack и т.д.

Поддержка нагрудного пульсометра, авторегулировка скорости по пульсу,
голосовые подсказки, профили пользователей, генератор тренировок,
автоопределение возможностей железа, фоновая работа (Foreground Service).

Работает полностью офлайн — разрешение INTERNET намеренно не запрашивается.

---

## Стек

- Язык: Kotlin 1.9.22, JVM target 17
- UI: Jetpack Compose (BOM 2024.02.00, Material 3 БЕЗ MenuAnchorType)
- Архитектура: MVVM + StateFlow + Foreground Service
- База: Room 2.6.1 (SQLite) с поддержкой миграций
- Сериализация: kotlinx.serialization 1.6.3
- BLE: android.bluetooth.le + собственные обёртки
- TTS: android.speech.tts (системный движок, русский язык)
- Сборка: Gradle 8.6, AGP 8.2.2, KSP 1.9.22-1.0.17
- SDK: min 21, target/compile 34
- CI/CD: GitHub Actions (автоматический versionCode)

---

## Структура проекта

    app/src/main/java/com/custom/treadmill/
    ├── TreadmillApp.kt              — Application, ручной DI (включая TtsManager)
    ├── MainActivity.kt              — точка входа, тема из настроек
    ├── DebugLog.kt                  — единый хаб логирования (memory + file)
    │
    ├── ble/
    │   ├── Uuids.kt                 — UUID сервисов и характеристик (FTMS + HR)
    │   ├── BleManager.kt            — сканирование BLE + кулдаун сканирования
    │   ├── BleConnection.kt         — обёртка BluetoothGatt (writeSuspend, refresh)
    │   ├── ITreadmillProtocol.kt    — интерфейс + TreadmillData + capabilities
    │   ├── TreadmillCapabilities.kt — парсер FTMS Feature / Speed/Incline Range
    │   ├── FTMSProtocol.kt          — стандартный FTMS (читает возможности)
    │   ├── TreadmillSession.kt      — ЯДРО: связь, AutoHR, TTS, метрики, реконнект
    │   └── HeartRateService.kt      — чтение пульса (0x180D)
    │
    ├── data/
    │   ├── database/                — Room (AppDatabase, DAO, Entity)
    │   └── repository/
    │       ├── ProgramRepository.kt — CRUD, JSON, демо
    │       └── SettingsStore.kt     — SharedPreferences + JSON + профили + TTS
    │
    ├── logic/
    │   ├── WorkoutManager.kt        — программа + пауза/пропуск/onFinish
    │   ├── HeartRateController.kt   — авторегулировка (AutoHR)
    │   ├── HeartRateZoneCalculator.kt — расчет пульсовых зон (Karvonen)
    │   ├── ProgramGenerator.kt      — генератор тренировок (волшебная палочка)
    │   └── TtsManager.kt            — обёртка TextToSpeech
    │
    ├── service/
    │   └── TreadmillService.kt      — Foreground Service (уведомление в шторке)
    │
    └── ui/
        ├── Theme.kt, AppNavHost.kt, Permissions.kt
        ├── components/
        │   ├── HeartRateChart.kt    — чистый Canvas, зоны, градиент, даты
        │   ├── ZoneLegend.kt        — компактная легенда зон Z1-Z5
        │   ├── CapabilitiesDialog.kt — диалог возможностей дорожки
        │   └── RollingText.kt
        ├── screens/
        │   ├── MainScreen.kt, ProgramsScreen.kt
        │   ├── WorkoutScreen.kt, SettingsScreen.kt
        │   ├── HistoryScreen.kt, DebugScreen.kt, AboutScreen.kt
        │   ├── ProfilesScreen.kt    — CRUD профилей спортсмена
        │   └── TtsSettingsScreen.kt — редактор TTS-фраз
        └── viewmodels/MainViewModel.kt, ProgramViewModel.kt

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
   дорожки. Не баг приложения.

---

## Логика работы (TreadmillSession)

Вся BLE-логика вынесена из ViewModel в `TreadmillSession`.

- **Автопереподключение**: если связь теряется, 10 попыток с интервалом 3 сек
  к MAC-адресу. Отменяется при ручном подключении/отключении (`autoReconnectJob`).
- **AutoHR (автопульс)**: цикл каждые `intervalSec` секунд. Если `hrEnabled`
  и пульс выше верхней границы → `-stepKmh`. Если ниже нижней → `+stepKmh`.
  НЕ работает во время программы. Учитывает наклон, вес, ИМТ.
- **TTS**: озвучка старта тренировки, смены сегмента, паузы/продолжения,
  финиша. Шаблоны фраз редактируются в настройках (с тегами).
- **Метрики**: `uiElapsedSec`, `uiDistanceKm`, `uiCalories` (формула MET).
  Вес берётся из активного профиля.
- **Capabilities**: после подключения читает FTMS Feature / Speed Range /
  Incline Range. Если наклон не поддерживается — UI скрывает слайдеры.
- **Foreground Service**: `TreadmillService` держит процесс живым и показывает
  плашку в шторке (Пульс, Скорость, Время).
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

---

## Профили пользователей

`AppSettings.profiles: List<UserProfile>` + `activeProfileId`.

Поля `UserProfile`:
- `id`, `name`
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

`logic/TtsManager.kt` — обёртка `TextToSpeech`, русский язык.

Шаблоны фраз в `AppSettings`:
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

Экран: `ui/screens/TtsSettingsScreen.kt`, маршрут `Routes.TTS`.
Кнопка «Прослушать» под каждым полем — тестовая озвучка с подставными
значениями.

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

---

## Возможности дорожки (Capabilities)

`ble/TreadmillCapabilities.kt` — парсер трёх характеристик FTMS:
- `FTMS Feature` (0x2ACC) — битовая маска поддерживаемых функций
- `Speed Range` (0x2AD4) — min/max/increment скорость
- `Incline Range` (0x2AD5) — min/max/increment наклон

`ui/components/CapabilitiesDialog.kt` — диалог, показывается при первом
подключении и по иконке ℹ️ рядом с именем дорожки.

Если дорожка не поддерживает наклон — все слайдеры наклона скрываются
(MainScreen, WorkoutScreen, ProgramEditor). Если Speed Range уже, чем
настройки пользователя — используются меньшие значения.

---

## Настройки (AppSettings)

- `themeMode`: SYSTEM / LIGHT / DARK
- `keepScreenOn`: не гасить экран во время тренировки
- **TTS**: `ttsEnabled` + 5 шаблонов фраз
- **Профили**: `profiles: List<UserProfile>`, `activeProfileId`
- **Скорость**: minSpeedKmh / maxSpeedKmh (1.0 / 20.0), speedStepKmh
- **Наклон**: maxInclinePercent (15.0)
- **Автопульс**: `hrEnabled` / `hrMode` / `targetHr` / `zoneMin` / `zoneMax` /
  `stepKmh` / `intervalSec` / `thresholdHigh` / `thresholdLow`
- **Max HR**: `autoMaxHr` (Tanaka/Gulati) / `maxHr` (ручной)

---

## UI

### Экран Настроек
Material 3, компактные карточки с иконками. Профиль спортсмена вынесен
в отдельный экран (ссылка «Спортсмены»). Есть разделы:
- Спортсмены
- Внешний вид и звук (+ настройка TTS-фраз)
- Скорость и наклон
- Автопульс
- О приложении (Debug, About)

### График пульса (HeartRateChart)
- Чистый Canvas без встроенных заголовков
- Заголовок и subtitle вынесены в отдельный Row над карточкой
- Ось Y слева, даты X снизу
- 5 цветных полупрозрачных зон (Karvonen)
- Линия пульса с градиентом
- Опциональная линия цели (dashed blue)

### Легенда зон (ZoneLegend)
- Компактная, одна строка
- Формат: `Z1 <123`, `Z2 123-134`, ..., `Z5 >157`

### ZoneStatsCard (в HistoryScreen)
- % времени в каждой зоне на основе средних пульсов тренировок
- Прогресс-бары с цветами зон

### Экран Тренировки
- При «Назад» или «Стоп» → диалог подтверждения
- Экран не гаснет (`FLAG_KEEP_SCREEN_ON`)
- При завершении программы кнопка «Стоп» меняется на «Готово»
- Заголовок графика и сам график разделены горизонтальной линией

### Главный экран
- Прокрутка убрана
- Кулдаун сканирования — кнопка «Подкл.» на 5 сек показывает «Ждите 5с»
- Иконка ℹ️ рядом с именем дорожки → диалог возможностей

### Программы
- Генератор (волшебная палочка) в шапке
- Раскрывающиеся карточки с мини-графиком скорости/наклона
- Редактор с кнопками +/- (авто-повтор при удержании), ввод времени ММ:СС
- Скрытие наклона если дорожка не поддерживает

---

## Сборка

### .github/workflows/build.yml
- Сборка APK при каждом push.
- `versionCode` из `github.run_number` (+10000).
- Шаги `Verify keystore` и `Show APK signature`.

### 🔑 Подпись APK (решено 2026-10-19)

**Проблема:** каждый билд подписывался новым случайным ключом → «Конфликт
пакетов» при установке поверх старого APK.

**Решение:**
- Постоянный файл `app/keystore/treadmill.keystore` в репо
  (пароли `treadmill123`, alias `treadmill`).
- В `app/build.gradle.kts` — явный `signingConfigs.create("stable")`.
- В CI — `Verify keystore` (падает при отсутствии) + `Show APK signature`.

**⚠️ Нельзя:**
- Удалять/заменять `app/keystore/treadmill.keystore`.
- Менять пароли/alias в `build.gradle.kts`.
- Полагаться на `~/.android/debug.keystore`.

**Резервная копия keystore** хранится вне GitHub. Потеря = разовая
переустановка с потерей данных.

---

## Известные подводные камни (НЕ ПЕРЕДЕЛЫВАТЬ)

1. MenuAnchorType — нет в BOM 2024.02.00. Только `.menuAnchor()`.
2. Gradle 9.x несовместим с AGP 8.2.2. Только 8.6.
3. Configuration cache отключен.
4. Wake-up: если дорожка спит, нужно нажать физическую кнопку.
5. `stop()` использует `08 01`, а не `02 00 00`.
6. В `TreadmillSession` нельзя вызывать `viewModelScope`.
   Используется `scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`.
7. `BleConnection.write()` — старая версия, не ждёт ACK.
   Везде в протоколе — `writeSuspend()`.
8. При переходе на новый сегмент всё обновляется ОДНИМ `copy()` в
   `WorkoutManager.launchLoop` — иначе TTS читает старое имя.
9. `startJob` / `autoReconnectJob` ДОЛЖНЫ отменяться при Stop/Pause/ручном
   подключении — иначе спам командами и гонка GATT.
10. Кулдаун сканирования (`BleManager.setScanCooldown`) блокирует
    `startScan` на уровне BLE — защищает от «залипания» FitShow.

---

## Тесты — статус

### Пройдено
- A1, A2, B1-B7 (все базовые команды, кроме наклона — он принимается,
  но не двигается).
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
- **Генератор**: разница между новичком и профи — колоссальная.
- **Capabilities**: UNixFit отдаёт 1.0-20.0 км/ч, 0-15% наклон.
- **SOS-защита**: Stop во время startJob мгновенно прекращает команды.

---

## Backlog

- [x] Автопереподключение BLE (в `TreadmillSession`).
- [x] Починить CI (Keystore).
- [x] TTS голосовые подсказки (с настройкой фраз).
- [x] Автопульс (AutoHR).
- [x] Профили пользователей.
- [x] Генератор тренировок (волшебная палочка).
- [x] Автоопределение возможностей дорожки (FTMS Capabilities).
- [ ] Плавающая emergency stop (кнопка SOS в WorkoutScreen).
- [ ] Экспорт в TCX / FIT (для Strava/Garmin).
- [ ] Резервное копирование БД (ZIP).
- [ ] Onboarding при первом запуске.
- [ ] Мультиязычность (RU + EN).
- [ ] Виджет на рабочий стол.
- [ ] Достижения / медали.
- [ ] Плавный разгон скорости (S-curve ramp).

---

## Changelog

### 2026-10-26 (v2.2 — TTS / AutoHR / Профили / Генератор / Capabilities)
- **TTS**: полностью реализован. `TtsManager`, `TtsSettingsScreen` с
  редактируемыми шаблонами (5 фраз), кнопки «Прослушать», теги `{name}`,
  `{segName}`, `{speed}`, `{speed_text}`, `{angle}`, `{angle_text}`, `{i}`,
  `{n}`, `{dist}`, `{hr}`.
- **AutoHR**: цикл в `TreadmillSession` каждые `intervalSec` секунд,
  `HeartRateController.evaluate()` меняет скорость. Не работает во время
  программы. Логи в `[SESSION] AutoHR: ...`.
- **Профили**: `UserProfile` (вес, рост, возраст, пол, restingHr, уровень),
  `ProfilesScreen` с CRUD. Хранятся в `AppSettings.profiles` +
  `activeProfileId`. Вес из профиля используется для расчёта калорий
  и зон пульса.
- **Генератор тренировок**: `ProgramGenerator.generate()` с параметрами
  цель / длительность / интенсивность. Учитывает ИМТ, уровень, пол,
  реальные лимиты железа. Разница между новичком и профи — колоссальная.
- **Capabilities**: `TreadmillCapabilities` парсит FTMS Feature / Speed
  Range / Incline Range. `CapabilitiesDialog` показывает возможности
  при первом подключении и по иконке ℹ️. UI скрывает слайдеры наклона,
  если железо не поддерживает.
- **Графики пульса**: `HeartRateChart` переписан на чистый Canvas без
  встроенных заголовков. Заголовки над графиком, даты X снизу,
  компактная `ZoneLegend`, `ZoneStatsCard` с % времени в каждой зоне.
- **SOS/стабильность BLE**:
  - `startJob` отменяется при Stop/Pause — не спамит командами.
  - `autoReconnectJob` отменяется при ручном подключении.
  - `BleConnection.disconnect()` → `refresh()` → `close()` с таймаутом
    для быстрого освобождения GATT.
  - `BleManager.setScanCooldown(5s)` — кулдаун сканирования после
    ручного отключения.
- **Универсализация**: убраны упоминания UNixFit/FitShow из AboutScreen.
  Приложение позиционируется как «для любой FTMS-дорожки».

### 2026-10-20 (v2.1 — UI & BLE Fixes)
- **UI Редизайн**: экран «Программы» и «Редактор» полностью переработаны.
  Карточки раскрывающиеся, кнопки +/- с авто-повтором, ввод времени ММ:СС
  (Wheel Picker), компактные поля. Debug сжат, добавлено логирование
  тапов/свайпов и экспорт лога. About обновлён, шаблон тренировки.
- **BLE Фиксы**:
  - `write()` заменён на `writeSuspend()` — ждёт ACK. Решило проблему
    потери команд при быстрой смене сегментов.
  - В `FTMSProtocol` добавлен `sendRaw` и логирование ошибок записи.
  - `setSpeed` / `setIncline` больше не игнорируют команды, если belt
    ещё разгоняется.
- **Тренировки**: прогресс по сегментам, автосохранение в журнал при
  finish, кнопка «Стоп» → «Готово».
- **DebugLog**: единый хаб логирования в файл и память.

### 2026-10-19 (v2.0+ — CI fix)
- **Подпись**: keystore в репо, `signingConfigs.create("stable")`,
  `Verify keystore` + `Show APK signature` в CI.
- `.gitignore` добавлен.

### 2026-10-19 (v2.0+)
- **Архитектура**: `TreadmillSession` + `TreadmillService` (Foreground).
- **Протокол**: `08 01` для остановки (сбрасывает таймер).
- **Автопульс и зоны**: профиль спортсмена, зоны Карвонена на графике.
- **Баги**: сохранение ручных тренировок, пропуск сегментов,
  скорость после паузы, утечка GATT.
- **UI**: без прокрутки главного, подтверждение выхода, Material 3,
  неоновая иконка.
- **Удалено**: протокол FitShow, ручные UUID.

=== КОНЕЦ ФАЙЛА NOTES.md ===
