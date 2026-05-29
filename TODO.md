# TODO: Автовыбор прокси по ключевым словам + автопинг

## Аудит репозитория — что уже реализовано

### Управление сетью
| Компонент | Файл | Описание |
|---|---|---|
| `SagerNet.reloadNetwork()` | `SagerNet.kt:263` | **Уже** определяет тип сети через `TRANSPORT_*` → строки `"wifi"`, `"data"`, `"bluetooth"`, `"ethernet"`, `"usb"`, `"satellite"`. Вызывается из `VpnService.preInit()` и `ProxyService.preInit()` при каждом `DefaultNetworkListener` событии |
| `DefaultNetworkListener` | `utils/DefaultNetworkListener.kt` | `NetworkCallback` — уже подписан в `VpnService` и `ProxyService`, рассылает смены сети. Хранит `ssid` (Android 12+) |
| `SagerNet.currentNetwork` | `SagerNet.kt:260` | Текущий объект `Network` |

### Управление сервисом
| Метод | Реализация |
|---|---|
| `SagerNet.startService()` | `ContextCompat.startForegroundService(...)` |
| `SagerNet.reloadService()` | `sendBroadcast(Action.RELOAD)` → `forceLoad()` → если запущен — `stopRunner(restart=true)`, если остановлен — `startRunner()` |
| `SagerNet.stopService()` | `sendBroadcast(Action.CLOSE)` |
| `SagerNet.started` | `Boolean` — флаг активности сервиса |

### Автозапуск при загрузке
`BootReceiver` — **уже реализован**. Запускает сервис при загрузке устройства, если `DataStore.persistAcrossReboot == true` и `DataStore.currentProfile > 0`. Управляется через `global_preferences.xml` ключ `"isAutoConnect"` (строка `"Auto connect"`).

### Тестирование задержки
| Компонент | Файл | Описание |
|---|---|---|
| `V2RayTestInstance.doTest()` | `bg/test/V2RayTestInstance.kt:43` | Пингует один профиль через `Libexclavecore.urlTest()`. Возвращает `Int` (мс) или бросает исключение |
| `DataStore.connectionTestURL` | `DataStore.kt:268` | URL теста (`https://www.google.com/generate_204`), уже конфигурируется пользователем |
| `ProxyEntity.ping` | `ProxyEntity.kt:86` | Поле задержки (мс), `status` (0=не тестировался, 1=OK, 3=недоступен) |
| Параллельный пинг в UI | `ConfigurationFragment.kt:788` | `urlTest()` — 6 воркеров, тот же `V2RayTestInstance`. Шаблон для переиспользования |

### Фильтрация профилей по имени
| Компонент | Файл | Описание |
|---|---|---|
| `SubscriptionBean.nameFilter` | `SubscriptionBean.java:45` | **Уже есть** — regex-фильтр исключения (exclude) при обновлении подписки |
| `SubscriptionBean.nameFilter1` | `SubscriptionBean.java:46` | **Уже есть** — regex-фильтр включения (include) при обновлении подписки |
| Поиск в UI | `ConfigurationFragment.kt:1276` | `displayName().lowercase().contains(lower) \|\| displayType().lowercase().contains(lower)` — поиск по имени и типу |

### Хранилище настроек
| Поле | Файл | Описание |
|---|---|---|
| `DataStore.selectedProxy` | `DataStore.kt:84` | ID текущего выбранного профиля (`Key.PROFILE_ID`) |
| `DataStore.selectedGroup` | `DataStore.kt:88` | ID текущей группы |
| `DataStore.currentGroup()` | `DataStore.kt:106` | Возвращает текущую `ProxyGroup` |
| `SagerDatabase.proxyDao.getByGroup(id)` | `ProxyEntity.kt:534` | Все профили группы |
| `SagerDatabase.proxyDao.updateProxy(proxy)` | `ProxyEntity.kt:568` | Сохранить изменения профиля |

---

## Что нужно реализовать

Из анализа выше:
- Определение типа сети (`SagerNet.reloadNetwork`) — **готово**, нужно только вынести текущий тип в доступное поле
- Управление сервисом (`reloadService`, `startService`) — **готово**
- Автозапуск при загрузке (`BootReceiver`, `persistAcrossReboot`) — **готово**
- Тестирование задержки (`V2RayTestInstance.doTest()`) — **готово**
- Фильтрация по имени в runtime — **нужно реализовать** (существующий `nameFilter` работает только при обновлении подписки, не при выборе активного профиля)
- Ключевые слова для Wi-Fi/Mobile, автовыбор, автопинг — **нужно реализовать**

---

## Требования

- Из **одной подписки** — разные наборы прокси для Wi-Fi и мобильной сети через **ключевые слова**
- Слова проверяются по `displayName()` (имя профиля) и `displayType()` (протокол, например `VLESS / TCP / REALITY`)
- Если ключевые слова заданы **только для одного** типа сети → для другого типа автоматически используются **все профили, кроме тех, что попали в первый список** (взаимное исключение)
- Если ключевые слова заданы **для обоих** — каждый список независим
- Если ключевые слова **не заданы ни для одного** — используются все профили
- Перед подключением — **автопинг** кандидатов (опционально)
- Автоматический выбор **лучшего по пингу** при смене типа сети

---

## Пример логики взаимного исключения

```
wifiKeywords   = "Армения, Латвия, Gaming"   (заданы)
mobileKeywords = ""                           (не задано)

Профили в подписке:
  ⚡ LTE Россия, ⚡ LTE Россия 2, ⚡ LTE Европа 1   → НЕ содержат wifi-слов
  Армения, Латвия, Швеция • Gaming               → содержат wifi-слова

При Wi-Fi   → кандидаты: [Армения, Латвия, Швеция • Gaming]
При Mobile  → mobileKeywords пуст → кандидаты: ВСЕ минус wifi-список
               = [⚡ LTE Россия, ⚡ LTE Россия 2, ⚡ LTE Европа 1]
```

---

## Задачи

---

### 1. Добавить `currentNetworkType` в `SagerNet`

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt`

В `reloadNetwork()` уже вычисляется строка типа сети и передаётся в `Libexclavecore.setNetworkType()`. Нужно параллельно сохранить её в доступное поле:

```kotlin
// Добавить companion object поле:
@Volatile
var currentNetworkType: String = ""   // "wifi", "data", "bluetooth", "ethernet", "usb", "satellite", ""

// В reloadNetwork(), после вычисления networkType:
currentNetworkType = networkType
// (строка уже вычисляется в существующем when-блоке)
```

> Минимальное изменение существующего кода. `currentNetworkType` используется теми же строками, что уже идут в `setNetworkType()` и `RuleEntity.networkType`.

---

### 2. Константы

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/Constants.kt`

```kotlin
// Добавить в object Key:
const val AUTO_SELECT_BY_NETWORK    = "autoSelectByNetwork"
const val WIFI_KEYWORDS             = "wifiKeywords"
const val MOBILE_KEYWORDS           = "mobileKeywords"
const val AUTO_PING_BEFORE_CONNECT  = "autoPingBeforeConnect"
const val AUTO_PING_TIMEOUT         = "autoPingTimeout"       // мс, default 3000
const val AUTO_PING_CONCURRENCY     = "autoPingConcurrency"   // параллельность, default 4
```

> `AUTO_CONNECT` и `PERSIST_ACROSS_REBOOT` уже реализованы (`Key.PERSIST_ACROSS_REBOOT = "isAutoConnect"`). Новый ключ для auto-select — отдельный, не перекрывает существующий.

---

### 3. Поля в `DataStore`

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt`

```kotlin
var autoSelectByNetwork   by configurationStore.boolean(Key.AUTO_SELECT_BY_NETWORK)
var wifiKeywords          by configurationStore.string(Key.WIFI_KEYWORDS)     // default: ""
var mobileKeywords        by configurationStore.string(Key.MOBILE_KEYWORDS)   // default: ""
var autoPingBeforeConnect by configurationStore.boolean(Key.AUTO_PING_BEFORE_CONNECT)
var autoPingTimeout       by configurationStore.int(Key.AUTO_PING_TIMEOUT)    // default: 3000
var autoPingConcurrency   by configurationStore.int(Key.AUTO_PING_CONCURRENCY)// default: 4
```

> Используется `configurationStore` (не `profileCacheStore`), так как настройки глобальные, не зависят от профиля.

---

### 4. `KeywordFilter` — фильтрация и взаимное исключение

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/KeywordFilter.kt`

```kotlin
object KeywordFilter {

    /** "LTE, При глушении, Россия" → ["lte", "при глушении", "россия"] */
    fun parse(raw: String): List<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** true, если хотя бы одно слово содержится в displayName() или displayType() */
    fun matches(proxy: ProxyEntity, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        val name = proxy.displayName().lowercase()
        val type = proxy.displayType().lowercase()
        return keywords.any { kw -> name.contains(kw) || type.contains(kw) }
    }

    /**
     * Возвращает кандидатов для данного типа сети.
     *
     * Логика взаимного исключения:
     *  - Оба пусты          → все профили
     *  - Оба заданы         → независимые фильтры
     *  - Только current задан → фильтр по current
     *  - Только other задан   → все профили КРОМЕ тех, что совпадают с other
     */
    fun candidates(
        all: List<ProxyEntity>,
        currentRaw: String,
        otherRaw: String,
    ): List<ProxyEntity> {
        val currentKw = parse(currentRaw)
        val otherKw   = parse(otherRaw)

        return when {
            currentKw.isEmpty() && otherKw.isEmpty() -> all
            currentKw.isNotEmpty()                   -> all.filter { matches(it, currentKw) }
            else /* currentKw пуст, other задан */   -> all.filterNot { matches(it, otherKw) }
        }
    }
}
```

---

### 5. `NetworkProxyPinger` — параллельный пинг кандидатов

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkProxyPinger.kt`

```kotlin
object NetworkProxyPinger {

    /**
     * Пингует [candidates] параллельно через V2RayTestInstance (уже существует).
     * Возвращает список, отсортированный по задержке (лучший первый).
     * Недоступные — в конце.
     */
    suspend fun pingAndRank(
        candidates: List<ProxyEntity>,
        testUrl: String  = DataStore.connectionTestURL,   // уже в DataStore
        timeoutMs: Int   = DataStore.autoPingTimeout.takeIf { it > 0 } ?: 3000,
        concurrency: Int = DataStore.autoPingConcurrency.takeIf { it > 0 } ?: 4,
    ): List<ProxyEntity> = coroutineScope {
        val results = ConcurrentHashMap<Long, Int>()
        val semaphore = Semaphore(concurrency)

        candidates.map { proxy ->
            launch {
                semaphore.withPermit {
                    val ping = try {
                        // V2RayTestInstance и doTest() уже реализованы
                        V2RayTestInstance(proxy, testUrl, timeoutMs).use { it.doTest() }
                    } catch (_: Exception) {
                        Int.MAX_VALUE
                    }
                    results[proxy.id] = ping
                    proxy.ping   = if (ping == Int.MAX_VALUE) 0 else ping
                    proxy.status = if (ping == Int.MAX_VALUE) 3 else 1
                    SagerDatabase.proxyDao.updateProxy(proxy)   // уже существует
                }
            }
        }.joinAll()

        candidates.sortedBy { results[it.id] ?: Int.MAX_VALUE }
    }
}
```

---

### 6. `NetworkAwareSelector` — основная логика

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkAwareSelector.kt`

```kotlin
object NetworkAwareSelector {

    /**
     * Вызывается из VpnService.preInit() / ProxyService.preInit()
     * ПОСЛЕ существующего вызова SagerNet.reloadNetwork(it).
     *
     * Встраивается в уже существующий DefaultNetworkListener.start() callback —
     * не создаёт отдельного listener'а.
     */
    suspend fun onNetworkChanged(network: Network?) {
        if (!DataStore.autoSelectByNetwork) return

        if (network == null) return   // потеря сети — не меняем профиль

        val netType = SagerNet.currentNetworkType   // "wifi" или "data" (п.1)
        if (netType != "wifi" && netType != "data") return

        val allProxies = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroup().id)
        if (allProxies.isEmpty()) return

        val isWifi = netType == "wifi"
        val candidates = KeywordFilter.candidates(
            all        = allProxies,
            currentRaw = if (isWifi) DataStore.wifiKeywords else DataStore.mobileKeywords,
            otherRaw   = if (isWifi) DataStore.mobileKeywords else DataStore.wifiKeywords,
        )

        if (candidates.isEmpty()) {
            Logs.w("NetworkAwareSelector: no candidates for netType=$netType")
            return
        }

        // Пинг или сортировка по сохранённому ping
        val ranked = if (DataStore.autoPingBeforeConnect) {
            NetworkProxyPinger.pingAndRank(candidates)
        } else {
            candidates.sortedWith(compareBy {
                if (it.status == 1 && it.ping > 0) it.ping else Int.MAX_VALUE
            })
        }

        val best = ranked.firstOrNull { it.status != 3 } ?: run {
            Logs.w("NetworkAwareSelector: all candidates unreachable for netType=$netType")
            return
        }

        if (DataStore.selectedProxy == best.id) {
            Logs.d("NetworkAwareSelector: already on best proxy '${best.displayName()}'")
            return
        }

        DataStore.selectedProxy = best.id
        Logs.i("NetworkAwareSelector: → '${best.displayName()}' ping=${best.ping}ms netType=$netType")

        // reloadService() уже реализован в SagerNet: sendBroadcast(Action.RELOAD)
        // → BaseService.forceLoad() → stopRunner(restart=true) или startRunner()
        SagerNet.reloadService()
    }
}
```

---

### 7. Интеграция в существующий network callback

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt`

Существующий код (строки 166–170):
```kotlin
DefaultNetworkListener.start(this) {
    if (networkListenerIsRunning) {
        underlyingNetwork = it
        SagerNet.reloadNetwork(it)   // ← уже есть
    }
}
```

Изменение — добавить одну строку после `reloadNetwork`:
```kotlin
DefaultNetworkListener.start(this) {
    if (networkListenerIsRunning) {
        underlyingNetwork = it
        SagerNet.reloadNetwork(it)
        runOnDefaultDispatcher { NetworkAwareSelector.onNetworkChanged(it) }  // NEW
    }
}
```

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/ProxyService.kt`

Аналогично, строки 49–52:
```kotlin
DefaultNetworkListener.start(this) {
    if (networkListenerIsRunning) {
        SagerNet.reloadNetwork(it)
        underlyingNetwork = it
        runOnDefaultDispatcher { NetworkAwareSelector.onNetworkChanged(it) }  // NEW
    }
}
```

> **Не создаём отдельный `DefaultNetworkListener.start()`** — переиспользуем существующий callback в обоих сервисах.

---

### 8. Автовыбор при первом запуске сервиса

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt`

В `startRunner()` / `startProcesses()` перед запуском ядра, если `autoSelectByNetwork` включён, вызвать `onNetworkChanged` с текущей сетью:

```kotlin
// В suspend fun startProcesses(), перед data.proxy!!.launch():
if (DataStore.autoSelectByNetwork) {
    NetworkAwareSelector.onNetworkChanged(SagerNet.currentNetwork)
}
```

Это гарантирует выбор правильного профиля при старте сервиса, даже если сеть не менялась.

---

### 9. `BootReceiver` — не требует изменений

`BootReceiver` уже реализован и управляется через `DataStore.persistAcrossReboot` (`"isAutoConnect"` в UI). При загрузке он запускает сервис, `startProcesses()` (п.8) подхватит нужный профиль по типу текущей сети.

---

### 10. UI — новые настройки

**Файл:** `app/src/main/res/xml/global_preferences.xml`

Добавить секцию после `<PreferenceCategory app:title="@string/general_settings">`:

```xml
<PreferenceCategory app:title="@string/auto_select_category">

    <SwitchPreference
        app:key="autoSelectByNetwork"
        app:title="@string/auto_select_by_network"
        app:summary="@string/auto_select_by_network_summary"
        app:icon="@drawable/ic_baseline_compare_arrows_24" />

    <EditTextPreference
        app:key="wifiKeywords"
        app:title="@string/wifi_keywords"
        app:summary="@string/wifi_keywords_hint"
        app:icon="@drawable/baseline_wifi_lock_24"
        app:dependency="autoSelectByNetwork"
        app:useSimpleSummaryProvider="true" />

    <EditTextPreference
        app:key="mobileKeywords"
        app:title="@string/mobile_keywords"
        app:summary="@string/mobile_keywords_hint"
        app:icon="@drawable/ic_baseline_airplanemode_active_24"
        app:dependency="autoSelectByNetwork"
        app:useSimpleSummaryProvider="true" />

    <SwitchPreference
        app:key="autoPingBeforeConnect"
        app:title="@string/auto_ping_before_connect"
        app:summary="@string/auto_ping_before_connect_summary"
        app:icon="@drawable/ic_baseline_speed_24"
        app:dependency="autoSelectByNetwork" />

    <EditTextPreference
        app:key="autoPingTimeout"
        app:title="@string/auto_ping_timeout"
        app:dependency="autoPingBeforeConnect"
        app:icon="@drawable/ic_baseline_timelapse_24"
        app:inputType="number"
        app:useSimpleSummaryProvider="true" />

    <EditTextPreference
        app:key="autoPingConcurrency"
        app:title="@string/auto_ping_concurrency"
        app:dependency="autoPingBeforeConnect"
        app:icon="@drawable/ic_baseline_multiple_stop_24"
        app:inputType="number"
        app:useSimpleSummaryProvider="true" />

</PreferenceCategory>
```

> Иконки переиспользованы из уже существующих в `res/drawable/`.

**Файл:** `app/src/main/res/values/strings.xml`

```xml
<string name="auto_select_category">Auto proxy selection</string>
<string name="auto_select_by_network">Auto-select proxy by network type</string>
<string name="auto_select_by_network_summary">Automatically switch proxy when changing between Wi-Fi and mobile network</string>
<string name="wifi_keywords">Keywords for Wi-Fi</string>
<string name="wifi_keywords_hint">Comma-separated words to match proxy name. Example: Армения, Латвия, Gaming. Leave empty to use all proxies not matched by mobile keywords.</string>
<string name="mobile_keywords">Keywords for mobile network</string>
<string name="mobile_keywords_hint">Comma-separated words to match proxy name. Example: LTE, При глушении. Leave empty to use all proxies not matched by Wi-Fi keywords.</string>
<string name="auto_ping_before_connect">Ping before connecting</string>
<string name="auto_ping_before_connect_summary">Test latency of candidate proxies and pick the fastest</string>
<string name="auto_ping_timeout">Ping timeout (ms)</string>
<string name="auto_ping_concurrency">Ping concurrency</string>
```

---

### 11. Граничные случаи

| Ситуация | Поведение |
|---|---|
| Оба поля пустые | `KeywordFilter.candidates()` → все профили |
| Задано только `wifiKeywords` | Wi-Fi: по словам; Mobile: все кроме совпавших с wifi-словами |
| Задано только `mobileKeywords` | Mobile: по словам; Wi-Fi: все кроме совпавших с mobile-словами |
| Заданы оба | Независимые фильтры для каждого типа |
| Кандидатов нет | Логировать предупреждение, не менять профиль |
| Все кандидаты недоступны (ping fail) | Не менять профиль |
| `selectedProxy` уже оптимальный | Не вызывать `reloadService()` |
| `autoPingBeforeConnect = false` | Сортировка по сохранённому `ping`; без нового теста |
| Сеть не Wi-Fi и не Cellular | `NetworkAwareSelector.onNetworkChanged()` выходит без действий |
| Сеть пропала (network = null) | Не трогать профиль; `BootReceiver` и `persistAcrossReboot` существуют для восстановления |

---

### 12. Полный UX-флоу

```
Подписка содержит:
  ⚡ LTE Россия          ← displayName
  ⚡ LTE Россия 2
  ⚡ LTE Европа 1
  Армения                ← VLESS / TCP / REALITY | JSON
  Латвия
  Швеция • Gaming 🎮

Настройки:
  autoSelectByNetwork    = true
  wifiKeywords           = "Армения, Латвия, Gaming"
  mobileKeywords         = ""   ← не задано
  autoPingBeforeConnect  = true

── При переключении Wi-Fi → Mobile ──────────────────────────
1. VpnService DefaultNetworkListener callback вызывает SagerNet.reloadNetwork()
   → SagerNet.currentNetworkType = "data"
2. NetworkAwareSelector.onNetworkChanged()
   → netType="data", currentRaw="", otherRaw="Армения, Латвия, Gaming"
   → KeywordFilter.candidates(): mobileKeywords пуст → all minus wifi-matches
   → кандидаты: [⚡ LTE Россия, ⚡ LTE Россия 2, ⚡ LTE Европа 1]
3. NetworkProxyPinger.pingAndRank():
     ⚡ LTE Россия 2  →  54 ms ✓
     ⚡ LTE Россия    →  91 ms ✓
     ⚡ LTE Европа 1  → 310 ms ✓
4. DataStore.selectedProxy = "⚡ LTE Россия 2".id
5. SagerNet.reloadService() → Action.RELOAD → BaseService.forceLoad()
   → stopRunner(restart=true) → startRunner()
```

---

### 13. Новые файлы и изменения

| Действие | Файл | Изменение |
|---|---|---|
| ИЗМЕНИТЬ | `SagerNet.kt` | Добавить `currentNetworkType: String` в companion object; заполнять в `reloadNetwork()` |
| ИЗМЕНИТЬ | `Constants.kt` | 6 новых ключей в `object Key` |
| ИЗМЕНИТЬ | `DataStore.kt` | 6 новых полей |
| НОВЫЙ | `bg/KeywordFilter.kt` | Фильтрация + взаимное исключение |
| НОВЫЙ | `bg/NetworkProxyPinger.kt` | Параллельный пинг |
| НОВЫЙ | `bg/NetworkAwareSelector.kt` | Основная логика выбора |
| ИЗМЕНИТЬ | `bg/VpnService.kt` | +1 строка в `DefaultNetworkListener.start` callback |
| ИЗМЕНИТЬ | `bg/ProxyService.kt` | +1 строка в `DefaultNetworkListener.start` callback |
| ИЗМЕНИТЬ | `bg/BaseService.kt` | Вызов `NetworkAwareSelector.onNetworkChanged` в `startProcesses()` |
| ИЗМЕНИТЬ | `res/xml/global_preferences.xml` | Новая `PreferenceCategory` |
| ИЗМЕНИТЬ | `res/values/strings.xml` | 10 новых строк |

---

### 14. Тесты

- [ ] **Unit** `KeywordFilter.candidates()`: все 4 комбинации (оба пусты / только wifi / только mobile / оба заданы)
- [ ] **Unit** `KeywordFilter.matches()`: регистронезависимость, совпадение по `displayType`, отсутствие совпадений
- [ ] **Unit** `NetworkProxyPinger`: мок `V2RayTestInstance`, проверить сортировку и обработку ошибок
- [ ] **Unit** `NetworkAwareSelector`: мок `SagerNet.currentNetworkType` и `DataStore`, проверить выбор и вызов `reloadService`
- [ ] **Интеграционный**: смена `currentNetworkType` "wifi" → "data" → проверить `DataStore.selectedProxy`

---

## HWID Device Limit (Remnawave)

### Что такое HWID Device Limit

Remnawave-панель поддерживает ограничение числа устройств по HWID. При включённой опции сервер:
- Возвращает `404` при запросе подписки **без** заголовка `x-hwid`
- Отслеживает уникальные устройства по HWID
- Возвращает диагностические заголовки в ответе

**В репозитории HWID не реализован вообще** — ни получение Device ID, ни хранение, ни отправка заголовков при запросе подписки.

---

### Протокол (по документации Remnawave)

Клиент отправляет при запросе подписки:

```
x-hwid: <уникальный_идентификатор_устройства>   // обязателен
x-device-os: Android                             // опционально
x-ver-os: 14                                     // опционально
x-device-model: Pixel 8 Pro                      // опционально
user-agent: Exclave/1.x.x                        // уже отправляется
```

Сервер отвечает заголовками:

| Заголовок | Значение |
|---|---|
| `x-hwid-active` | `true` — HWID-ограничение включено на сервере |
| `x-hwid-not-supported` | `true` — сервер ждал HWID, но клиент не прислал |
| `x-hwid-max-devices-reached` | `true` — лимит устройств исчерпан |
| `x-hwid-limit` | `true` — лимит достигнут (для обратной совместимости) |

---

### Ограничение текущего API `Libexclavecore`

Объект `newRequest()` из `Libexclavecore.newHttpClient()` предоставляет только:
- `setURL(url)`
- `setUserAgent(ua)`
- `execute()`
- `getHeader(name)` — только на ответе

Метода `setHeader(name, value)` в публичном API **нет**. Это ключевое ограничение — для добавления произвольных заголовков нужно расширить `libexclavecore`.

---

### Задачи

#### HWID-1. Получение/генерация Device ID

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/utils/DeviceId.kt`

```kotlin
object DeviceId {

    private const val PREF_KEY = "deviceHwid"

    /**
     * Возвращает HWID устройства:
     * 1. Если пользователь задал кастомный HWID в настройках — вернуть его
     * 2. Иначе: взять ANDROID_ID (стабилен в рамках одного аккаунта/устройства)
     * 3. Если ANDROID_ID недоступен — сгенерировать случайный UUID и сохранить в DataStore
     */
    fun get(context: Context): String {
        // 1. Кастомный HWID от пользователя
        val custom = DataStore.customHwid
        if (custom.isNotBlank()) return custom.trim()

        // 2. Авто-режим: ANDROID_ID
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            // uuid5 уже реализован в ktx/UUIDs.kt
            return uuid5("exclave-hwid-$androidId")
        }

        // 3. Fallback: стабильный случайный UUID
        var stored = DataStore.generatedHwid
        if (stored.isBlank()) {
            stored = UUID.randomUUID().toString()
            DataStore.generatedHwid = stored
        }
        return stored
    }
}
```

> `uuid5()` уже реализован в `ktx/UUIDs.kt`. `Settings.Secure.ANDROID_ID` — стабильный идентификатор (сбрасывается только при factory reset или смене аккаунта).
> Значение `"9774d56d682e549c"` — известный баг-дефолт на некоторых устройствах.

---

#### HWID-2. Константы и поля `DataStore`

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/Constants.kt`

```kotlin
// В object Key:
const val HWID_ENABLED      = "hwidEnabled"      // отправлять ли HWID при обновлении подписки
const val CUSTOM_HWID       = "customHwid"        // пользовательский HWID (опционально)
const val GENERATED_HWID    = "generatedHwid"     // авто-сгенерированный fallback UUID
```

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt`

```kotlin
var hwidEnabled    by configurationStore.boolean(Key.HWID_ENABLED)   // default: true
var customHwid     by configurationStore.string(Key.CUSTOM_HWID)     // default: ""
var generatedHwid  by configurationStore.string(Key.GENERATED_HWID)  // default: ""
```

---

#### HWID-3. `setHeader()` — уже реализован в ядре

**Файл:** [`ExclaveNetwork/LibExclaveCore/http.go`](https://github.com/ExclaveNetwork/LibExclaveCore/blob/main/http.go), строка 155

Интерфейс `HTTPRequest` (строка 44) явно объявляет метод:

```go
type HTTPRequest interface {
    SetURL(link string) error
    SetMethod(method string)
    SetHeader(key string, value string)   // ← уже есть
    SetUserAgent(userAgent string)
    Execute() (HTTPResponse, error)
    // ...
}
```

Реализация (строка 155):

```go
func (r *httpRequest) SetHeader(key string, value string) {
    r.request.Header.Set(key, value)
}
```

Через gomobile метод доступен в Kotlin как `request.setHeader(key, value)` — аналогично уже используемому `setUserAgent()`.
**Никаких изменений в ядре не требуется.**

---

#### HWID-4. Отправка заголовков при запросе подписки

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt`

`setHeader()` доступен без каких-либо изменений ядра. Нужно только дополнить блок построения запроса:

```kotlin
// Существующий код:
}.newRequest().apply {
    setURL(subscription.link)
    if (subscription.customUserAgent.isNotEmpty()) {
        setUserAgent(subscription.customUserAgent)
    } else {
        setUserAgent(USER_AGENT)
    }
    // NEW: добавить HWID-заголовки если включено
    if (DataStore.hwidEnabled) {
        val hwid = DeviceId.get(app)
        setHeader("x-hwid", hwid)
        setHeader("x-device-os", "Android")
        setHeader("x-ver-os", Build.VERSION.RELEASE)
        setHeader("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}.execute()
```

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/group/SIP008Updater.kt`

Аналогичное изменение — тот же блок `newRequest().apply { ... }` в строках 58–65.

---

#### HWID-5. Обработка ответных заголовков

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt`

После `response = ...execute()` добавить чтение HWID-заголовков сервера:

```kotlin
// Уже читается: response.getHeader("Subscription-Userinfo")
// Добавить:
val hwidActive         = response.getHeader("x-hwid-active") == "true"
val hwidNotSupported   = response.getHeader("x-hwid-not-supported") == "true"
val hwidLimitReached   = response.getHeader("x-hwid-max-devices-reached") == "true"
    || response.getHeader("x-hwid-limit") == "true"

if (hwidNotSupported) {
    // Сервер ждал HWID, но не получил — уведомить пользователя
    userInterface?.onError(proxyGroup, Exception(app.getString(R.string.hwid_not_supported)))
    return
}
if (hwidLimitReached) {
    // Достигнут лимит устройств — уведомить пользователя
    userInterface?.onError(proxyGroup, Exception(app.getString(R.string.hwid_limit_reached)))
    return
}
```

Новые строки в `strings.xml`:

```xml
<string name="hwid_not_supported">Server requires HWID authentication. Enable HWID in settings.</string>
<string name="hwid_limit_reached">Device limit reached. Remove unused devices in your subscription panel.</string>
```

---

#### HWID-6. UI настроек

**Файл:** `app/src/main/res/xml/global_preferences.xml`

Добавить новую секцию:

```xml
<PreferenceCategory app:title="@string/hwid_category">

    <SwitchPreference
        app:key="hwidEnabled"
        app:title="@string/hwid_enabled"
        app:summary="@string/hwid_enabled_summary"
        app:defaultValue="true" />

    <!-- Показывает текущий авто-HWID (read-only) -->
    <Preference
        app:key="hwidCurrent"
        app:title="@string/hwid_current"
        app:dependency="hwidEnabled" />

    <!-- Кастомный HWID — опционально -->
    <EditTextPreference
        app:key="customHwid"
        app:title="@string/hwid_custom"
        app:summary="@string/hwid_custom_summary"
        app:dependency="hwidEnabled"
        app:useSimpleSummaryProvider="true" />

</PreferenceCategory>
```

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt`

В `onCreatePreferences()` привязать `"hwidCurrent"` preference к отображению текущего HWID:

```kotlin
findPreference<Preference>("hwidCurrent")?.apply {
    summary = DeviceId.get(requireContext())
}
```

**Строки** в `strings.xml`:

```xml
<string name="hwid_category">HWID Device Limit</string>
<string name="hwid_enabled">Send HWID on subscription update</string>
<string name="hwid_enabled_summary">Required for subscriptions with device limit (Remnawave)</string>
<string name="hwid_current">Current HWID</string>
<string name="hwid_custom">Custom HWID</string>
<string name="hwid_custom_summary">Override auto-generated HWID. Leave empty to use device ANDROID_ID.</string>
```

---

### Порядок реализации HWID

1. **`Constants.kt`** — добавить 3 ключа (HWID-2)
2. **`DataStore.kt`** — добавить 3 поля (HWID-2)
3. **`DeviceId.kt`** — новый файл (HWID-1)
4. **`RawUpdater.kt`** — отправка заголовков + обработка ответа (HWID-4, HWID-5)
5. **`SIP008Updater.kt`** — отправка заголовков (HWID-4)
6. **`global_preferences.xml`** + **`strings.xml`** — UI (HWID-6)
7. **`SettingsPreferenceFragment.kt`** — привязать `hwidCurrent` (HWID-6)

> `setHeader()` уже доступен в `Libexclavecore` ([`http.go:155`](https://github.com/ExclaveNetwork/LibExclaveCore/blob/main/http.go#L155)) — дополнительных изменений в ядре не требуется.

### Тесты HWID

- [ ] **Unit** `DeviceId.get()`: приоритет кастомного → ANDROID_ID → fallback UUID
- [ ] **Unit** `DeviceId.get()`: стабильность — повторный вызов возвращает тот же ID
- [ ] **Интеграционный**: запрос подписки с mock-сервером — проверить наличие заголовков `x-hwid`, `x-device-os`, `x-ver-os`, `x-device-model`
- [ ] **Интеграционный**: ответ `x-hwid-max-devices-reached: true` → проверить показ ошибки пользователю
