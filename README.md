# JSON Mock Server — plugin para Android Studio

Servidor HTTP local embebido en el IDE que **mockea solo los endpoints que tú eliges**.
Todo lo demás se reenvía tal cual al backend real.

```
App Android ──► http://10.0.2.2:8888 ──► ¿regla activa que coincide?
                                             ├─ sí → responde con tu archivo JSON
                                             └─ no → passthrough al "Backend real" (o 404 si está vacío)
```

## Documentación

- [Manual de usuario](docs/MANUAL_USUARIO.md)
- [Guía técnica del código](docs/GUIA_CODIGO.md)
- [Publicar en JetBrains Marketplace](docs/PUBLICAR_MARKETPLACE.md)

## Compilar e instalar

```bash
./gradlew buildPlugin      # genera build/distributions/JsonMockServer-1.0.0.zip
./gradlew runIde           # abre un Android Studio de prueba con el plugin cargado
./gradlew test             # tests de coincidencia de rutas y del servidor (mock / proxy / 404)
```

Instalación: *Settings → Plugins → ⚙ → Install Plugin from Disk…* y elige el zip.

La plataforma de compilación se define en `gradle.properties` (`platformLocalPath` apunta a tu
Android Studio instalado; si no existe, se descarga `platformVersion`).

## Probarlo en IntelliJ IDEA (u otro IDE de JetBrains)

El plugin solo depende del núcleo de la plataforma, así que funciona en Android Studio y en cualquier
IDE de JetBrains 2024.3+. Una vez publicado, se instala desde el Marketplace de cualquiera de ellos.

```bash
./gradlew runIntelliJ                              # abre tu IntelliJ IDEA (sandbox) con el plugin
./gradlew verifyPlugin -PverifyLocalOnly=true      # compatibilidad solo contra tu IntelliJ local
```

La ruta de IntelliJ se define en `gradle.properties` (`intellijLocalPath`). Para instalarlo en tu
IntelliJ real usa el mismo zip: *Settings → Plugins → ⚙ → Install Plugin from Disk…*

## Uso

1. Abre la Tool Window **JSON Mock Server** (barra derecha).
2. Escribe en **Backend real** la URL base de tu API, p. ej. `https://api.miapp.com`.
3. Pulsa **+** y crea una regla: método, ruta, código HTTP y archivo JSON.
   El botón **Crear** genera `mocks/<METODO>_<ruta>.json` en el proyecto y lo abre en el editor.
4. Pulsa **Iniciar**.
5. Activa o desactiva cada regla con el checkbox. Los cambios se aplican al momento, sin reiniciar,
   y el JSON se relee en cada petición, así que puedes editarlo con el servidor en marcha.

El orden de la tabla define la prioridad: gana la primera regla activa que coincida.

### Sintaxis de rutas
| Patrón | Coincide con |
|---|---|
| `/v1/usuario` | exactamente esa ruta (ignora la query string y la `/` final) |
| `/v1/usuario/{id}` o `/v1/usuario/*` | un segmento cualquiera |
| `/v1/**` | `/v1` y todo lo que cuelgue de él |
| `https://api.miapp.com/v1/usuario` | se usa solo el path |

El método `ANY` acepta cualquier verbo. Cada respuesta lleva la cabecera `X-Mock-Server: mock | proxy | none`.

### Log
En el panel inferior se ve cada petición con su resultado: **MOCK**, **PROXY**, **NOT_FOUND** o **ERROR**.

## Apuntar tu app al mock

La app debe usar la URL del servidor como base URL **solo en debug**:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug   { buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8888/\"") }
        release { buildConfigField("String", "BASE_URL", "\"https://api.miapp.com/\"") }
    }
    buildFeatures { buildConfig = true }
}

// Retrofit
Retrofit.Builder().baseUrl(BuildConfig.BASE_URL)
```

- **Emulador:** `http://10.0.2.2:8888` (10.0.2.2 es el localhost del ordenador).
- **Dispositivo físico por USB:** pulsa **adb reverse** y usa `http://localhost:8888`.
- **Dispositivo por Wi-Fi:** `http://<IP-del-ordenador>:8888` (el servidor escucha en todas las interfaces).

### Permitir HTTP en claro (Android 9+)
`src/debug/res/xml/network_security_config.xml`:
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```
y en `src/debug/AndroidManifest.xml`:
```xml
<application android:networkSecurityConfig="@xml/network_security_config" />
```

## Persistencia
La configuración (puerto, backend, auto-arranque y reglas) se guarda por proyecto en
`.idea/jsonMockServer.xml`. Las rutas de los JSON se guardan relativas al proyecto si están dentro de él,
así que puedes versionar la carpeta `mocks/` y compartirla con el equipo.

## Estructura
```
src/main/kotlin/com/dertoo/jsonmock/
├── model/MockRule.kt              regla + PathPattern (coincidencia de rutas)
├── settings/MockSettingsService.kt PersistentStateComponent → .idea/jsonMockServer.xml
├── server/MockHttpServer.kt       servidor com.sun.net.httpserver + passthrough con java.net.http
├── server/MockServerService.kt    ciclo de vida, eventos (Topic) y auto-arranque
├── adb/AdbReverse.kt              adb reverse tcp:PORT tcp:PORT
└── ui/                            Tool Window, tabla de reglas, diálogo UI DSL, log
```
