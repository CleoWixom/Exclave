# TODO: Автовыбор прокси по типу сети + автопинг + ключевые слова

## Контекст и существующая инфраструктура

| Компонент | Файл | Что уже есть |
|---|---|---|
| `AbstractBean.name` | `fmt/AbstractBean.java` | Имя профиля (например `⚡ LTE Россия`), поле `name` |
| `ProxyEntity.displayName()` | `database/ProxyEntity.kt` | Возвращает `bean.name` или `host:port` |
| `ProxyEntity.displayType()` | `database/ProxyEntity.kt` | Строка протокола (например `VLESS / TCP / REALITY`) |
| `ProxyEntity.ping` / `status` | `database/ProxyEntity.kt` | Задержка и статус теста |
| `V2RayTestInstance.doTest()` | `bg/test/V2RayTestInstance.kt` | Пинг через `Libexclavecore.urlTest()` |
| Поиск по имени в UI | `ui/ConfigurationFragment.kt:1276` | `displayName().lowercase().contains(lower)` |
| `DefaultNetworkListener` | `utils/DefaultNetworkListener.kt` | `NetworkCallback` — отслеживает смену сети |
| `RuleEntity.networkType` | `database/RuleEntity.kt` | Маршрутизация по типу сети (`wifi`, `data`, ...) |

**Как выглядят профили в подписке (скриншот):**
```
⚡ LTE Россия          ← bean.name (displayName)
При глушении LTE/4G   ← вторая строка того же name (многострочное имя) или отдельный рендер
```
```
Армения               ← bean.name
VLESS / TCP / REALITY | JSON  ← displayType()
```

**Фильтрация должна работать по `displayName()` и `displayType()`** — теми же строками, что уже используются в поиске (`ConfigurationFragment.kt:1276`).

---

## Требования

- Из **одной подписки** настроить разные наборы прокси для Wi-Fi и мобильной сети через **ключевые слова**
- Слова проверяются по `displayName()` и `displayType()` профиля (без учёта регистра)
- Перед подключением — **автопинг** подходящих кандидатов
- Автоматический выбор **лучшего по пингу** профиля при смене типа сети

---

## Пример конфигурации

```
Ключевые слова для Wi-Fi:  "Армения, Латвия, Швеция, Gaming"
Ключевые слова для Mobile: "LTE, При глушении"
```

При подключении к Wi-Fi — среди профилей группы выбираются те, у которых
`displayName()` или `displayType()` содержит хотя бы одно из слов (`Армения`, `Латвия`, `Швеция`, `Gaming`).
При мобильной сети — профили, у которых имя содержит `LTE` или `При глушении`.

---

## Задачи

---

### 1. Константы и ключи

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/Constants.kt`

```kotlin
const val AUTO_SELECT_BY_NETWORK    = "autoSelectByNetwork"     // включить фичу
const val WIFI_KEYWORDS             = "wifiKeywords"            // ключевые слова для Wi-Fi (через запятую)
const val MOBILE_KEYWORDS           = "mobileKeywords"          // ключевые слова для Mobile
const val AUTO_PING_BEFORE_CONNECT  = "autoPingBeforeConnect"   // пинговать перед выбором
const val AUTO_PING_TIMEOUT         = "autoPingTimeout"         // таймаут мс, default 3000
const val AUTO_PING_CONCURRENCY     = "autoPingConcurrency"     // параллельность, default 4
const val AUTO_CONNECT              = "autoConnect"             // автоподключение
const val STOP_ON_NETWORK_LOSS      = "stopOnNetworkLoss"       // стоп при потере сети
```

---

### 2. Поля в `DataStore.kt`

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt`

```kotlin
var autoSelectByNetwork   by profileCacheStore.boolean(Key.AUTO_SELECT_BY_NETWORK)
var wifiKeywords          by profileCacheStore.string(Key.WIFI_KEYWORDS)    // default: ""
var mobileKeywords        by profileCacheStore.string(Key.MOBILE_KEYWORDS)  // default: "LTE"
var autoPingBeforeConnect by profileCacheStore.boolean(Key.AUTO_PING_BEFORE_CONNECT)
var autoPingTimeout       by profileCacheStore.int(Key.AUTO_PING_TIMEOUT)   // default: 3000
var autoPingConcurrency   by profileCacheStore.int(Key.AUTO_PING_CONCURRENCY) // default: 4
var autoConnect           by profileCacheStore.boolean(Key.AUTO_CONNECT)
var stopOnNetworkLoss     by profileCacheStore.boolean(Key.STOP_ON_NETWORK_LOSS)
```

---

### 3. Вспомогательная функция фильтрации по ключевым словам

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/KeywordFilter.kt`

```kotlin
object KeywordFilter {

    /**
     * Разбирает строку ключевых слов, разделённых запятой.
     * Пример: "LTE, При глушении, Россия" → ["lte", "при глушении", "россия"]
     */
    fun parseKeywords(raw: String): List<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * Возвращает true, если хотя бы одно ключевое слово содержится
     * в displayName() ИЛИ displayType() профиля (без учёта регистра).
     *
     * Если keywords пустой — матчит ВСЕ профили (нет ограничений).
     */
    fun matches(proxy: ProxyEntity, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        val name = proxy.displayName().lowercase()
        val type = proxy.displayType().lowercase()
        return keywords.any { kw -> name.contains(kw) || type.contains(kw) }
    }

    /**
     * Фильтрует список профилей по ключевым словам.
     */
    fun filter(proxies: List<ProxyEntity>, rawKeywords: String): List<ProxyEntity> {
        val keywords = parseKeywords(rawKeywords)
        return proxies.filter { matches(it, keywords) }
    }
}
```

> Переиспользует ту же логику что в `ConfigurationFragment.kt:1276` — поиск по `displayName`/`displayType`.

---

### 4. Определение текущего типа сети

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/utils/DefaultNetworkListener.kt`

Добавить:

```kotlin
enum class NetworkType { WIFI, MOBILE, OTHER, UNKNOWN }

var currentNetworkType: NetworkType = NetworkType.UNKNOWN
    private set
```

Заполнять в `onCapabilitiesChanged`:

```kotlin
override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
    currentNetworkType = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
        else -> NetworkType.OTHER
    }
    // ... существующий код (ssid, networkActor)
}

override fun onLost(network: Network) {
    currentNetworkType = NetworkType.UNKNOWN
    // ... существующий код
}
```

---

### 5. Автопинг кандидатов — `NetworkProxyPinger`

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkProxyPinger.kt`

```kotlin
object NetworkProxyPinger {

    /**
     * Пингует [candidates] параллельно через V2RayTestInstance.
     * Возвращает список, отсортированный по задержке (лучший первый).
     * Недоступные профили — в конце.
     */
    suspend fun pingAndRank(
        candidates: List<ProxyEntity>,
        testUrl: String = DataStore.connectionTestURL,
        timeoutMs: Int = DataStore.autoPingTimeout.takeIf { it > 0 } ?: 3000,
        concurrency: Int = DataStore.autoPingConcurrency.takeIf { it > 0 } ?: 4,
    ): List<ProxyEntity> = coroutineScope {
        val results = ConcurrentHashMap<Long, Int>()   // proxy.id → ping ms
        val semaphore = Semaphore(concurrency)

        candidates.map { proxy ->
            launch {
                semaphore.withPermit {
                    val ping = try {
                        V2RayTestInstance(proxy, testUrl, timeoutMs).use { it.doTest() }
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

> `V2RayTestInstance` и `doTest()` уже готовы — переиспользуем без изменений.

---

### 6. Автовыбор профиля — `NetworkAwareSelector`

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkAwareSelector.kt`

```kotlin
object NetworkAwareSelector {

    fun start() {
        runOnDefaultDispatcher {
            DefaultNetworkListener.start(this) { network -> onNetworkChanged(network) }
        }
    }

    fun stop() {
        runOnDefaultDispatcher { DefaultNetworkListener.stop(this) }
    }

    private fun onNetworkChanged(network: Network?) {
        if (!DataStore.autoSelectByNetwork) return

        if (network == null) {
            if (DataStore.stopOnNetworkLoss) SagerNet.stopService()
            return
        }

        runOnDefaultDispatcher {
            val rawKeywords = when (DefaultNetworkListener.currentNetworkType) {
                NetworkType.WIFI   -> DataStore.wifiKeywords
                NetworkType.MOBILE -> DataStore.mobileKeywords
                else               -> return@runOnDefaultDispatcher
            }

            // 1. Получить все профили текущей группы
            val allProxies = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroup().id)

            // 2. Отфильтровать по ключевым словам в displayName() / displayType()
            val candidates = KeywordFilter.filter(allProxies, rawKeywords)
            if (candidates.isEmpty()) {
                Logs.w("NetworkAwareSelector: no candidates for keywords '$rawKeywords'")
                return@runOnDefaultDispatcher
            }

            // 3. Пинг кандидатов (или взять по сохранённому ping)
            val ranked = if (DataStore.autoPingBeforeConnect) {
                NetworkProxyPinger.pingAndRank(candidates)
            } else {
                candidates.sortedWith(compareBy {
                    if (it.status == 1 && it.ping > 0) it.ping else Int.MAX_VALUE
                })
            }

            // 4. Выбрать лучший доступный
            val best = ranked.firstOrNull { it.status != 3 } ?: run {
                Logs.w("NetworkAwareSelector: all candidates unreachable")
                return@runOnDefaultDispatcher
            }

            if (DataStore.selectedProxy == best.id) {
                if (DataStore.autoConnect && !BaseService.isRunning()) SagerNet.startService()
                return@runOnDefaultDispatcher
            }

            DataStore.selectedProxy = best.id
            Logs.i("NetworkAwareSelector: → '${best.displayName()}' ping=${best.ping}ms  keywords='$rawKeywords'")

            if (BaseService.isRunning()) {
                SagerNet.reloadService()
            } else if (DataStore.autoConnect) {
                SagerNet.startService()
            }
        }
    }
}
```

---

### 7. Интеграция в жизненный цикл

#### 7.1 `BaseService.kt`
```kotlin
// в onStartCommand / onCreate:
NetworkAwareSelector.start()

// в onDestroy:
NetworkAwareSelector.stop()
```

#### 7.2 Автоподключение при старте — `SagerNet.kt`
```kotlin
// в onCreate(), после инициализации DataStore:
if (DataStore.autoConnect && DataStore.selectedProxy > 0) {
    startService(Intent(this, VpnService::class.java))
}
```

#### 7.3 `BootReceiver.kt`
Добавить проверку:
```kotlin
if (!DataStore.autoConnect) return
```

---

### 8. UI — настройки

**Файл:** `app/src/main/res/xml/global_preferences.xml`

```xml
<PreferenceCategory app:title="@string/auto_select_category">

    <SwitchPreferenceCompat
        app:key="autoSelectByNetwork"
        app:title="@string/auto_select_by_network"
        app:summary="@string/auto_select_by_network_summary" />

    <!-- Ключевые слова для Wi-Fi -->
    <EditTextPreference
        app:key="wifiKeywords"
        app:title="@string/wifi_keywords"
        app:summary="@string/wifi_keywords_summary"
        app:dependency="autoSelectByNetwork" />

    <!-- Ключевые слова для мобильной сети -->
    <EditTextPreference
        app:key="mobileKeywords"
        app:title="@string/mobile_keywords"
        app:summary="@string/mobile_keywords_summary"
        app:dependency="autoSelectByNetwork" />

    <SwitchPreferenceCompat
        app:key="autoPingBeforeConnect"
        app:title="@string/auto_ping_before_connect"
        app:summary="@string/auto_ping_before_connect_summary"
        app:dependency="autoSelectByNetwork" />

    <EditTextPreference
        app:key="autoPingTimeout"
        app:title="@string/auto_ping_timeout"
        app:dependency="autoPingBeforeConnect"
        app:inputType="number" />

    <SwitchPreferenceCompat
        app:key="autoConnect"
        app:title="@string/auto_connect"
        app:summary="@string/auto_connect_summary" />

    <SwitchPreferenceCompat
        app:key="stopOnNetworkLoss"
        app:title="@string/stop_on_network_loss"
        app:dependency="autoConnect" />

</PreferenceCategory>
```

**Файл:** `app/src/main/res/values/strings.xml`

```xml
<string name="auto_select_category">Auto proxy selection</string>
<string name="auto_select_by_network">Auto-select proxy by network type</string>
<string name="auto_select_by_network_summary">Switch proxy automatically when changing between Wi-Fi and mobile network</string>
<string name="wifi_keywords">Keywords for Wi-Fi</string>
<string name="wifi_keywords_summary">Comma-separated words matched against proxy name and type. Example: Армения, Латвия, Gaming</string>
<string name="mobile_keywords">Keywords for mobile network</string>
<string name="mobile_keywords_summary">Comma-separated words matched against proxy name and type. Example: LTE, При глушении</string>
<string name="auto_ping_before_connect">Ping before connecting</string>
<string name="auto_ping_before_connect_summary">Test latency of matching proxies and pick the fastest</string>
<string name="auto_ping_timeout">Ping timeout (ms)</string>
<string name="auto_connect">Auto-connect</string>
<string name="auto_connect_summary">Connect automatically on app start and network change</string>
<string name="stop_on_network_loss">Disconnect on network loss</string>
```

---

### 9. Граничные случаи

| Ситуация | Поведение |
|---|---|
| Ключевые слова не заданы (пустая строка) | `KeywordFilter.filter()` возвращает все профили группы |
| Ни один профиль не совпал по словам | Логировать предупреждение, не менять профиль |
| Все кандидаты недоступны (ping fail) | Не переключать, оставить текущий |
| Уже выбран лучший профиль | Не перезапускать сервис, только убедиться что запущен |
| Подписка обновилась | После обновления группы повторно запустить `onNetworkChanged` |
| `autoPingBeforeConnect = false` | Сортировка по сохранённому `ping`; без нового теста |
| Несколько кандидатов с одинаковым пингом | Победитель — первый по порядку в группе |
| Профиль удалён из подписки | `selectedProxy` сбросится при следующем цикле выбора |

---

### 10. UX-флоу (пример)

```
Подписка содержит:
  ⚡ LTE Россия          (displayName)  / "При глушении LTE/4G" в name
  ⚡ LTE Россия 2        
  ⚡ LTE Европа 1        
  Армения                / VLESS / TCP / REALITY | JSON
  Латвия                 / VLESS / TCP / REALITY | JSON
  Швеция • Gaming 🎮    

Настройки:
  wifiKeywords   = "Армения, Латвия, Gaming"
  mobileKeywords = "LTE"
  autoPingBeforeConnect = true

Сценарий: телефон переключился с Wi-Fi → Mobile
  1. DefaultNetworkListener → currentNetworkType = MOBILE
  2. NetworkAwareSelector: rawKeywords = "LTE"
  3. KeywordFilter: ["⚡ LTE Россия", "⚡ LTE Россия 2", "⚡ LTE Европа 1"] (3 кандидата)
  4. NetworkProxyPinger.pingAndRank():
       ⚡ LTE Россия 2  →  54 ms  ✓
       ⚡ LTE Россия    →  91 ms  ✓
       ⚡ LTE Европа 1  → 310 ms  ✓
  5. DataStore.selectedProxy = "⚡ LTE Россия 2"
  6. SagerNet.reloadService()
```

---

### 11. Порядок реализации

1. **`Constants.kt`** — добавить ключи (п. 1)
2. **`DataStore.kt`** — добавить поля (п. 2)
3. **`DefaultNetworkListener.kt`** — добавить `NetworkType` + `currentNetworkType` (п. 4)
4. **`KeywordFilter.kt`** — новый файл (п. 3)
5. **`NetworkProxyPinger.kt`** — новый файл (п. 5)
6. **`NetworkAwareSelector.kt`** — новый файл (п. 6)
7. **`BaseService.kt`** / **`SagerNet.kt`** — интеграция (п. 7)
8. **`BootReceiver.kt`** — проверка `autoConnect` (п. 7.3)
9. **`global_preferences.xml`** + **`strings.xml`** — UI (п. 8)
10. **`SettingsPreferenceFragment.kt`** — подключить preference'ы

---

### 12. Тесты

- [ ] **Unit** `KeywordFilter`: проверить матчинг по имени, по типу, регистронезависимость, пустые слова
- [ ] **Unit** `NetworkProxyPinger`: мок `V2RayTestInstance`, проверить сортировку и обработку ошибок
- [ ] **Unit** `NetworkAwareSelector`: мок `DefaultNetworkListener` + `DataStore`, проверить выбор и вызов `reloadService`
- [ ] **UI**: задать ключевые слова → убедиться что summary обновляется корректно
