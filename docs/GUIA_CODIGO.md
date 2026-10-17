# JSON Mock Server — Guía técnica del código

Este documento explica cómo está construido el plugin, qué hace cada archivo y cómo extenderlo.
Está pensado para alguien que conoce Kotlin pero no necesariamente el IntelliJ Platform SDK.

---

## Índice

1. [Arquitectura](#1-arquitectura)
2. [Estructura del proyecto](#2-estructura-del-proyecto)
3. [Build: Gradle y plataforma](#3-build-gradle-y-plataforma)
4. [plugin.xml](#4-pluginxml)
5. [Modelo: MockRule y PathPattern](#5-modelo-mockrule-y-pathpattern)
6. [Persistencia: MockSettingsService](#6-persistencia-mocksettingsservice)
7. [Servidor: MockHttpServer](#7-servidor-mockhttpserver)
8. [Ciclo de vida: MockServerService](#8-ciclo-de-vida-mockserverservice)
9. [UI](#9-ui)
10. [AppUrlDetector](#10-appurldetector)
11. [JsonCheck](#11-jsoncheck)
12. [AdbReverse](#12-adbreverse)
13. [Hilos y concurrencia](#13-hilos-y-concurrencia)
14. [Tests](#14-tests)
15. [Depuración](#15-depuración)
16. [Cómo extender el plugin](#16-cómo-extender-el-plugin)

---

## 1. Arquitectura

```
┌───────────────────────────── Android Studio (un proyecto abierto) ─────────────────────────────┐
│                                                                                                │
│  UI (EDT)                         Servicios de proyecto                    Hilos del servidor  │
│  ─────────                        ──────────────────────                   ──────────────────  │
│  MockToolWindowFactory                                                                         │
│        │ crea                                                                                  │
│        ▼                                                                                       │
│  MockRulesPanel ──setRules()──►  MockSettingsService ◄──activeRules()──┐                       │
│   │  │   ▲                       (PersistentStateComponent)            │                       │
│   │  │   │                       └─► .idea/jsonMockServer.xml          │                       │
│   │  │   │                                                             │                       │
│   │  └start/stop──────────────►  MockServerService ──crea──►  MockHttpServer ◄── App Android   │
│   │      │                       (Disposable)                 (com.sun.net.httpserver)         │
│   │      └──── MessageBus Topic ◄──────── requestHandled / serverStateChanged                  │
│   │                                                               │                            │
│   └─► MockRuleDialog / MockFiles                                  └─► HttpClient ──► Backend   │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Flujo de una petición

1. La app hace `GET http://10.0.2.2:8888/v1/usuario/42`.
2. `MockHttpServer.handle()` recibe la petición en un hilo del pool `JsonMockServer`.
3. Pide a `MockSettingsService.activeRules()` una **copia** de las reglas activas.
4. Busca la primera regla con `rule.matches(method, path)`.
5. Si hay regla: `serveMock()` aplica el retardo, lee el JSON del disco y responde.
6. Si no hay regla y hay `targetBaseUrl`: `proxy()` reenvía con `java.net.http.HttpClient`.
7. Si no hay ninguna de las dos: responde 404.
8. Publica un `RequestLogEntry` en el `Topic`; el panel lo recibe y lo pinta en el EDT.

---

## 2. Estructura del proyecto

```
JsonMockServer/
├── build.gradle.kts              Build del plugin (IntelliJ Platform Gradle Plugin 2.x)
├── settings.gradle.kts
├── gradle.properties             Versión, grupo y plataforma de compilación
├── gradle/wrapper/               Gradle 9.8.0 (el plugin 2.19 exige Gradle 9+)
├── docs/                         Esta documentación
└── src/
    ├── main/
    │   ├── resources/META-INF/plugin.xml
    │   └── kotlin/com/dertoo/jsonmock/
    │       ├── model/MockRule.kt                 Regla + PathPattern
    │       ├── model/PortInput.kt                Puerto desde un número o una URL
    │       ├── settings/MockSettingsService.kt   Estado persistente
    │       ├── server/MockHttpServer.kt          Servidor HTTP + proxy
    │       ├── server/MockServerService.kt       Ciclo de vida, eventos, auto-arranque
    │       ├── server/PortFinder.kt              Puerto libre de reserva y dueño del puerto
    │       ├── model/AppTarget.kt                URLs a las que debe apuntar la app
    │       ├── model/JsonCheck.kt                Validador JSON con línea/columna del error
    │       ├── detect/AppUrlDetector.kt          Busca URLs base en los archivos de la app
    │       ├── adb/AdbReverse.kt                 adb reverse
    │       └── ui/
    │           ├── MockToolWindowFactory.kt      Punto de entrada de la Tool Window
    │           ├── MockRulesPanel.kt             Panel principal
    │           ├── MockRuleDialog.kt             Diálogo de alta/edición (UI DSL)
    │           ├── DetectUrlsDialog.kt           Elegir backend y URL de la app detectados
    │           ├── PasteJsonDialog.kt            Pegar JSON y crear el archivo
    │           └── MockFiles.kt                  Crear y abrir archivos JSON
    └── test/kotlin/com/dertoo/jsonmock/
        ├── PathPatternTest.kt
        ├── PortFinderTest.kt
        ├── PortInputTest.kt
        ├── AppUrlDetectorTest.kt
        ├── JsonCheckTest.kt
        ├── AppTargetTest.kt
        └── MockHttpServerTest.kt
```

Dependencias externas: **ninguna**. Todo usa la plataforma IntelliJ y el JDK
(`com.sun.net.httpserver`, `java.net.http`).

---

## 3. Build: Gradle y plataforma

### Versiones

| Pieza | Versión | Motivo |
|---|---|---|
| Gradle | 9.8.0 | El IntelliJ Platform Gradle Plugin 2.19 exige Gradle 9+ |
| `org.jetbrains.intellij.platform` | 2.19.0 | Plugin oficial actual (sustituye a `org.jetbrains.intellij` 1.x) |
| Kotlin | 2.4.20 | Debe leer los metadatos de Kotlin de la plataforma 262 |
| JVM toolchain | 21 | Bytecode compatible con IDEs desde 2024.3 |
| `sinceBuild` | 243 | Android Studio / IntelliJ 2024.3 en adelante |
| `untilBuild` | sin límite | Evita bloquear versiones futuras |

### Plataforma de compilación (`gradle.properties`)

```properties
platformLocalPath=/Applications/Android Studio.app
platformVersion=2025.1.1.13
```

`build.gradle.kts` decide así:

```kotlin
val localPath = providers.gradleProperty("platformLocalPath").orNull
if (!localPath.isNullOrBlank() && file(localPath).exists()) {
    local(localPath)                                         // usa tu Android Studio instalado
} else {
    androidStudio(providers.gradleProperty("platformVersion")) // lo descarga
}
```

Con `local()` compilas y ejecutas `runIde` contra exactamente tu IDE, sin descargas.
En CI o en otra máquina, borra o vacía `platformLocalPath` para que use `platformVersion`.

`jvmDefault = NO_COMPATIBILITY` (en `kotlin { compilerOptions }`): sin esto, Kotlin genera en
`MockToolWindowFactory` métodos puente hacia los métodos por defecto de `ToolWindowFactory`
(`getAnchor`, `getIcon`, `manage`…), y el Plugin Verifier los marca como uso de API interna y obsoleta.

`kotlin.stdlib.default.dependency=false`: la stdlib de Kotlin la aporta el IDE; empaquetarla
causaría conflictos de clases.

### Tareas útiles

| Tarea | Qué hace |
|---|---|
| `./gradlew runIde` | Abre un IDE de prueba (sandbox) con el plugin cargado (Android Studio) |
| `./gradlew runIntelliJ` | Lo mismo con tu IntelliJ IDEA (`intellijLocalPath`) |
| `./gradlew test` | Ejecuta los tests unitarios |
| `./gradlew buildPlugin` | Genera `build/distributions/JsonMockServer-<versión>.zip` |
| `./gradlew verifyPluginStructure` | Valida `plugin.xml` y la estructura del zip |
| `./gradlew verifyPlugin` | Comprueba la compatibilidad binaria contra varias versiones de IDE y tu IntelliJ local |
| `./gradlew verifyPlugin -PverifyLocalOnly=true` | Solo contra tu IntelliJ local (rápido, sin descargas) |
| `./gradlew signPlugin` / `publishPlugin` | Firma y publica (ver `PUBLICAR_MARKETPLACE.md`) |

---

## 4. plugin.xml

```xml
<depends>com.intellij.modules.platform</depends>
```

Depender solo de `platform` hace el plugin compatible con **todos** los IDEs de JetBrains,
incluido Android Studio. No se usa ninguna API de Android.

| Extensión | Clase | Función |
|---|---|---|
| `projectService` | `MockSettingsService` | Estado persistente por proyecto |
| `projectService` | `MockServerService` | Servidor y su ciclo de vida |
| `toolWindow` | `MockToolWindowFactory` | Panel lateral derecho "JSON Mock Server" |
| `postStartupActivity` | `MockAutoStartActivity` | Arranca el servidor al abrir el proyecto si `autoStart` |
| `notificationGroup` | id `JSON Mock Server` | Globos de notificación (errores, adb) |

Los servicios se registran en XML en lugar de con `@Service`. Ambos sistemas funcionan; el XML
deja todos los puntos de entrada visibles en un solo archivo.

Los servicios de proyecto se crean de forma **perezosa**: la primera llamada a
`project.getService(...)` los instancia, y la plataforma los destruye al cerrar el proyecto.

---

## 5. Modelo: MockRule y PathPattern

`model/MockRule.kt`

### MockRule

```kotlin
class MockRule() {
    var enabled: Boolean = true
    var method: String = "GET"
    var path: String = ""
    var statusCode: Int = 200
    var jsonFile: String = ""   // relativo al proyecto si está dentro de él
    var delayMs: Int = 0
}
```

- Tiene **constructor vacío y propiedades `var`** porque el `XmlSerializer` de la plataforma lo exige
  para persistirlo. Por eso no es una `data class` con `val`.
- `MockRule(other)` y `copyFrom(other)` hacen copias; el diálogo edita una copia y solo la vuelca
  al pulsar OK, y el servidor trabaja sobre copias.
- `matches(method, path)`: `ANY` acepta cualquier método; si no, compara ignorando mayúsculas.
  Luego delega en `PathPattern`.

### PathPattern

1. `normalize()`:
   - Si recibe una URL completa (`https://host/v1/x`), se queda con el path.
   - Quita la query y el fragmento (`?…`, `#…`).
   - Asegura la `/` inicial y quita la final.
2. Divide patrón y ruta en segmentos.
3. `match()` es un emparejador recursivo:
   - `**` prueba a consumir 0, 1, 2… segmentos de la ruta (backtracking).
   - `*` o `{x}` consumen exactamente un segmento.
   - Cualquier otro segmento debe ser igual (sensible a mayúsculas).

El coste es despreciable con rutas reales (pocos segmentos y pocos `**`).

---

## 6. Persistencia: MockSettingsService

`settings/MockSettingsService.kt`

```kotlin
@State(name = "JsonMockServerSettings", storages = [Storage("jsonMockServer.xml")])
class MockSettingsService(project: Project) : PersistentStateComponent<MockSettingsState>
```

- En un servicio de **proyecto**, `Storage("jsonMockServer.xml")` se guarda en
  `<proyecto>/.idea/jsonMockServer.xml`.
- La plataforma llama a `getState()` cuando guarda (al cerrar, al perder el foco, periódicamente) y
  serializa a XML solo lo que difiere de los valores por defecto.
- `loadState()` copia lo leído sobre el objeto existente con `XmlSerializerUtil.copyBean`.

### MockSettingsState

| Campo | Defecto | Uso |
|---|---|---|
| `port` | 8888 | Puerto del servidor |
| `targetBaseUrl` | `""` | Backend real para el passthrough |
| `autoStart` | false | Arranque al abrir el proyecto |
| `autoFreePort` | true | Si `port` está ocupado, usar el siguiente libre |
| `rules` | `[]` | Lista de `MockRule` (`@XCollection(style = v2)`) |

Ejemplo del XML generado:

```xml
<project version="4">
  <component name="JsonMockServerSettings">
    <option name="targetBaseUrl" value="https://api.miapp.com" />
    <option name="rules">
      <MockRule>
        <option name="path" value="/v1/usuario/{id}" />
        <option name="jsonFile" value="mocks/GET_v1_usuario_id.json" />
      </MockRule>
    </option>
  </component>
</project>
```

### API del servicio

| Método | Quién lo usa | Nota |
|---|---|---|
| `activeRules()` | Servidor (hilos de red) | Copia filtrada bajo `synchronized` |
| `allRules()` | Panel al crearse | Copia de la lista |
| `setRules(list)` | Panel tras cada cambio | Sustituye la lista bajo `synchronized` |
| `resolveJsonFile(path)` | Servidor, `MockFiles` | Relativo → `basePath/path` |
| `toStoredPath(abs)` | Diálogo | Absoluto dentro del proyecto → relativo |

Guardar rutas relativas hace que `.idea/jsonMockServer.xml` y `mocks/` funcionen en cualquier máquina.

---

## 7. Servidor: MockHttpServer

`server/MockHttpServer.kt`

Clase sin dependencias de la plataforma salvo el logger, por eso se puede probar con JUnit puro.
Recibe todo lo que necesita por constructor:

```kotlin
MockHttpServer(
    port,
    executor,               // pool de hilos
    rulesProvider,          // () -> List<MockRule>    (se llama en cada petición)
    targetBaseUrlProvider,  // () -> String           (se llama en cada petición)
    fileResolver,           // (String) -> File
    onRequest,              // (RequestLogEntry) -> Unit
)
```

Las *lambdas* en lugar de valores fijos son lo que hace que **todo se aplique en caliente**.

### `handle(exchange)`

```
regla = rulesProvider().firstOrNull { it.matches(method, uri.path) }
├── regla != null            → serveMock()
├── targetBaseUrl no vacío    → proxy()
└── si no                    → 404 JSON
excepción → 502 JSON + log ERROR
finalmente → onRequest(entry con duración)
```

`exchange.use { }` garantiza que el intercambio se cierra aunque haya excepción.

### Ruta de la app y ruta real

`realPathOf(target, requestPath)` = path del Backend real + ruta pedida
(`https://example.com/api/v1` + `/ruta3` → `/api/v1/ruta3`). Una regla coincide si encaja con
`uri.path` **o** con esa ruta real. Así funcionan las tres formas de escribirla: `/ruta3`,
`/api/v1/ruta3` y `https://example.com/api/v1/ruta3` (`PathPattern.normalize` se queda solo con el path).
El reenvío usa `realUrlOf(target, requestURI)` = Backend real + URI pedida (con la query).

### `serveMock()`

1. Consume el cuerpo de la petición (necesario para que la conexión keep-alive quede limpia).
2. `Thread.sleep(delayMs)` si hay retardo; bloquea solo ese hilo del pool.
3. Si el JSON no existe → 500 con el detalle del archivo.
4. Lee el archivo **en cada petición** y responde con `Content-Type: application/json; charset=utf-8`
   y `X-Mock-Server: mock`.

### `proxy()`

1. Construye `targetBaseUrl + requestURI` (el URI incluye la query).
2. Copia método, cuerpo y cabeceras, salvo las que `HttpClient` prohíbe o son *hop-by-hop*
   (`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`, `Keep-Alive`, `Transfer-Encoding`).
3. `HttpClient` con `Redirect.NEVER`: las redirecciones llegan a la app tal cual, como sin mock.
4. Copia el código, las cabeceras (salvo las *hop-by-hop*) y el cuerpo **en bytes**.
   Si el backend responde con gzip, se reenvía comprimido junto con su `Content-Encoding`.
5. Añade `X-Mock-Server: proxy`.

Timeouts: 15 s de conexión, 60 s por petición.

### `sendBody()`

`sendResponseHeaders(status, length)` usa `-1` cuando no hay cuerpo (HEAD, 204, 304 o cuerpo vacío),
como exige `com.sun.net.httpserver`.

### `RequestLogEntry`

`data class` inmutable con método, URI, código, tipo (`MOCK`, `PROXY`, `NOT_FOUND`, `ERROR`),
duración y un detalle. Es lo que viaja por el `Topic` hasta el log.

---

## 8. Ciclo de vida: MockServerService

`server/MockServerService.kt`

- Pool de hilos: `AppExecutorUtil.createBoundedApplicationPoolExecutor("JsonMockServer", 16)`.
  Usa el pool compartido del IDE con un máximo de 16 peticiones simultáneas.
- `start()` y `stop()` son `@Synchronized`; `server` y `runningPort` son `@Volatile`.
- `start()`:
  1. Lee el puerto preferido (`state.port`). Si ya está en uso, guarda quién lo ocupa con
     `PortFinder.describeOwner()`.
  2. Llama a `PortFinder.startOnFreePort()`, que crea un `MockHttpServer` por cada puerto que prueba
     (el puerto va en el constructor).
  3. Si arrancó en otro puerto, muestra un aviso con el dueño del puerto y las URLs nuevas.
     **No modifica `state.port`**, así que el siguiente arranque vuelve a intentar el preferido.
  4. Si no encontró puerto, muestra un error y devuelve `false`.
- `runningPort` es el puerto real; `isOnFallbackPort` indica si difiere del configurado. La UI los usa
  para la línea de estado y para `adb reverse`.
- `dispose()` (al cerrar el proyecto) detiene el servidor y apaga el pool, liberando el puerto.

### `PortFinder`

| Función | Qué hace |
|---|---|
| `startOnFreePort(preferred, autoFallback, starter)` | Prueba `preferred` y, con fallback, hasta `MAX_TRIES` (50) puertos más. Salta los que `isInUse()` y los que lanzan `BindException` en `starter`. Devuelve el puerto usado o `null`. |
| `isInUse(port)` | Intenta **conectar** a `127.0.0.1:port` (200 ms). Si conecta, alguien escucha. |
| `describeOwner(port)` | `lsof -Fpc` en macOS/Linux, `netstat -ano` + `tasklist` en Windows → `"node (PID 123)"` o `null`. |

**Por qué no basta con intentar el bind:** en macOS y Linux, Java activa `SO_REUSEADDR` en los
sockets de servidor. Con eso, `HttpServer` puede enlazar `0.0.0.0:8888` aunque otro proceso ya escuche
en `127.0.0.1:8888`. El bind "funciona", pero el emulador (`10.0.2.2` → `127.0.0.1` del ordenador) y
`adb reverse` acabarían hablando con el otro proceso. Por eso primero se comprueba con una conexión.

`run()` vuelca la salida del comando a un archivo temporal en lugar de leer el pipe: `netstat` puede
escribir más de lo que cabe en el buffer y bloquearse antes del `waitFor` (timeout de 3 s).

### Eventos: `MockServerListener` + `Topic`

```kotlin
interface MockServerListener {
    fun serverStateChanged(running: Boolean, port: Int) {}
    fun requestHandled(entry: RequestLogEntry) {}
}
```

Se publica con `project.messageBus.syncPublisher(TOPIC)`. El servicio no conoce a la UI: cualquier
componente puede suscribirse. Los eventos llegan en el hilo que publica (hilos del servidor), así que
el suscriptor debe saltar al EDT.

### `MockAutoStartActivity`

`ProjectActivity` (API con corrutinas) registrada como `postStartupActivity`: se ejecuta tras abrir
el proyecto y arranca el servidor si `autoStart` está activo.

---

## 9. UI

### `MockToolWindowFactory`

Implementa `ToolWindowFactory` y `DumbAware` (funciona mientras se indexa).
Crea un `MockRulesPanel`, lo mete en un `Content` y registra el panel como hijo *disposable*
del contenido, para que se limpie al cerrar la ventana.

### `MockRulesPanel`

`SimpleToolWindowPanel` + `Disposable`. Tres partes:

**Cabecera (Kotlin UI DSL)**

- `textField` + `onTextChange()` (un `DocumentAdapter`) y `checkBox` + `whenStateChangedFromUi`:
  escriben directamente en `settings.state` al cambiar, sin botón "Aplicar".
  Se evita `whenTextChangedFromUi` porque es API experimental.
- Campo **Puerto**: `applyPortInput()` usa `PortInput.parse()`, que acepta un número o una URL
  (`http://10.0.2.2:9090/`, `localhost:9090`, `[::1]:3000`…). Si era una URL, sustituye el texto por
  el número con `invokeLater`, porque un `DocumentListener` no puede modificar su propio documento.
- Botones Iniciar/Detener, Reiniciar y adb reverse.
- `statusLabel` con las URLs para emulador y USB.

**Tabla de reglas**

- `TableView<MockRule>` + `ListTableModel` con una lista **propia** (copia de `allRules()`).
- Columnas: `EnabledColumn` (Boolean editable → checkbox) y `TextColumn` (solo lectura).
- `EnabledColumn.setValue` cambia `enabled` y llama a `persist()`.
- `model.addTableModelListener { persist() }`: cualquier alta, baja, movimiento o edición publica la
  lista con `settings.setRules(model.items)`.
- `ToolbarDecorator` aporta +, −, editar y ↑/↓ (`ListTableModel` implementa `EditableModel`),
  más la acción extra **Abrir JSON**.
- `DoubleClickListener` abre el diálogo de edición.

**Log**

- `JBList` sobre `CollectionListModel<Pair<Long, RequestLogEntry>>`.
- `ColoredListCellRenderer` colorea por tipo con `JBColor` (claro/oscuro).
- Se suscribe al `Topic` con `messageBus.connect(this)`; la conexión se cierra sola al
  destruirse el panel. Cada evento se pasa al EDT con `invokeLater(…, project.disposed)`.
- Se limita a 200 entradas (`MAX_LOG`).

### `MockRuleDialog`

`DialogWrapper` con Kotlin UI DSL y *bindings* a una **copia** de la regla (`result`):

| Campo | Componente | Binding |
|---|---|---|
| Método | `comboBox(MockRule.METHODS)` | `bindItem(result::method.toNullableProperty())` |
| Ruta | `textField()` | `bindText(result::path)` |
| Código | `intTextField(100..599)` | `bindIntText(result::statusCode)` |
| JSON | `textFieldWithBrowseButton(descriptor, project)` + botón **Crear** | `bindText(result::jsonFile)` |
| Retardo | `intTextField(0..120_000)` | `bindIntText(result::delayMs)` |
| Activa | `checkBox` | `bindSelected(result::enabled)` |

- `applyFields()` vuelca la UI a `result`; se usa en la validación y en **Crear** para conocer la ruta escrita.
- `doValidate()` exige una ruta no vacía.
- `doOKAction()` convierte la ruta del JSON a relativa con `toStoredPath`.
- Quien abre el diálogo copia `result` sobre la regla original solo si se pulsa OK.

### `MockFiles`

- `createJsonFor(project, method, path)`: crea `mocks/<MÉTODO>_<ruta_saneada>.json` con `{}`
  si no existe (los caracteres no alfanuméricos pasan a `_`) y lo abre.
- `openInEditor()`: `LocalFileSystem.refreshAndFindFileByIoFile` (para que el VFS vea el archivo
  recién creado) + `FileEditorManager.openFile`.

---

### Bloque "Tu app (build debug) debe apuntar a"

- `model/AppTarget.kt` (puro, probado en `AppTargetTest`): con el Backend real y el puerto calcula las URLs
  `http://10.0.2.2:P/` (emulador), `http://localhost:P/` (USB) y `http://<IP LAN>:P/` (Wi-Fi), la URL a la que
  sustituye (`replaces`), si el backend lleva ruta (`backendHasPath`) y la línea `buildConfigField` para Gradle.
- La IP de Wi-Fi es la primera IPv4 privada de una interfaz activa (`NetworkInterface`); si no hay, se oculta la fila.
- `refreshAppTarget()` se llama al cambiar el Backend real, al aplicar un puerto y en cada `updateStatus()`
  (arranque, parada, puerto de reserva). Con el servidor encendido usa `runningPort`, que puede ser el de reserva.
- **Copiar** usa `CopyPasteManager` y confirma con un globo (`JBPopupFactory…createBalloon()`) junto al botón.
- Las etiquetas de URL son `JBLabel.setCopyable(true)`: también se pueden seleccionar con el ratón.

### Puerto: confirmar y reiniciar

- `onCommit()` engancha **Enter** (`ActionListener`) y **perder el foco** (`FocusListener`) del campo Puerto.
- `commitPort()` compara con `committedPort` (el último confirmado). Así salir del campo sin cambiar nada
  no reinicia, y si el servidor está en un puerto de reserva no se reintenta en bucle.
- Si el servidor está encendido, `runServerTask("Reiniciando… en el puerto N")` lo reinicia.
- No se aplica en cada tecla: al escribir "9090" se intentaría arrancar en 9, 90 y 909.

### `runServerTask(title, action)`

Arrancar o reiniciar puede tardar (comprobar el puerto, `lsof`), así que nunca va en el EDT:

1. Línea de estado: icono animado + `title`.
2. `Task.Backgroundable`: barra de progreso del IDE con el mismo texto.
3. `onFinished` (EDT): `updateStatus()` y notificación "escuchando en el puerto N". Si hubo puerto de
   reserva o error, la notificación la da `MockServerService`.
4. `serverTaskRunning` evita dos tareas a la vez (salir del campo pulsando **Reiniciar**).

Iniciar, Reiniciar y el reinicio automático usan esta función.

### Detectar desde la app

```
botón → detectFromApp()
          ├─ estado: "Buscando URLs en los archivos de la app…"
          └─ Task.Backgroundable (cancelable)
                AppUrlDetector.scan(basePath) { rel -> checkCanceled(); text2 = rel }
             onSuccess (EDT) → showDetected(found)
                ├─ vacío → notificación de aviso
                └─ DetectUrlsDialog → OK → rellena backendField/portField → notificación → commitPort()
```

Rellenar los campos con `.text = …` dispara sus `DocumentAdapter`, que guardan en `settings.state`:
la detección pasa por el mismo camino que si el usuario escribiera.

### `DetectUrlsDialog`

- `TableView<Row>`; `Row` = `DetectedUrl` + `use` (`NONE`, `BACKEND`, `APP`).
- Columna **Uso** con `ComboBoxTableRenderer` como renderer y editor. `APP` solo se ofrece si la URL tiene puerto.
- `setValue` mantiene la unicidad (un backend y una URL de app) y refresca la vista previa.
- `choice` calcula `DetectedChoice(targetBaseUrl, port, summary)` con `AppUrlDetector.targetFor()`.
- Doble clic (fuera de **Uso**) → `OpenFileDescriptor` en el archivo y la línea.

### `PasteJsonDialog`

- Prellenado con el portapapeles (`CopyPasteManager`) solo si es un objeto o array JSON válido.
- `DocumentAdapter` en el área → `JsonCheck.validate()` → estado *válido / no válido + línea y columna*.
- `doValidate()`: nombre y contenido no vacíos (bloquea OK).
- `doOKAction()`: si es inválido, pide confirmar; si el archivo existe, pide reemplazar; luego `MockFiles.writeJson()`.
- `MockRuleDialog` asigna `created` al campo JSON (ruta relativa con `toStoredPath`).

### `MockFiles` (nombres)

- `suggestName(method, path)` → `GET_v1_usuario_id`.
- `sanitize(name)` quita `.json` y deja solo `[A-Za-z0-9._-]`. El usuario no puede salir de `mocks/` con `../`.

## 10. AppUrlDetector

`detect/AppUrlDetector.kt`. Lógica pura sobre `java.io.File`, sin plataforma, probada con JUnit.

### Recorrido

- `root.walkTopDown()`, sin entrar en `build`, `out`, `generated`, `intermediates`, `gradle`, `node_modules`
  ni carpetas que empiezan por `.`.
- Archivos: `.kt`, `.kts`, `.java`, `.gradle`, `.properties` y `.xml` dentro de `values*/`.
- Límites: 512 KB por archivo y 20 000 archivos.
- `onFile(rel)` se llama antes de cada archivo: la UI muestra el nombre y puede cancelar lanzando excepción.

### Por cada línea

1. Salta las líneas que empiezan por `//`, `/*`, `*`, `#` o `<!--`.
2. **Nombre**: el primer patrón que encaje:
   `buildConfigField/resValue(…, "NOMBRE", …)` → `<string name="nombre">` → `.baseUrl(` →
   `clave=https://` (properties) → `NOMBRE = "https://` (Kotlin/Java).
3. **URLs**: `https?://[^\s"'<>\`]+`, recortando `)`, `,`, `;`, `.`, `]` y `}` finales.
4. Descarta hosts de ruido (`NOISE_HOSTS`) y URLs que `java.net.URI` no puede leer (p. ej. `https://$host`).

### Variante

- **Gradle**: `trackBlocks()` lleva una pila de bloques por llaves. El nombre de cada bloque es el último
  identificador antes de `{` (`debug {`, `getByName("release") {`, `create("staging") {`). La variante es el
  bloque justo dentro de `buildTypes` o `productFlavors`; dentro de `defaultConfig` es `default`.
- **Resto**: el source set de la ruta (`app/src/debug/...` → `debug`); `main`, `test` y `androidTest` → vacío.

### `DetectedUrl`

`host`, `port`, `path` y `origin` salen de `java.net.URI`. `isLocal` = `10.0.2.2`, `10.0.3.2` (Genymotion),
`localhost`, `127.0.0.1` o una IP privada (10/8, 172.16/12, 192.168/16).

### `suggest(urls)`

- **App**: locales con puerto; más puntos si la variante es `debug` y si tiene nombre.
- **Backend**: no locales y **nunca de una variante de producción** (`isProductionVariant`: `release`,
  `prod`, `production`, `store`), para no mandar el tráfico de desarrollo a producción. Puntuación:
  - +8 si tiene el mismo nombre que la URL de la app (`BASE_URL` de un flavor `dev` frente al de debug).
  - +4 si el nombre contiene `url`, `host` o `api`.
  - +3 si es `debug`; +2 si es otro flavor (`dev`, `staging`); +1 sin variante o `default`.
  - +1 si está en Gradle.
- Si no queda candidato, no se propone backend. `DetectUrlsDialog` lo explica y, si el usuario elige a mano
  una URL de producción, muestra un aviso.

### `targetFor(backend, app)`

La app manda al mock la ruta que va **después** de su URL de debug. Si la de release tiene una ruta extra,
esa ruta va en el target. Se quita del final de la ruta del backend la ruta de la URL de debug (tabla en el
manual, sección 4.6).

## 11. JsonCheck

`model/JsonCheck.kt`. Parser de descenso recursivo que **solo valida** (no construye objetos):
`value → obj | arr | str | num | true/false/null`. Ante un error lanza `ParseError` con línea y columna
calculadas desde el índice. Se escribió propio porque no hay una librería JSON garantizada en el
classpath de todos los IDEs (Gson y Jackson son internos de la plataforma).

## 12. AdbReverse

`adb/AdbReverse.kt`

1. Busca `platform-tools/adb` en este orden: `sdk.dir` de `local.properties` del proyecto,
   `ANDROID_HOME`, `ANDROID_SDK_ROOT` y la ruta por defecto del sistema
   (`~/Library/Android/sdk`, `%LOCALAPPDATA%\Android\Sdk` o `~/Android/Sdk`).
2. Ejecuta `adb reverse tcp:PORT tcp:PORT` con `GeneralCommandLine` + `ExecUtil` (timeout 15 s).
3. Devuelve `Result<String>`; el panel lo ejecuta con barra de progreso modal y notifica el resultado.

---

## 13. Hilos y concurrencia

| Hilo | Qué corre ahí | Reglas |
|---|---|---|
| **EDT** (UI) | Panel, diálogos, tabla, log | Nunca bloquear; todo cambio de Swing aquí |
| **Tareas en segundo plano** (`Task.Backgroundable`) | Iniciar/reiniciar el servidor, detectar URLs | Muestran progreso; vuelven al EDT en `onSuccess`/`onFinished` |
| **Pool `JsonMockServer`** | `handle()`, `serveMock()`, `proxy()` | Puede bloquear (retardo, red); nunca toca Swing |
| Hilo de `HttpServer` | Acepta conexiones y delega al pool | — |
| Corrutina de arranque | `MockAutoStartActivity` | Solo llama a `start()` |

Decisiones:

- **El servidor nunca recorre la lista viva de la UI.** `activeRules()` hace una copia bajo
  `synchronized(state)`, y la UI sustituye la lista entera con `setRules()` bajo el mismo lock.
  Así no puede haber `ConcurrentModificationException`.
- Las reglas son objetos mutables compartidos: un `enabled` que cambia mientras se copia es una
  carrera benigna (la petición ve el valor anterior o el nuevo).
- `targetBaseUrl` y `port` se leen sin lock: son referencias/valores atómicos y la peor consecuencia
  es que una petición en curso use el valor previo.
- Los eventos del servidor se pasan al EDT con `invokeLater`, con `project.disposed` como condición
  de caducidad para no tocar una UI ya destruida.

---

## 14. Tests

```bash
./gradlew test
```

| Test | Qué cubre |
|---|---|
| `PortFinderTest` | Puerto libre → se usa el preferido; ocupado en loopback → `isInUse` y salto al siguiente; `BindException` → siguiente; sin fallback → `null`; `describeOwner` identifica al propio proceso de test |
| `AppUrlDetectorTest` | Proyecto Android de ejemplo en un directorio temporal: nombre, variante y línea; ruido, comentarios y `build/` ignorados; sugerencias antes y después de cambiar debug (nunca producción); flavors `dev`/`prod`; variante por source set; ajuste de ruta del backend; hosts locales |
| `AppTargetTest` | URLs de emulador, USB y Wi-Fi; backend con ruta y solo dominio; puerto de reserva; sin backend; línea de Gradle |
| `JsonCheckTest` | Documentos válidos e inválidos, posición del error, nombres de archivo (`suggestName`, `sanitize`) |
| `PortInputTest` | Número, URLs de app con y sin esquema, IPv6, credenciales; sin puerto o fuera de rango → `null` |
| `PathPatternTest` | Ruta exacta, query y `/` final, `{id}` y `*`, `**`, URL completa, método y `ANY` |
| `MockHttpServerTest` | Servidor real en un puerto libre contra un "backend" local: regla activa → mock (201 + cuerpo + cabecera), sin regla → proxy (método y ruta conservados), regla desactivada en caliente → proxy, sin target → 404 |

`MockHttpServerTest` no necesita la plataforma: inyecta un `Executors.newCachedThreadPool()`
y lambdas sobre listas locales. Es el patrón a seguir para probar lógica de red.

Los resultados quedan en `build/reports/tests/test/index.html`.

---

## 15. Depuración

- **`./gradlew runIde`** abre un IDE sandbox con el plugin. Lánzalo en modo *Debug* desde IntelliJ
  (panel Gradle → `intellij platform` → `runIde` → Debug) para usar *breakpoints*.
- La sandbox vive en `build/idea-sandbox/`; bórrala para empezar con un IDE limpio.
- Los logs del plugin (`thisLogger()`) van a `idea.log` del sandbox:
  *Help → Show Log in Finder/Explorer* dentro del IDE de prueba.
- Probar el servidor sin app:

```bash
curl -i http://localhost:8888/v1/usuario/42
curl -i -X POST http://localhost:8888/v1/login -H 'Content-Type: application/json' -d '{"u":"a"}'
```

La cabecera `X-Mock-Server` indica qué rama atendió la petición.

---

## 16. Cómo extender el plugin

### Añadir un campo a la regla (p. ej. cabeceras de respuesta)

1. `MockRule`: añade `var responseHeaders: String = ""` y cópialo en `copyFrom`.
2. `MockRuleDialog`: añade una fila `row("Cabeceras:") { expandableTextField().bindText(result::responseHeaders) }`.
3. `MockHttpServer.serveMock()`: parsea y añade a `exchange.responseHeaders`.
4. Opcional: una columna en `MockRulesPanel`.

La persistencia no requiere cambios: el `XmlSerializer` recoge la nueva propiedad sola.

### Coincidencia por cuerpo o query (útil para GraphQL)

Añade `var bodyContains: String = ""` a `MockRule` y amplía `matches()` para recibir el cuerpo.
En `handle()` lee el cuerpo **antes** de buscar la regla y pásalo a `serveMock()`/`proxy()`
(hoy cada rama lo lee por su cuenta).

### Varios JSON por regla (escenarios)

Cambia `jsonFile` por una lista y un índice "activo", y añade en la tabla un combo para elegirlo.

### Grabar respuestas reales como mocks

En `proxy()`, si un flag "grabar" está activo, guarda `response.body()` en `mocks/` y crea una regla
desactivada. Hazlo desde el hilo del servidor solo para escribir el archivo; añade la regla en el EDT
con `invokeLater`.

### Servidor HTTPS

`com.sun.net.httpserver.HttpsServer` + un `SSLContext` con un certificado propio. La app tendría que
confiar en ese certificado (`network_security_config` con `<certificates src="@raw/…"/>`).

### Convenciones

- Toda la lógica de red, en clases sin dependencias de la plataforma, para poder probarla con JUnit.
- Cambios de Swing solo en el EDT; trabajo bloqueante solo fuera de él.
- Toda la comunicación servidor → UI, por el `Topic`.
