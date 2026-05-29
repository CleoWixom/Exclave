# TODO: Автовыбор прокси по ключевым словам + автопинг + HWID

---

## Часть 1: Автовыбор прокси по типу сети

### Аудит репозитория — что уже реализовано

#### Управление сетью
| Компонент | Файл | Описание |
|---|---|---|
| `SagerNet.reloadNetwork()` | `SagerNet.kt:263` | Уже определяет тип сети через `TRANSPORT_*` → строки `"wifi"`, `"data"`, `"bluetooth"`, `"ethernet"`, `"usb"`, `"satellite"`. Вызывается из `VpnService.preInit()` и `ProxyService.preInit()` при каждом событии `DefaultNetworkListener` |
| `DefaultNetworkListener` | `utils/DefaultNetworkListener.kt` | `NetworkCallback` — уже подписан в `VpnService` и `ProxyService`, рассылает смены сети. Хранит `ssid` (Android 12+) |
| `SagerNet.currentNetwork` | `SagerNet.kt:260` | Текущий объект `Network` |

#### Управление сервисом
| Метод | Реализация |
|---|---|
| `SagerNet.startService()` | `ContextCompat.startForegroundService(...)` |
| `SagerNet.reloadService()` | `sendBroadcast(Action.RELOAD)` → `BaseService.forceLoad()` → если запущен: `stopRunner(restart=true)`, если остановлен: `startRunner()` |
| `SagerNet.stopService()` | `sendBroadcast(Action.CLOSE)` |
| `SagerNet.started` | `Boolean` — флаг активности сервиса |

#### Автозапуск при загрузке
`BootReceiver` — **уже реализован**. Запускает сервис при загрузке, если `DataStore.persistAcrossReboot == true` и `DataStore.currentProfile > 0`. UI-ключ: `"isAutoConnect"`.

#### Тестирование задержки
| Компонент | Файл | Описание |
|---|---|---|
| `V2RayTestInstance` | `bg/test/V2RayTestInstance.kt:38` | Конструктор: `(profile: ProxyEntity, link: String, timeout: Int, protectPath: String = "")`. Метод `doTest()` возвращает `Int` (мс) или бросает исключение |
| `DataStore.connectionTestURL` | `DataStore.kt:268` | URL теста (`https://www.google.com/generate_204`), конфигурируется пользователем |
| `ProxyEntity.ping` / `status` | `ProxyEntity.kt` | `ping` — задержка в мс; `status`: 0=не тестировался, 1=OK, 3=ошибка |
| Параллельный пинг в UI | `ConfigurationFragment.kt:788` | 6 воркеров, тот же `V2RayTestInstance`. При `TUN+VPN+started` передаёт `protectPath` |

#### Фильтрация профилей по имени
| Компонент | Файл | Описание |
|---|---|---|
| `SubscriptionBean.nameFilter` | `SubscriptionBean.java:45` | Regex-исключение при **обновлении** подписки |
| `SubscriptionBean.nameFilter1` | `SubscriptionBean.java:46` | Regex-включение при **обновлении** подписки |
| Поиск в UI | `ConfigurationFragment.kt:1276` | `displayName().lowercase().contains(lower) \|\| displayType().lowercase().contains(lower)` |

#### Хранилище и БД
| Поле / метод | Файл | Описание |
|---|---|---|
| `DataStore.selectedProxy` | `DataStore.kt:84` | ID выбранного профиля (`Key.PROFILE_ID`) |
| `DataStore.currentGroup()` | `DataStore.kt:106` | Возвращает текущую `ProxyGroup` |
| `SagerDatabase.proxyDao.getByGroup(groupId)` | `ProxyEntity.kt:535` | Все профили **одной** группы, упорядочены по `userOrder` |
| `SagerDatabase.proxyDao.updateProxy(proxy)` | `ProxyEntity.kt:568` | Сохранить изменения профиля |

---

### Требования

- Из **одной подписки** — разные наборы прокси для Wi-Fi и мобильной сети через **ключевые слова**
- Слова проверяются по `displayName()` и `displayType()` (без учёта регистра)
- Если ключевые слова заданы **только для одного** типа — для другого используются **все профили, кроме попавших в первый список** (взаимное исключение)
- Если заданы **оба** — независимые фильтры; **ни для одного** — используются все профили
- Перед подключением — опциональный **автопинг** кандидатов
- Автоматический выбор **лучшего по пингу** при смене типа сети

---

### Пример взаимного исключения

```
wifiKeywords   = "Армения, Латвия, Gaming"
mobileKeywords = ""  ← не задано

При Wi-Fi   → [Армения, Латвия, Швеция • Gaming]
При Mobile  → все минус wifi-список = [⚡ LTE Россия, ⚡ LTE Россия 2, ⚡ LTE Европа 1]
```

---

### Задачи

#### 1. `currentNetworkType` в `SagerNet`

**Файл:** `SagerNet.kt`

В `reloadNetwork()` уже вычисляется тип сети и передаётся в `Libexclavecore.setNetworkType()`. Нужно параллельно сохранить его в поле companion object:

```kotlin
@Volatile
var currentNetworkType: String = ""  // "wifi", "data", "bluetooth", ...

// В reloadNetwork(), сразу после существующего when-блока:
currentNetworkType = networkType
```

#### 2. Константы

**Файл:** `Constants.kt`, в `object Key`:

```kotlin
const val AUTO_SELECT_BY_NETWORK   = "autoSelectByNetwork"
const val WIFI_KEYWORDS            = "wifiKeywords"
const val MOBILE_KEYWORDS          = "mobileKeywords"
const val AUTO_PING_BEFORE_CONNECT = "autoPingBeforeConnect"
const val AUTO_PING_TIMEOUT        = "autoPingTimeout"      // мс, default 3000
const val AUTO_PING_CONCURRENCY    = "autoPingConcurrency"  // default 4
```

> `PERSIST_ACROSS_REBOOT = "isAutoConnect"` уже существует. Новые ключи не пересекаются с ним.

#### 3. Поля `DataStore`

**Файл:** `DataStore.kt`

```kotlin
var autoSelectByNetwork   by configurationStore.boolean(Key.AUTO_SELECT_BY_NETWORK)
var wifiKeywords          by configurationStore.string(Key.WIFI_KEYWORDS)
var mobileKeywords        by configurationStore.string(Key.MOBILE_KEYWORDS)
var autoPingBeforeConnect by configurationStore.boolean(Key.AUTO_PING_BEFORE_CONNECT)
var autoPingTimeout       by configurationStore.int(Key.AUTO_PING_TIMEOUT)
var autoPingConcurrency   by configurationStore.int(Key.AUTO_PING_CONCURRENCY)
```

> `configurationStore` — глобальное хранилище, не зависящее от профиля.

#### 4. `KeywordFilter`

**Новый файл:** `bg/KeywordFilter.kt`

```kotlin
object KeywordFilter {

    fun parse(raw: String): List<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun matches(proxy: ProxyEntity, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        val name = proxy.displayName().lowercase()
        val type = proxy.displayType().lowercase()
        return keywords.any { kw -> name.contains(kw) || type.contains(kw) }
    }

    /**
     * Логика:
     *  - Оба пусты          → все профили
     *  - currentKw не пуст  → фильтр по currentKw
     *  - currentKw пуст,
     *    otherKw не пуст    → все профили КРОМЕ совпавших с otherKw
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
            else                                     -> all.filterNot { matches(it, otherKw) }
        }
    }
}
```

#### 5. `NetworkProxyPinger`

**Новый файл:** `bg/NetworkProxyPinger.kt`

```kotlin
object NetworkProxyPinger {

    suspend fun pingAndRank(
        candidates: List<ProxyEntity>,
        testUrl: String  = DataStore.connectionTestURL,
        timeoutMs: Int   = DataStore.autoPingTimeout.takeIf { it > 0 } ?: 3000,
        concurrency: Int = DataStore.autoPingConcurrency.takeIf { it > 0 } ?: 4,
    ): List<ProxyEntity> = coroutineScope {
        val results = ConcurrentHashMap<Long, Int>()
        val semaphore = Semaphore(concurrency)

        // protectPath нужен при TUN+VPN+запущенном сервисе — как в ConfigurationFragment:788
        val protectPath =
            if (DataStore.tunImplementation == TunImplementation.SYSTEM
                && DataStore.serviceMode == Key.MODE_VPN
                && SagerNet.started
                && DataStore.startedProfile > 0
            ) SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
            else ""

        candidates.map { proxy ->
            launch {
                semaphore.withPermit {
                    val ping = try {
                        val instance = if (protectPath.isNotEmpty())
                            V2RayTestInstance(proxy, testUrl, timeoutMs, protectPath)
                        else
                            V2RayTestInstance(proxy, testUrl, timeoutMs)
                        instance.use { it.doTest() }
                    } catch (_: Exception) {
                        Int.MAX_VALUE
                    }
                    results[proxy.id] = ping
                    proxy.ping   = if (ping == Int.MAX_VALUE) 0 else ping
                    proxy.status = if (ping == Int.MAX_VALUE) 3 else 1
                    SagerDatabase.proxyDao.updateProxy(proxy)
                }
            }
        }.joinAll()

        candidates.sortedBy { results[it.id] ?: Int.MAX_VALUE }
    }
}
```

#### 6. `NetworkAwareSelector`

**Новый файл:** `bg/NetworkAwareSelector.kt`

```kotlin
object NetworkAwareSelector {

    suspend fun onNetworkChanged(network: Network?) {
        if (!DataStore.autoSelectByNetwork) return
        if (network == null) return

        val netType = SagerNet.currentNetworkType
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

        if (DataStore.selectedProxy == best.id) return

        DataStore.selectedProxy = best.id
        Logs.i("NetworkAwareSelector: → '${best.displayName()}' ping=${best.ping}ms netType=$netType")
        SagerNet.reloadService()
    }
}
```

#### 7. Интеграция — `VpnService.kt` и `ProxyService.kt`

Добавить **одну строку** в уже существующий `DefaultNetworkListener.start()` callback в каждом файле:

**`bg/VpnService.kt`** (строки 166–170):
```kotlin
DefaultNetworkListener.start(this) {
    if (networkListenerIsRunning) {
        underlyingNetwork = it
        SagerNet.reloadNetwork(it)
        runOnDefaultDispatcher { NetworkAwareSelector.onNetworkChanged(it) }  // NEW
    }
}
```

**`bg/ProxyService.kt`** (строки 49–52):
```kotlin
DefaultNetworkListener.start(this) {
    if (networkListenerIsRunning) {
        SagerNet.reloadNetwork(it)
        underlyingNetwork = it
        runOnDefaultDispatcher { NetworkAwareSelector.onNetworkChanged(it) }  // NEW
    }
}
```

> Отдельный `DefaultNetworkListener.start()` не создаём — встраиваемся в существующий.

#### 8. Автовыбор при первом запуске

**Файл:** `bg/BaseService.kt`, в `suspend fun startProcesses()` перед `data.proxy!!.launch()`:

```kotlin
if (DataStore.autoSelectByNetwork) {
    NetworkAwareSelector.onNetworkChanged(SagerNet.currentNetwork)
}
```

#### 9. `BootReceiver` — изменений не требует

Уже корректно работает через `persistAcrossReboot`. `startProcesses()` (п.8) подхватит правильный профиль.

#### 10. UI — `global_preferences.xml`

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

**`strings.xml`:**
```xml
<string name="auto_select_category">Auto proxy selection</string>
<string name="auto_select_by_network">Auto-select proxy by network type</string>
<string name="auto_select_by_network_summary">Switch proxy automatically when changing between Wi-Fi and mobile network</string>
<string name="wifi_keywords">Keywords for Wi-Fi</string>
<string name="wifi_keywords_hint">Comma-separated words matched against proxy name and type. Leave empty to use all proxies not matched by mobile keywords.</string>
<string name="mobile_keywords">Keywords for mobile network</string>
<string name="mobile_keywords_hint">Comma-separated words matched against proxy name and type. Leave empty to use all proxies not matched by Wi-Fi keywords.</string>
<string name="auto_ping_before_connect">Ping before connecting</string>
<string name="auto_ping_before_connect_summary">Test latency of candidate proxies and pick the fastest</string>
<string name="auto_ping_timeout">Ping timeout (ms)</string>
<string name="auto_ping_concurrency">Ping concurrency</string>
```

---

### Граничные случаи

| Ситуация | Поведение |
|---|---|
| Оба поля пустые | Все профили как кандидаты |
| Только `wifiKeywords` задано | Wi-Fi: по словам; Mobile: все минус wifi-совпадения |
| Только `mobileKeywords` задано | Mobile: по словам; Wi-Fi: все минус mobile-совпадения |
| Заданы оба | Независимые фильтры |
| Нет кандидатов | Лог, профиль не меняется |
| Все кандидаты недоступны | Лог, профиль не меняется |
| Профиль уже оптимальный | `reloadService()` не вызывается |
| `autoPingBeforeConnect = false` | Сортировка по сохранённому `ping` |
| Сеть не wifi/data | `onNetworkChanged()` выходит без действий |
| `network == null` | Профиль не меняется |

---

### Сводная таблица изменений (часть 1)

| Действие | Файл |
|---|---|
| ИЗМЕНИТЬ | `SagerNet.kt` — поле `currentNetworkType` + присвоение в `reloadNetwork()` |
| ИЗМЕНИТЬ | `Constants.kt` — 6 ключей |
| ИЗМЕНИТЬ | `DataStore.kt` — 6 полей |
| НОВЫЙ | `bg/KeywordFilter.kt` |
| НОВЫЙ | `bg/NetworkProxyPinger.kt` |
| НОВЫЙ | `bg/NetworkAwareSelector.kt` |
| ИЗМЕНИТЬ | `bg/VpnService.kt` — +1 строка в callback |
| ИЗМЕНИТЬ | `bg/ProxyService.kt` — +1 строка в callback |
| ИЗМЕНИТЬ | `bg/BaseService.kt` — вызов в `startProcesses()` |
| ИЗМЕНИТЬ | `res/xml/global_preferences.xml` — новая секция |
| ИЗМЕНИТЬ | `res/values/strings.xml` — 11 строк |

---

### Тесты (часть 1)

- [ ] **Unit** `KeywordFilter.candidates()`: все 4 комбинации входных данных
- [ ] **Unit** `KeywordFilter.matches()`: регистронезависимость, совпадение по `displayType`, нет совпадений
- [ ] **Unit** `NetworkProxyPinger`: мок `V2RayTestInstance`, сортировка по пингу, обработка исключений
- [ ] **Unit** `NetworkAwareSelector`: мок `SagerNet.currentNetworkType` + `DataStore`, проверить вызов `reloadService`
- [ ] **Интеграционный**: смена `currentNetworkType` "wifi"→"data", проверить `DataStore.selectedProxy`

---

## Часть 2: HWID Device Limit (Remnawave)

### Обзор

Remnawave-панель ограничивает число устройств по HWID. При включённой опции:
- Без заголовка `x-hwid` сервер возвращает `404`
- Сервер отвечает диагностическими заголовками

**В репозитории HWID не реализован** — ни генерация Device ID, ни хранение, ни отправка заголовков.

### Протокол

Клиент **отправляет** при запросе подписки:
```
x-hwid: <id>              // обязателен
x-device-os: Android      // опционально
x-ver-os: 14              // опционально
x-device-model: Pixel 8   // опционально
```

Сервер **отвечает** заголовками:
| Заголовок | Значение |
|---|---|
| `x-hwid-active` | `true` — HWID-лимит включён на сервере |
| `x-hwid-not-supported` | `true` — сервер ждал `x-hwid`, но не получил |
| `x-hwid-max-devices-reached` | `true` — лимит устройств исчерпан |
| `x-hwid-limit` | `true` — то же, для обратной совместимости с v2RayTun |

### `setHeader()` уже реализован в ядре

**[`ExclaveNetwork/LibExclaveCore/http.go:155`](https://github.com/ExclaveNetwork/LibExclaveCore/blob/main/http.go#L155)**

Интерфейс `HTTPRequest` (строка 44) объявляет:
```go
SetHeader(key string, value string)
```

Реализация:
```go
func (r *httpRequest) SetHeader(key string, value string) {
    r.request.Header.Set(key, value)
}
```

Через gomobile: `request.setHeader(key, value)` — аналогично уже используемому `setUserAgent()`.
**Изменений в ядре не требуется.**

---

### Задачи

#### HWID-1. `DeviceId.kt`

**Новый файл:** `utils/DeviceId.kt`

```kotlin
object DeviceId {

    /**
     * Возвращает HWID по приоритету:
     * 1. Кастомный HWID из настроек (если задан)
     * 2. ANDROID_ID → прогоняется через uuid5() для нормализации
     * 3. Fallback: случайный UUID, сохраняется в DataStore для стабильности
     */
    fun get(context: Context): String {
        val custom = DataStore.customHwid
        if (custom.isNotBlank()) return custom.trim()

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        // "9774d56d682e549c" — известный дефолт-баг на некоторых устройствах
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            return uuid5("exclave-hwid-$androidId")  // uuid5() уже есть в ktx/UUIDs.kt
        }

        var stored = DataStore.generatedHwid
        if (stored.isBlank()) {
            stored = UUID.randomUUID().toString()
            DataStore.generatedHwid = stored
        }
        return stored
    }
}
```

#### HWID-2. Константы и `DataStore`

**`Constants.kt`**, в `object Key`:
```kotlin
const val HWID_ENABLED   = "hwidEnabled"    // отправлять ли заголовки
const val CUSTOM_HWID    = "customHwid"     // кастомный HWID пользователя
const val GENERATED_HWID = "generatedHwid"  // fallback UUID (генерируется один раз)
```

**`DataStore.kt`**:
```kotlin
var hwidEnabled    by configurationStore.boolean(Key.HWID_ENABLED)   // default: true
var customHwid     by configurationStore.string(Key.CUSTOM_HWID)     // default: ""
var generatedHwid  by configurationStore.string(Key.GENERATED_HWID)  // default: ""
```

#### HWID-3. Отправка заголовков — `RawUpdater.kt` и `SIP008Updater.kt`

**`group/RawUpdater.kt`** — дополнить блок `newRequest().apply { ... }`:

```kotlin
}.newRequest().apply {
    setURL(subscription.link)
    if (subscription.customUserAgent.isNotEmpty()) {
        setUserAgent(subscription.customUserAgent)
    } else {
        setUserAgent(USER_AGENT)
    }
    if (DataStore.hwidEnabled) {                              // NEW
        val hwid = DeviceId.get(app)                          // NEW
        setHeader("x-hwid", hwid)                            // NEW
        setHeader("x-device-os", "Android")                  // NEW
        setHeader("x-ver-os", Build.VERSION.RELEASE)         // NEW
        setHeader("x-device-model",                          // NEW
            "${Build.MANUFACTURER} ${Build.MODEL}")           // NEW
    }                                                         // NEW
}.execute()
```

**`group/SIP008Updater.kt`** — аналогичное изменение в блоке строк 58–65.

#### HWID-4. Обработка ответных заголовков — `RawUpdater.kt`

Существующая обработка ошибок в `GroupUpdater.kt` (строка 89) перехватывает любое исключение из `doUpdate()` и вызывает `userInterface.onUpdateFailure(group, message)`. Поэтому для HWID-ошибок нужно просто бросить исключение через `error()`:

```kotlin
// После response = ...execute(), вместе с существующим чтением Subscription-Userinfo:
if (DataStore.hwidEnabled) {
    val hwidNotSupported  = response.getHeader("x-hwid-not-supported") == "true"
    val hwidLimitReached  = response.getHeader("x-hwid-max-devices-reached") == "true"
                         || response.getHeader("x-hwid-limit") == "true"

    if (hwidNotSupported) error(app.getString(R.string.hwid_not_supported))
    if (hwidLimitReached) error(app.getString(R.string.hwid_limit_reached))
}
```

> `error()` бросает `IllegalStateException`, которое `GroupUpdater` перехватывает и передаёт в `onUpdateFailure()` → UI показывает сообщение пользователю.

**`strings.xml`**:
```xml
<string name="hwid_not_supported">Server requires HWID. Enable HWID in Settings → HWID Device Limit.</string>
<string name="hwid_limit_reached">Device limit reached. Remove unused devices in your subscription panel.</string>
```

#### HWID-5. UI настроек

**`res/xml/global_preferences.xml`**:
```xml
<PreferenceCategory app:title="@string/hwid_category">

    <SwitchPreference
        app:key="hwidEnabled"
        app:title="@string/hwid_enabled"
        app:summary="@string/hwid_enabled_summary"
        app:defaultValue="true" />

    <Preference
        app:key="hwidCurrent"
        app:title="@string/hwid_current"
        app:dependency="hwidEnabled" />

    <EditTextPreference
        app:key="customHwid"
        app:title="@string/hwid_custom"
        app:summary="@string/hwid_custom_summary"
        app:dependency="hwidEnabled"
        app:useSimpleSummaryProvider="true" />

</PreferenceCategory>
```

**`ui/SettingsPreferenceFragment.kt`**, в `onCreatePreferences()`:
```kotlin
findPreference<Preference>("hwidCurrent")?.apply {
    summary = DeviceId.get(requireContext())
}
```

**`strings.xml`**:
```xml
<string name="hwid_category">HWID Device Limit</string>
<string name="hwid_enabled">Send HWID on subscription update</string>
<string name="hwid_enabled_summary">Required for subscriptions with Remnawave device limit</string>
<string name="hwid_current">Current HWID</string>
<string name="hwid_custom">Custom HWID</string>
<string name="hwid_custom_summary">Leave empty to use auto-generated ID based on ANDROID_ID</string>
```

---

### Порядок реализации HWID

1. `Constants.kt` — 3 ключа
2. `DataStore.kt` — 3 поля
3. `utils/DeviceId.kt` — новый файл
4. `group/RawUpdater.kt` — заголовки + обработка ответа
5. `group/SIP008Updater.kt` — заголовки
6. `res/xml/global_preferences.xml` + `strings.xml` — UI
7. `ui/SettingsPreferenceFragment.kt` — `hwidCurrent` summary

### Сводная таблица изменений (часть 2)

| Действие | Файл |
|---|---|
| ИЗМЕНИТЬ | `Constants.kt` — 3 ключа |
| ИЗМЕНИТЬ | `DataStore.kt` — 3 поля |
| НОВЫЙ | `utils/DeviceId.kt` |
| ИЗМЕНИТЬ | `group/RawUpdater.kt` — отправка + обработка ответа |
| ИЗМЕНИТЬ | `group/SIP008Updater.kt` — отправка заголовков |
| ИЗМЕНИТЬ | `res/xml/global_preferences.xml` — секция HWID |
| ИЗМЕНИТЬ | `res/values/strings.xml` — 8 строк |
| ИЗМЕНИТЬ | `ui/SettingsPreferenceFragment.kt` — `hwidCurrent` |

---

### Тесты (часть 2)

- [ ] **Unit** `DeviceId.get()`: приоритет кастом → ANDROID_ID → fallback
- [ ] **Unit** `DeviceId.get()`: повторный вызов возвращает тот же ID (стабильность)
- [ ] **Unit** `DeviceId.get()`: дефолтный `"9774d56d682e549c"` игнорируется, падает на fallback
- [ ] **Интеграционный**: запрос подписки с mock-сервером — проверить заголовки `x-hwid`, `x-device-os`, `x-ver-os`, `x-device-model`
- [ ] **Интеграционный**: ответ `x-hwid-not-supported: true` → `onUpdateFailure` с нужным текстом
- [ ] **Интеграционный**: ответ `x-hwid-max-devices-reached: true` → `onUpdateFailure` с нужным текстом
- [ ] **Интеграционный**: `hwidEnabled = false` → заголовки не отправляются, ответные заголовки не проверяются
