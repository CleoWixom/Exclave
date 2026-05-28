# TODO: Автовыбор прокси по типу сети + автопинг + тэги

## Контекст и существующая инфраструктура

| Компонент | Файл | Что уже есть |
|---|---|---|
| `RuleEntity.networkType` | `database/RuleEntity.kt` | Поле для маршрутизации по типу сети (`wifi`, `data`, ...) |
| `DefaultNetworkListener` | `utils/DefaultNetworkListener.kt` | `NetworkCallback` — отслеживает смену сети, читает SSID (Android 12+) |
| `ProxyEntity.ping` | `database/ProxyEntity.kt` | Поле задержки; `status` (0=untested, 1=ok, 3=error) |
| `V2RayTestInstance` | `bg/test/V2RayTestInstance.kt` | `doTest()` — пингует один профиль через `Libexclavecore.urlTest()` |
| `ConfigBuilder.networkType` | `fmt/ConfigBuilder.kt` | Передаёт `networkType` в конфиг ядра (V2Ray/sing-box) |
| Ping UI | `ui/ConfigurationFragment.kt` | `urlTest()` — параллельный пинг всей группы (6 воркеров) |

**Чего не хватает:**
1. Тэгирование профилей внутри подписки для разных типов сети
2. Автопинг кандидатов перед подключением
3. Автовыбор лучшего профиля по тэгу при смене сети

---

## Требования

- Из **одной подписки** можно задать разные наборы прокси для Wi-Fi и мобильной сети через **тэги**
- Перед подключением — **автопинг** подходящих кандидатов через прокси (`V2RayTestInstance`)
- Автоматический выбор **лучшего по пингу** профиля с нужным тэгом при смене типа сети

---

## Задачи

---

### 1. Тэги профилей

#### 1.1 Поле `networkTags` в `ProxyEntity`
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt`

Добавить поле:
```kotlin
@ColumnInfo(defaultValue = "") var networkTags: Set<String> = emptySet()
```

Конвертер `Set<String>` уже есть (`StringCollectionConverter`).

Миграция БД — добавить в `database/Migrations.kt`:
```kotlin
// версия N+1
database.execSQL("ALTER TABLE proxy_entities ADD COLUMN networkTags TEXT NOT NULL DEFAULT ''")
```

Смысл тэгов: произвольные строки, которые пользователь задаёт вручную.  
Зарезервированные значения (рекомендуемые): `wifi`, `mobile`.  
Можно также: `home`, `work`, `roaming` — любые строки.

#### 1.2 UI — редактирование тэгов профиля
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt`  
**Файл:** `app/src/main/res/xml/name_preferences.xml`

Добавить `MultiSelectListPreference` или поле ввода через запятую (если тэги произвольные):

```xml
<EditTextPreference
    app:key="networkTags"
    app:title="@string/network_tags"
    app:summary="@string/network_tags_summary"
    app:icon="@drawable/ic_baseline_label_24" />
```

Формат ввода: `wifi, mobile` → парсить по запятой в `Set<String>`.

Строки для `strings.xml`:
```xml
<string name="network_tags">Network tags</string>
<string name="network_tags_summary">Tags for auto-selection by network type (e.g. wifi, mobile)</string>
```

#### 1.3 Фильтрация по тэгу в БД
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt` (DAO)

```kotlin
// Получить все профили группы с заданным тэгом
@Query("SELECT * FROM proxy_entities WHERE groupId = :groupId")
fun getByGroup(groupId: Long): List<ProxyEntity>
// Фильтрацию по тэгу делать in-memory через:
// entities.filter { it.networkTags.contains(tag) }
```

> SQLite не поддерживает поиск внутри JSON-сериализованных Set, поэтому фильтрация in-memory.

---

### 2. Конфигурация автовыбора

#### 2.1 Ключи в `Constants.kt`
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/Constants.kt`

```kotlin
const val AUTO_SELECT_BY_NETWORK = "autoSelectByNetwork"   // включить фичу
const val WIFI_PROXY_TAG         = "wifiProxyTag"          // тэг для Wi-Fi
const val MOBILE_PROXY_TAG       = "mobileProxyTag"        // тэг для Mobile
const val AUTO_PING_BEFORE_CONNECT = "autoPingBeforeConnect" // пинговать перед выбором
const val AUTO_PING_TIMEOUT      = "autoPingTimeout"       // таймаут пинга (мс), default 3000
const val AUTO_PING_CONCURRENCY  = "autoPingConcurrency"   // параллельность, default 4
const val AUTO_CONNECT           = "autoConnect"           // автоподключение при старте/смене сети
const val STOP_ON_NETWORK_LOSS   = "stopOnNetworkLoss"     // останавливать при потере сети
```

#### 2.2 Поля в `DataStore.kt`
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt`

```kotlin
var autoSelectByNetwork   by profileCacheStore.boolean(Key.AUTO_SELECT_BY_NETWORK)
var wifiProxyTag          by profileCacheStore.string(Key.WIFI_PROXY_TAG)    // default: "wifi"
var mobileProxyTag        by profileCacheStore.string(Key.MOBILE_PROXY_TAG)  // default: "mobile"
var autoPingBeforeConnect by profileCacheStore.boolean(Key.AUTO_PING_BEFORE_CONNECT)
var autoPingTimeout       by profileCacheStore.int(Key.AUTO_PING_TIMEOUT)    // default: 3000
var autoPingConcurrency   by profileCacheStore.int(Key.AUTO_PING_CONCURRENCY) // default: 4
var autoConnect           by profileCacheStore.boolean(Key.AUTO_CONNECT)
var stopOnNetworkLoss     by profileCacheStore.boolean(Key.STOP_ON_NETWORK_LOSS)
```

---

### 3. Определение типа текущей сети

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/utils/DefaultNetworkListener.kt`

Добавить:

```kotlin
enum class NetworkType { WIFI, MOBILE, OTHER, UNKNOWN }

var currentNetworkType: NetworkType = NetworkType.UNKNOWN
    private set
```

Заполнять в callback'е `onCapabilitiesChanged`:

```kotlin
override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
    currentNetworkType = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
        else -> NetworkType.OTHER
    }
    // ... существующий код ssid и networkActor
}

override fun onLost(network: Network) {
    currentNetworkType = NetworkType.UNKNOWN
    // ... существующий код
}
```

---

### 4. Автопинг кандидатов — `NetworkProxyPinger`

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkProxyPinger.kt`

```kotlin
object NetworkProxyPinger {

    /**
     * Пингует [candidates] параллельно через [V2RayTestInstance].
     * Возвращает список, отсортированный по задержке (лучший первый).
     * Профили с ошибкой — в конце.
     */
    suspend fun pingAndRank(
        candidates: List<ProxyEntity>,
        testUrl: String = DataStore.connectionTestURL,
        timeoutMs: Int = DataStore.autoPingTimeout.takeIf { it > 0 } ?: 3000,
        concurrency: Int = DataStore.autoPingConcurrency.takeIf { it > 0 } ?: 4,
    ): List<ProxyEntity> = coroutineScope {
        val results = ConcurrentHashMap<Long, Int>() // id -> ping ms (Int.MAX_VALUE = fail)

        val semaphore = Semaphore(concurrency)
        val jobs = candidates.map { profile ->
            launch {
                semaphore.withPermit {
                    val ping = try {
                        V2RayTestInstance(profile, testUrl, timeoutMs).use { it.doTest() }
                    } catch (_: Exception) {
                        Int.MAX_VALUE
                    }
                    results[profile.id] = ping
                    // сохранить результат в БД, как это делает ConfigurationFragment.urlTest()
                    profile.ping = if (ping == Int.MAX_VALUE) 0 else ping
                    profile.status = if (ping == Int.MAX_VALUE) 3 else 1
                    SagerDatabase.proxyDao.updateProxy(profile)
                }
            }
        }
        jobs.joinAll()

        candidates.sortedWith(compareBy {
            results[it.id] ?: Int.MAX_VALUE
        })
    }
}
```

> `V2RayTestInstance` и его `doTest()` уже готовы — переиспользуем без изменений.

---

### 5. Автовыбор профиля — `NetworkAwareSelector`

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkAwareSelector.kt`

```kotlin
object NetworkAwareSelector {

    fun start() {
        runOnDefaultDispatcher {
            DefaultNetworkListener.start(this) { network ->
                onNetworkChanged(network)
            }
        }
    }

    fun stop() {
        runOnDefaultDispatcher {
            DefaultNetworkListener.stop(this)
        }
    }

    private fun onNetworkChanged(network: Network?) {
        if (!DataStore.autoSelectByNetwork) return

        if (network == null) {
            if (DataStore.stopOnNetworkLoss) SagerNet.stopService()
            return
        }

        runOnDefaultDispatcher {
            val tag = when (DefaultNetworkListener.currentNetworkType) {
                NetworkType.WIFI   -> DataStore.wifiProxyTag.ifEmpty { "wifi" }
                NetworkType.MOBILE -> DataStore.mobileProxyTag.ifEmpty { "mobile" }
                else               -> return@runOnDefaultDispatcher
            }

            val group = DataStore.currentGroup()
            val candidates = SagerDatabase.proxyDao
                .getByGroup(group.id)
                .filter { it.networkTags.contains(tag) }

            if (candidates.isEmpty()) {
                Logs.w("NetworkAwareSelector: no candidates for tag '$tag'")
                return@runOnDefaultDispatcher
            }

            val ranked = if (DataStore.autoPingBeforeConnect) {
                NetworkProxyPinger.pingAndRank(candidates)
            } else {
                // без пинга — брать профиль с наименьшим сохранённым ping (или первый)
                candidates.sortedWith(compareBy {
                    if (it.status == 1 && it.ping > 0) it.ping else Int.MAX_VALUE
                })
            }

            val best = ranked.firstOrNull { it.status != 3 } ?: return@runOnDefaultDispatcher

            if (DataStore.selectedProxy == best.id) {
                // профиль тот же — просто убедиться что сервис запущен
                if (DataStore.autoConnect && !BaseService.isRunning()) SagerNet.startService()
                return@runOnDefaultDispatcher
            }

            DataStore.selectedProxy = best.id
            Logs.i("NetworkAwareSelector: selected '${best.displayName()}' (ping=${best.ping}ms) for tag '$tag'")

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

### 6. Интеграция в жизненный цикл сервиса

#### 6.1 Запуск/остановка селектора
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt`

```kotlin
// в onStartCommand или onCreate сервиса:
NetworkAwareSelector.start()

// в onDestroy:
NetworkAwareSelector.stop()
```

#### 6.2 Автоподключение при старте приложения
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt`

```kotlin
// в onCreate(), после инициализации DataStore:
if (DataStore.autoConnect && DataStore.selectedProxy > 0) {
    startService(Intent(this, VpnService::class.java))
}
```

#### 6.3 Boot receiver
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt`

Убедиться, что `BootReceiver.onReceive()` проверяет `DataStore.autoConnect` перед запуском сервиса. Если проверки нет — добавить:
```kotlin
if (!DataStore.autoConnect) return
```

---

### 7. UI — настройки автовыбора

**Файл:** `app/src/main/res/xml/global_preferences.xml`

Добавить секцию:

```xml
<PreferenceCategory app:title="@string/auto_select_category">

    <SwitchPreferenceCompat
        app:key="autoSelectByNetwork"
        app:title="@string/auto_select_by_network"
        app:summary="@string/auto_select_by_network_summary" />

    <EditTextPreference
        app:key="wifiProxyTag"
        app:title="@string/wifi_proxy_tag"
        app:dependency="autoSelectByNetwork" />

    <EditTextPreference
        app:key="mobileProxyTag"
        app:title="@string/mobile_proxy_tag"
        app:dependency="autoSelectByNetwork" />

    <SwitchPreferenceCompat
        app:key="autoPingBeforeConnect"
        app:title="@string/auto_ping_before_connect"
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

**Файл:** `app/src/main/res/values/strings.xml` — добавить:

```xml
<string name="auto_select_category">Auto proxy selection</string>
<string name="auto_select_by_network">Auto-select by network type</string>
<string name="auto_select_by_network_summary">Pick the best proxy by tag when switching between Wi-Fi and mobile network</string>
<string name="wifi_proxy_tag">Tag for Wi-Fi</string>
<string name="mobile_proxy_tag">Tag for mobile network</string>
<string name="auto_ping_before_connect">Ping before connecting</string>
<string name="auto_ping_before_connect_summary">Test latency of candidates and pick the fastest</string>
<string name="auto_ping_timeout">Ping timeout (ms)</string>
<string name="network_tags">Network tags</string>
<string name="network_tags_summary">Comma-separated tags for auto-selection (e.g. wifi, mobile)</string>
<string name="auto_connect">Auto-connect</string>
<string name="auto_connect_summary">Connect automatically on app start and network change</string>
<string name="stop_on_network_loss">Disconnect on network loss</string>
```

---

### 8. Граничные случаи

| Ситуация | Ожидаемое поведение |
|---|---|
| Нет кандидатов с нужным тэгом | Логировать предупреждение, не менять профиль |
| Все кандидаты недоступны (ping fail) | Не переключать; оставить текущий профиль |
| Профиль удалён из подписки | Сбросить `selectedProxy`, показать уведомление |
| VPN активен, сеть сменилась | Пингануть кандидатов → выбрать лучшего → `reloadService()` |
| `autoPingBeforeConnect = false` | Брать профиль с минимальным сохранённым `ping`, или первый по порядку |
| `autoSelectByNetwork = false` | `NetworkAwareSelector` полностью бездействует |
| Подписка обновилась (новые профили) | После обновления группы повторно запустить выбор для текущего типа сети |
| Несколько профилей с одинаковым тэгом | Все попадают в пинг-тест; побеждает с наименьшим `ping` |
| Нет сети (network = null) | Опционально останавливать сервис (флаг `stopOnNetworkLoss`) |

---

### 9. Рекомендуемый UX-флоу

```
Пользователь в подписке:
  Профиль A  → теги: [wifi]
  Профиль B  → теги: [wifi]
  Профиль C  → теги: [mobile]
  Профиль D  → теги: [mobile, roaming]

Настройки:
  autoSelectByNetwork    = true
  wifiProxyTag           = "wifi"
  mobileProxyTag         = "mobile"
  autoPingBeforeConnect  = true

Сценарий: телефон переключился с Wi-Fi на мобильную сеть
  1. DefaultNetworkListener.onCapabilitiesChanged → currentNetworkType = MOBILE
  2. NetworkAwareSelector.onNetworkChanged()
  3. Кандидаты: [C, D]  (оба имеют тэг "mobile")
  4. NetworkProxyPinger.pingAndRank([C, D])
     → C: 87ms, D: 210ms
  5. DataStore.selectedProxy = C.id
  6. SagerNet.reloadService()
```

---

### 10. Порядок реализации

1. **`Constants.kt`** — добавить все ключи (п. 2.1)
2. **БД-миграция** в `Migrations.kt` — добавить колонку `networkTags`
3. **`ProxyEntity.kt`** — добавить поле `networkTags`
4. **`DataStore.kt`** — добавить поля (п. 2.2)
5. **`DefaultNetworkListener.kt`** — добавить `NetworkType` + `currentNetworkType` (п. 3)
6. **`NetworkProxyPinger.kt`** — новый файл (п. 4)
7. **`NetworkAwareSelector.kt`** — новый файл (п. 5)
8. **`BaseService.kt`** / **`SagerNet.kt`** — интеграция (п. 6)
9. **`ProfileSettingsActivity.kt`** + `name_preferences.xml` — UI тэгов (п. 1.2)
10. **`global_preferences.xml`** + `strings.xml` — UI настроек (п. 7)
11. **`BootReceiver.kt`** — проверка `autoConnect` (п. 6.3)
12. **Тесты** (п. ниже)

---

### 11. Тесты

- [ ] **Unit** `NetworkProxyPinger`: мок `V2RayTestInstance`, проверить сортировку по пингу и обработку ошибок
- [ ] **Unit** `NetworkAwareSelector`: мок `DefaultNetworkListener` + `DataStore`, проверить выбор профиля по тэгу
- [ ] **Интеграционный**: смена `currentNetworkType` → проверить `DataStore.selectedProxy` и вызов `reloadService()`
- [ ] **UI**: задать тэги профилю → проверить что они сохраняются и отображаются в summary
