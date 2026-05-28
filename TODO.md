# TODO: Механизм смены прокси по типу сети + автоподключение

## Контекст

В репозитории уже существует базовая инфраструктура:
- `RuleEntity.networkType` — поле для фильтрации правил маршрутизации по типу сети (`wifi`, `data`, `bluetooth`, `ethernet`, `usb`, `satellite`)
- `DefaultNetworkListener` — отслеживает смену активной сети через `ConnectivityManager.NetworkCallback`
- `ConfigBuilder` — передаёт `networkType` в конфиг ядра (V2Ray/sing-box)

**Чего не хватает:** автоматической смены активного прокси-профиля при переключении между Wi-Fi и мобильной сетью, а также автоподключения VPN при старте или смене сети.

---

## Задачи

### 1. Привязка прокси-профиля к типу сети

**Цель:** пользователь задаёт разные профили для Wi-Fi и мобильной сети; приложение переключается автоматически.

#### 1.1 Модель данных — `DataStore`
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt`

Добавить поля:
```kotlin
var wifiProxyId by profileCacheStore.long(Key.WIFI_PROXY_ID)       // ID профиля для Wi-Fi
var mobileProxyId by profileCacheStore.long(Key.MOBILE_PROXY_ID)   // ID профиля для Mobile
var perNetworkProxyEnabled by profileCacheStore.boolean(Key.PER_NETWORK_PROXY_ENABLED) // флаг фичи
```

#### 1.2 Константы — `Constants.kt`
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/Constants.kt`

```kotlin
const val WIFI_PROXY_ID = "wifiProxyId"
const val MOBILE_PROXY_ID = "mobileProxyId"
const val PER_NETWORK_PROXY_ENABLED = "perNetworkProxyEnabled"
```

#### 1.3 UI настройки — `SettingsPreferenceFragment`
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt`  
**Файл:** `app/src/main/res/xml/global_preferences.xml`

Добавить секцию «Переключение по сети»:
- Переключатель `perNetworkProxyEnabled` — «Переключать профиль по типу сети»
- `ProfileSelectPreference` для `wifiProxyId` — «Профиль для Wi-Fi»
- `ProfileSelectPreference` для `mobileProxyId` — «Профиль для мобильной сети»

Оба селектора видимы только при включённом переключателе.

---

### 2. Определение текущего типа сети

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/utils/DefaultNetworkListener.kt`

Добавить публичное свойство `currentNetworkType: NetworkType`:

```kotlin
enum class NetworkType { WIFI, MOBILE, OTHER, UNKNOWN }

var currentNetworkType: NetworkType = NetworkType.UNKNOWN
    private set
```

Заполнять в `onCapabilitiesChanged`:
```kotlin
override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
    currentNetworkType = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
        else -> NetworkType.OTHER
    }
    // ... существующий код
}
```

---

### 3. Автопереключение профиля при смене сети

**Новый файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkAwareProfileSwitcher.kt`

```kotlin
object NetworkAwareProfileSwitcher {

    fun start() {
        // подписаться на DefaultNetworkListener
        // при каждом вызове listener'а вызывать onNetworkChanged()
    }

    private fun onNetworkChanged(network: Network?) {
        if (!DataStore.perNetworkProxyEnabled) return
        val targetId = when (DefaultNetworkListener.currentNetworkType) {
            NetworkType.WIFI -> DataStore.wifiProxyId
            NetworkType.MOBILE -> DataStore.mobileProxyId
            else -> return
        }
        if (targetId <= 0L) return
        if (DataStore.selectedProxy == targetId) return

        DataStore.selectedProxy = targetId
        // перезапустить сервис с новым профилем
        SagerNet.reloadService()
    }
}
```

**Интеграция:**
- `NetworkAwareProfileSwitcher.start()` вызвать из `BaseService.Interface.onStartCommand()`
- `SagerNet.reloadService()` — существующий механизм перезапуска (проверить наличие / добавить при необходимости)

---

### 4. Автоподключение VPN

#### 4.1 При старте приложения
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt`

```kotlin
// в onCreate() после инициализации DataStore
if (DataStore.autoConnect && DataStore.selectedProxy > 0) {
    val intent = Intent(this, VpnService::class.java)
    startService(intent)
}
```

Новые ключи в `DataStore`:
```kotlin
var autoConnect by profileCacheStore.boolean(Key.AUTO_CONNECT)
```

#### 4.2 При смене сети (восстановление соединения)
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/bg/NetworkAwareProfileSwitcher.kt`

В `onNetworkChanged()` добавить логику:
```kotlin
// если VPN не запущен, но autoConnect включён — запустить
if (network != null && DataStore.autoConnect && !BaseService.isRunning()) {
    SagerNet.startService()
}
// если сеть пропала — опционально останавливать
if (network == null && DataStore.stopOnNetworkLoss) {
    SagerNet.stopService()
}
```

Новые ключи:
```kotlin
var stopOnNetworkLoss by profileCacheStore.boolean(Key.STOP_ON_NETWORK_LOSS)
```

#### 4.3 Boot receiver — уже существует
**Файл:** `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt`

Убедиться, что `BootReceiver` уважает `autoConnect`. Если нет — добавить проверку `DataStore.autoConnect` перед запуском сервиса.

---

### 5. UI — новые настройки

**Файл:** `app/src/main/res/xml/global_preferences.xml`  
**Файл:** `app/src/main/res/values/strings.xml`

Добавить строки:
```xml
<string name="per_network_proxy">Профиль по типу сети</string>
<string name="per_network_proxy_summary">Автоматически переключать прокси при смене Wi-Fi/мобильной сети</string>
<string name="wifi_proxy">Профиль для Wi-Fi</string>
<string name="mobile_proxy">Профиль для мобильной сети</string>
<string name="auto_connect">Автоподключение</string>
<string name="auto_connect_summary">Подключаться автоматически при старте приложения и появлении сети</string>
<string name="stop_on_network_loss">Отключаться при потере сети</string>
```

---

### 6. Обработка граничных случаев

| Ситуация | Ожидаемое поведение |
|---|---|
| Профиль для данного типа сети не задан | Не переключать, оставить текущий |
| Профиль удалён из БД | Сбросить `wifiProxyId`/`mobileProxyId` в 0, показать уведомление |
| VPN запущен, сеть сменилась | Перезапустить сервис с новым профилем (не обрывать соединение дольше, чем необходимо) |
| `perNetworkProxyEnabled = false` | Switcher бездействует, всё работает как раньше |
| Нет разрешения `ACCESS_FINE_LOCATION` (нужно для SSID) | Не блокировать смену профиля, SSID-фильтр просто не применяется |

---

### 7. Тесты

- [ ] Юнит-тест `NetworkAwareProfileSwitcher`: мок `DefaultNetworkListener`, проверить смену `DataStore.selectedProxy`
- [ ] Интеграционный тест: поднять фейковый VPN-сервис, симулировать `onLost` + `onAvailable`, проверить перезапуск
- [ ] UI-тест: включить `perNetworkProxyEnabled`, выбрать профили, убедиться что селекторы сохраняются

---

## Порядок реализации

1. `Constants.kt` — добавить ключи
2. `DataStore.kt` — добавить поля
3. `DefaultNetworkListener.kt` — добавить `currentNetworkType`
4. `NetworkAwareProfileSwitcher.kt` — новый файл
5. `SagerNet.kt` / `BaseService.kt` — интеграция switcher'а и autoConnect
6. `global_preferences.xml` + `strings.xml` — UI
7. `SettingsPreferenceFragment.kt` — подключить новые preference'ы
8. `BootReceiver.kt` — проверить/добавить `autoConnect`
9. Тесты
