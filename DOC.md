# TwedMediaInfo 1.0.0 — Documentación de la API

Este documento explica **cómo usar** TwedMediaInfo 1.0.0, con ejemplos en **Java** y **Kotlin**. La introducción general, la instalación, las atribuciones y la distribución están en el [README](README.md); los formatos y catálogos soportados, en [SUPPORTED](SUPPORTED.md).

## Contenido

1. [Introducción](#1-introducción)
2. [Inicio rápido](#2-inicio-rápido)
3. [Abrir un archivo](#3-abrir-un-archivo)
4. [Consultar información](#4-consultar-información)
5. [Estados y diagnóstico](#5-estados-y-diagnóstico)
6. [Ciclo de vida e hilos](#6-ciclo-de-vida-e-hilos)
7. [Referencia de la API](#7-referencia-de-la-api)
8. [Catálogos de parámetros](#8-catálogos-de-parámetros)
9. [Limitaciones conocidas](#9-limitaciones-conocidas)
10. [Arquitectura de alto nivel](#10-arquitectura-de-alto-nivel)
11. [Compatibilidad](#11-compatibilidad)
12. [Migración desde 0.1.0](#12-migración-desde-010)
13. [Licencia](#13-licencia)

---

## 1. Introducción

TwedMediaInfo es una biblioteca para Android, con API Java, que permite analizar archivos multimedia y consultar sus metadatos e información técnica (por ejemplo, formato, duración, resolución o canales de audio).

Es una adaptación/integración para Android basada en [MediaInfoLib](https://github.com/MediaArea/MediaInfoLib), desarrollada por MediaArea.net SARL. TwedMediaInfo no reimplementa el análisis: lo delega en MediaInfoLib 26.05, enlazada de forma estática en la biblioteca nativa `libtwedmediainfo.so`.

La versión 1.0.0 soporta oficialmente:

- **Rutas físicas:** `open(String)` y `open(File)`.
- **`Uri`:** `content://` (Storage Access Framework, MediaStore, providers propios) y `file://` con `open(Uri)`.
- **Estados:** motivo de error (`getLastError()`), formato reconocido (`isRecognized()`), análisis parcial (`isPartial()`) y diagnóstico de campos vacíos (`getFieldStatus(...)`).

La idea central es siempre la misma:

```text
crear instancia  →  open(...)  →  consultar (get / getGeneral / countStreams)  →  close()
```

---

## 2. Inicio rápido

Declara la dependencia como se explica en el [README](README.md#instalación) y usa la clase `TwedMediaInfo` dentro de un `try-with-resources` (Java) o un `use { }` (Kotlin), **en un hilo de fondo**.

**Java**

```java
try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
    if (mediaInfo.open("/ruta/al/archivo.mp4")) {
        String format = mediaInfo.getGeneral(General.FORMAT);
        String duration = mediaInfo.getGeneral(General.DURATION_STRING);
    } else {
        Log.w("App", "No se abrió: " + mediaInfo.getLastError());
    }
}
```

**Kotlin**

```kotlin
TwedMediaInfo().use { mediaInfo ->
    if (mediaInfo.open("/ruta/al/archivo.mp4")) {
        val format = mediaInfo.getGeneral(General.FORMAT)
        val duration = mediaInfo.getGeneral(General.DURATION_STRING)
    } else {
        Log.w("App", "No se abrió: ${mediaInfo.lastError}")
    }
}
```

Imports usados en los ejemplos de este documento:

```java
import com.twedmediainfo.android.FieldStatus;
import com.twedmediainfo.android.StreamKind;
import com.twedmediainfo.android.TwedMediaInfo;
import com.twedmediainfo.android.TwedMediaInfoError;
import com.twedmediainfo.android.parameters.Audio;
import com.twedmediainfo.android.parameters.General;
import com.twedmediainfo.android.parameters.Video;
```

---

## 3. Abrir un archivo

`open(...)` devuelve `true` si el archivo se abrió y `false` si no. **Nunca lanza excepciones por fallos esperados** (archivo inexistente, sin permiso, vacío, etc.): el motivo se consulta con `getLastError()` ([sección 5](#5-estados-y-diagnóstico)). Solo lanza `IllegalStateException` por mal uso: instancia ya cerrada, o `open(Uri)` en una instancia creada sin `Context`.

Si ya había un archivo abierto, `open(...)` lo cierra antes de abrir el nuevo.

Todos los `open(...)` son **bloqueantes**: llámalos desde un hilo de fondo ([sección 6](#6-ciclo-de-vida-e-hilos)).

### 3.1 Ruta física (`String`)

Requiere la ruta absoluta de un archivo al que tu app tenga acceso (almacenamiento propio de la app, o una ruta compartida si tienes el permiso correspondiente).

**Java**

```java
try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
    String path = new File(context.getFilesDir(), "video.mp4").getAbsolutePath();
    if (mediaInfo.open(path)) {
        String format = mediaInfo.getGeneral(General.FORMAT);
    } else {
        Log.w("App", mediaInfo.getLastError() + ": " + mediaInfo.getLastErrorMessage());
    }
}
```

**Kotlin**

```kotlin
TwedMediaInfo().use { mediaInfo ->
    val path = File(context.filesDir, "video.mp4").absolutePath
    if (mediaInfo.open(path)) {
        val format = mediaInfo.getGeneral(General.FORMAT)
    } else {
        Log.w("App", "${mediaInfo.lastError}: ${mediaInfo.lastErrorMessage}")
    }
}
```

Con rutas, el error se determina con el `errno` real del sistema: «no existe» (`FILE_NOT_FOUND`), «sin permiso» (`PERMISSION_DENIED`), «es un directorio» (`INVALID_ARGUMENT`) o «archivo vacío» (`EMPTY_FILE`). Si tu app no puede acceder a la ruta (por ejemplo, almacenamiento compartido con restricciones del sistema), usa un `Uri` ([sección 3.3](#33-uri)).

### 3.2 `File`

`open(File)` equivale a `open(file.getAbsolutePath())`.

**Java**

```java
try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
    File file = new File(context.getCacheDir(), "audio.flac");
    if (mediaInfo.open(file)) {
        int audioStreams = mediaInfo.countStreams(StreamKind.AUDIO);
    }
}
```

**Kotlin**

```kotlin
TwedMediaInfo().use { mediaInfo ->
    val file = File(context.cacheDir, "audio.flac")
    if (mediaInfo.open(file)) {
        val audioStreams = mediaInfo.countStreams(StreamKind.AUDIO)
    }
}
```

### 3.3 `Uri`

`open(Uri)` requiere crear la instancia con **`TwedMediaInfo(Context)`**. Soporta:

- **`content://`**: MediaStore, Storage Access Framework (`ACTION_OPEN_DOCUMENT`) y providers propios que entreguen un descriptor de archivo de lectura.
- **`file://`**: se resuelve como una ruta física (equivale a `open(uri.getPath())`).

**Storage Access Framework — Kotlin**

```kotlin
// En tu Activity o Fragment
private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
        thread {   // kotlin.concurrent.thread
            TwedMediaInfo(applicationContext).use { mi ->
                if (!mi.open(uri)) {
                    Log.w("App", "No se abrió: ${mi.lastError} - ${mi.lastErrorMessage}")
                    return@use
                }
                if (!mi.isRecognized) return@use
                val format = mi.getGeneral(General.FORMAT)
                val duration = mi.getGeneral(General.DURATION_STRING)
                if (mi.isPartial) Log.i("App", "Resultado parcial: la fuente no permite saltos")
            }
        }
    }
}

// Para lanzarlo:
picker.launch(arrayOf("audio/*", "video/*"))
```

**Storage Access Framework — Java**

```java
// En tu Activity o Fragment
private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(),
        uri -> {
            if (uri == null) return;
            new Thread(() -> {
                try (TwedMediaInfo mi = new TwedMediaInfo(getApplicationContext())) {
                    if (!mi.open(uri)) {
                        Log.w("App", "No se abrió: " + mi.getLastError()
                                + " - " + mi.getLastErrorMessage());
                        return;
                    }
                    if (!mi.isRecognized()) return;
                    String format = mi.getGeneral(General.FORMAT);
                    String duration = mi.getGeneral(General.DURATION_STRING);
                    if (mi.isPartial()) {
                        Log.i("App", "Resultado parcial: la fuente no permite saltos");
                    }
                }
            }).start();
        });

// Para lanzarlo:
picker.launch(new String[]{"audio/*", "video/*"});
```

**MediaStore — Java**

```java
Uri uri = ContentUris.withAppendedId(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId);

new Thread(() -> {
    try (TwedMediaInfo mi = new TwedMediaInfo(context)) {
        if (mi.open(uri)) {
            String format = mi.getGeneral(General.FORMAT);
            int width = mi.getInt(StreamKind.VIDEO, 0, Video.WIDTH, 0);
        } else {
            Log.w("App", "No se abrió: " + mi.getLastError());
        }
    }
}).start();
```

**MediaStore — Kotlin**

```kotlin
val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)

thread {
    TwedMediaInfo(context).use { mi ->
        if (mi.open(uri)) {
            val format = mi.getGeneral(General.FORMAT)
            val width = mi.getInt(StreamKind.VIDEO, 0, Video.WIDTH, 0)
        } else {
            Log.w("App", "No se abrió: ${mi.lastError}")
        }
    }
}
```

**`file://` — Java**

```java
try (TwedMediaInfo mi = new TwedMediaInfo(context)) {
    Uri uri = Uri.fromFile(new File("/storage/emulated/0/Movies/video.mp4"));
    boolean ok = mi.open(uri);
}
```

**`file://` — Kotlin**

```kotlin
TwedMediaInfo(context).use { mi ->
    val uri = Uri.fromFile(File("/storage/emulated/0/Movies/video.mp4"))
    val ok = mi.open(uri)
}
```

#### Cómo funciona `open(Uri)`

Obtiene un `ParcelFileDescriptor` mediante `ContentResolver`, lee el contenido en bloques de 64 KiB y se lo entrega a MediaInfo. Si MediaInfo necesita otra posición del archivo (por ejemplo, un MP4 con el átomo `moov` al final), se hace un *seek* y se informa a MediaInfo del nuevo offset sobre la misma instancia nativa.

- **Permisos:** tu app debe tener acceso de lectura al `Uri` (el que concede el selector de archivos, o el permiso de medios correspondiente para MediaStore). Si no, `getLastError()` devuelve `PERMISSION_DENIED`.
- **Interrupción:** respeta `Thread.interrupt()`; si el hilo se interrumpe, devuelve `false` con `TwedMediaInfoError.CANCELLED`.
- **Fuentes que no permiten saltos** (por ejemplo, un pipe): si MediaInfo necesita un salto que la fuente no puede atender, no es un error; el resultado es **parcial** y `isPartial()` devuelve `true`.
- **Tamaño desconocido:** si el provider no informa el tamaño, se analiza igualmente.

---

## 4. Consultar información

Una vez abierto el archivo, la información se consulta por tipo de stream, número de stream y nombre del parámetro:

```text
get(streamKind, streamNumber, parameter)
     │           │             └─ nombre del parámetro (por ejemplo, Video.WIDTH)
     │           └─ índice del stream, base 0
     └─ tipo de stream (StreamKind.*)
```

`StreamKind` define los tipos de stream (corresponden al enum `stream_t` de MediaInfoLib):

| Constante | Valor | Catálogo de parámetros |
|---|---|---|
| `StreamKind.GENERAL` | 0 | `General` |
| `StreamKind.VIDEO` | 1 | `Video` |
| `StreamKind.AUDIO` | 2 | `Audio` |
| `StreamKind.TEXT` | 3 | `Text` |
| `StreamKind.OTHER` | 4 | `Other` |
| `StreamKind.IMAGE` | 5 | `Image` |
| `StreamKind.MENU` | 6 | No hay clase de catálogo |

- Los índices de stream son **base 0**. Para saber cuántos hay de un tipo, usa `countStreams(streamKind)`; los índices válidos van de `0` a `count - 1`.
- El stream General es único: `getGeneral(parameter)` equivale a `get(StreamKind.GENERAL, 0, parameter)`.
- Un valor vacío (`""`) significa que no hay dato. Con la instancia viva pero sin archivo abierto, `get(...)` devuelve `""` y `countStreams(...)` devuelve `0`. Un `streamKind` fuera de rango, un `streamNumber` negativo o un parámetro `null` tampoco lanzan: devuelven `""` / `0`. Para saber **por qué** está vacío, usa [`getFieldStatus(...)`](#53-fieldstatus-por-qué-un-campo-no-tiene-valor).

### 4.1 General, Video y Audio

**Java**

```java
import com.twedmediainfo.android.StreamKind;
import com.twedmediainfo.android.TwedMediaInfo;
import com.twedmediainfo.android.parameters.Audio;
import com.twedmediainfo.android.parameters.General;
import com.twedmediainfo.android.parameters.Video;

import java.io.File;

public final class MediaInspector {

    // Llamar desde un hilo de fondo.
    public static void inspect(File file) {
        try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
            if (!mediaInfo.open(file)) {
                System.out.println("No se pudo abrir el archivo: " + mediaInfo.getLastError()
                        + " (" + mediaInfo.getLastErrorMessage() + ")");
                return;
            }
            if (!mediaInfo.isRecognized()) {
                System.out.println("Formato no reconocido");
                return;
            }

            // General
            String format = mediaInfo.getGeneral(General.FORMAT);
            String duration = mediaInfo.getGeneral(General.DURATION_STRING);
            String fileSize = mediaInfo.getGeneral(General.FILE_SIZE);
            System.out.println("Formato: " + format + ", duración: " + duration
                    + ", tamaño (bytes): " + fileSize);

            // Video
            int videoCount = mediaInfo.countStreams(StreamKind.VIDEO);
            for (int i = 0; i < videoCount; i++) {
                String vFormat = mediaInfo.get(StreamKind.VIDEO, i, Video.FORMAT);
                int width = mediaInfo.getInt(StreamKind.VIDEO, i, Video.WIDTH, 0);
                int height = mediaInfo.getInt(StreamKind.VIDEO, i, Video.HEIGHT, 0);
                String fps = mediaInfo.get(StreamKind.VIDEO, i, Video.FRAME_RATE_STRING);
                System.out.println("Video " + i + ": " + vFormat + " " + width + "x" + height
                        + " @ " + fps);
            }

            // Audio
            int audioCount = mediaInfo.countStreams(StreamKind.AUDIO);
            for (int i = 0; i < audioCount; i++) {
                String aFormat = mediaInfo.get(StreamKind.AUDIO, i, Audio.FORMAT);
                String channels = mediaInfo.get(StreamKind.AUDIO, i, Audio.CHANNELS);
                String bitrate = mediaInfo.get(StreamKind.AUDIO, i, Audio.BITRATE_STRING);
                String language = mediaInfo.get(StreamKind.AUDIO, i, Audio.LANGUAGE_STRING);
                System.out.println("Audio " + i + ": " + aFormat + ", canales: " + channels
                        + ", bitrate: " + bitrate + ", idioma: " + language);
            }
        }
    }
}
```

**Kotlin**

```kotlin
import com.twedmediainfo.android.StreamKind
import com.twedmediainfo.android.TwedMediaInfo
import com.twedmediainfo.android.parameters.Audio
import com.twedmediainfo.android.parameters.General
import com.twedmediainfo.android.parameters.Video
import java.io.File

// Llamar desde un hilo de fondo.
fun inspect(file: File) {
    TwedMediaInfo().use { mediaInfo ->
        if (!mediaInfo.open(file)) {
            println("No se pudo abrir el archivo: ${mediaInfo.lastError} (${mediaInfo.lastErrorMessage})")
            return
        }
        if (!mediaInfo.isRecognized) {
            println("Formato no reconocido")
            return
        }

        // General
        val format = mediaInfo.getGeneral(General.FORMAT)
        val duration = mediaInfo.getGeneral(General.DURATION_STRING)
        val fileSize = mediaInfo.getGeneral(General.FILE_SIZE)
        println("Formato: $format, duración: $duration, tamaño (bytes): $fileSize")

        // Video
        for (i in 0 until mediaInfo.countStreams(StreamKind.VIDEO)) {
            val vFormat = mediaInfo.get(StreamKind.VIDEO, i, Video.FORMAT)
            val width = mediaInfo.getInt(StreamKind.VIDEO, i, Video.WIDTH, 0)
            val height = mediaInfo.getInt(StreamKind.VIDEO, i, Video.HEIGHT, 0)
            val fps = mediaInfo.get(StreamKind.VIDEO, i, Video.FRAME_RATE_STRING)
            println("Video $i: $vFormat ${width}x$height @ $fps")
        }

        // Audio
        for (i in 0 until mediaInfo.countStreams(StreamKind.AUDIO)) {
            val aFormat = mediaInfo.get(StreamKind.AUDIO, i, Audio.FORMAT)
            val channels = mediaInfo.get(StreamKind.AUDIO, i, Audio.CHANNELS)
            val bitrate = mediaInfo.get(StreamKind.AUDIO, i, Audio.BITRATE_STRING)
            val language = mediaInfo.get(StreamKind.AUDIO, i, Audio.LANGUAGE_STRING)
            println("Audio $i: $aFormat, canales: $channels, bitrate: $bitrate, idioma: $language")
        }
    }
}
```

### 4.2 Text, Image y Other

Los streams `TEXT` (subtítulos), `IMAGE` y `OTHER` (capítulos, timecodes, etc.) se consultan con los mismos métodos.

**Java**

```java
import com.twedmediainfo.android.StreamKind;
import com.twedmediainfo.android.TwedMediaInfo;
import com.twedmediainfo.android.parameters.Image;
import com.twedmediainfo.android.parameters.Other;
import com.twedmediainfo.android.parameters.Text;

try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
    if (mediaInfo.open(file)) {
        // Subtítulos
        for (int i = 0; i < mediaInfo.countStreams(StreamKind.TEXT); i++) {
            String textFormat = mediaInfo.get(StreamKind.TEXT, i, Text.FORMAT);
            String textLanguage = mediaInfo.get(StreamKind.TEXT, i, Text.LANGUAGE_STRING);
        }

        // Imágenes
        for (int i = 0; i < mediaInfo.countStreams(StreamKind.IMAGE); i++) {
            String imgFormat = mediaInfo.get(StreamKind.IMAGE, i, Image.FORMAT);
            String imgWidth = mediaInfo.get(StreamKind.IMAGE, i, Image.WIDTH);
            String imgHeight = mediaInfo.get(StreamKind.IMAGE, i, Image.HEIGHT);
            String bitDepth = mediaInfo.get(StreamKind.IMAGE, i, Image.BIT_DEPTH_STRING);
        }

        // Otros (capítulos, timecodes, etc.)
        for (int i = 0; i < mediaInfo.countStreams(StreamKind.OTHER); i++) {
            String otherType = mediaInfo.get(StreamKind.OTHER, i, Other.TYPE);
        }
    }
}
```

**Kotlin**

```kotlin
import com.twedmediainfo.android.StreamKind
import com.twedmediainfo.android.TwedMediaInfo
import com.twedmediainfo.android.parameters.Other
import com.twedmediainfo.android.parameters.Text
import com.twedmediainfo.android.parameters.Image as ImageParams

TwedMediaInfo().use { mediaInfo ->
    if (mediaInfo.open(file)) {
        // Subtítulos
        for (i in 0 until mediaInfo.countStreams(StreamKind.TEXT)) {
            val textFormat = mediaInfo.get(StreamKind.TEXT, i, Text.FORMAT)
            val textLanguage = mediaInfo.get(StreamKind.TEXT, i, Text.LANGUAGE_STRING)
        }

        // Imágenes
        for (i in 0 until mediaInfo.countStreams(StreamKind.IMAGE)) {
            val imgFormat = mediaInfo.get(StreamKind.IMAGE, i, ImageParams.FORMAT)
            val imgWidth = mediaInfo.get(StreamKind.IMAGE, i, ImageParams.WIDTH)
            val imgHeight = mediaInfo.get(StreamKind.IMAGE, i, ImageParams.HEIGHT)
            val bitDepth = mediaInfo.get(StreamKind.IMAGE, i, ImageParams.BIT_DEPTH_STRING)
        }

        // Otros (capítulos, timecodes, etc.)
        for (i in 0 until mediaInfo.countStreams(StreamKind.OTHER)) {
            val otherType = mediaInfo.get(StreamKind.OTHER, i, Other.TYPE)
        }
    }
}
```

> En Android, el nombre `Image` puede coincidir con otras clases (por ejemplo, `android.media.Image`). Si te ocurre, importa el catálogo con un alias, como en el ejemplo de Kotlin, o usa el nombre completo `com.twedmediainfo.android.parameters.Image`.

### 4.3 Consultar un parámetro por nombre

Las constantes de los catálogos son `String` con el nombre del parámetro, así que estas consultas son equivalentes. Los parámetros que no estén en los catálogos se consultan igual, pasando su nombre.

**Java**

```java
String a = mediaInfo.get(StreamKind.VIDEO, 0, Video.FRAME_RATE_STRING);
String b = mediaInfo.get(StreamKind.VIDEO, 0, "FrameRate/String");
```

**Kotlin**

```kotlin
val a = mediaInfo.get(StreamKind.VIDEO, 0, Video.FRAME_RATE_STRING)
val b = mediaInfo.get(StreamKind.VIDEO, 0, "FrameRate/String")
```

### 4.4 Getters tipados

`has`, `getInt`, `getLong` y `getDouble` evitan parsear a mano. **Nunca lanzan** por valor ausente o no numérico: devuelven el valor por defecto que indiques.

- El parseo no depende del locale (`"29.970"` siempre usa punto decimal).
- `getInt` y `getLong` aceptan decimales y los **truncan** (`"29.970"` → `29`).
- Valores compuestos (`"1 / 2"`), no finitos o fuera del rango del tipo devuelven el valor por defecto.
- `getGeneralInt`, `getGeneralLong` y `getGeneralDouble` son atajos para el stream General.

**Java**

```java
boolean hasTitle = mediaInfo.has(StreamKind.GENERAL, 0, General.TITLE);

int width = mediaInfo.getInt(StreamKind.VIDEO, 0, Video.WIDTH, 0);
double fps = mediaInfo.getDouble(StreamKind.VIDEO, 0, Video.FRAME_RATE, 0.0);
long sizeBytes = mediaInfo.getGeneralLong(General.FILE_SIZE, 0L);
```

**Kotlin**

```kotlin
val hasTitle = mediaInfo.has(StreamKind.GENERAL, 0, General.TITLE)

val width = mediaInfo.getInt(StreamKind.VIDEO, 0, Video.WIDTH, 0)
val fps = mediaInfo.getDouble(StreamKind.VIDEO, 0, Video.FRAME_RATE, 0.0)
val sizeBytes = mediaInfo.getGeneralLong(General.FILE_SIZE, 0L)
```

### 4.5 Versiones de las bibliotecas embebidas

Son métodos estáticos; el valor se consulta una sola vez a la capa nativa y queda en caché.

**Java**

```java
String mediaInfoVersion = TwedMediaInfo.getMediaInfoVersion();
String zenLibVersion = TwedMediaInfo.getZenLibVersion();
```

**Kotlin**

```kotlin
val mediaInfoVersion = TwedMediaInfo.getMediaInfoVersion()
val zenLibVersion = TwedMediaInfo.getZenLibVersion()
```

---

## 5. Estados y diagnóstico

TwedMediaInfo distingue **qué pasó** en cada etapa: abrir, reconocer el formato, análisis completo o parcial y valor de cada campo.

| Situación | Cómo se informa |
|---|---|
| El archivo no se pudo abrir | `open()` devuelve `false` + `getLastError()` |
| Se abrió, pero el formato no se reconoce | `open()` devuelve `true`, `isRecognized() == false` |
| Se abrió, pero el análisis pudo quedar incompleto | `isPartial() == true` |
| El stream consultado no existe en el archivo | `FieldStatus.STREAM_NOT_PRESENT` |
| El nombre del campo no existe para ese tipo de stream | `FieldStatus.UNKNOWN_PARAMETER` |
| Campo válido, stream existe, sin valor | `FieldStatus.NO_VALUE` |

### 5.1 Por qué falló `open(...)`

Cuando `open(...)` devuelve `false`, tres métodos explican el motivo:

- `getLastError()` → `TwedMediaInfoError`. `NONE` si el último `open` tuvo éxito.
- `getLastErrorMessage()` → mensaje legible. **Nunca incluye la ruta completa ni el Uri completo.** Cadena vacía si `NONE`.
- `getLastException()` → la causa original, o `null`. Atención: el mensaje de esa excepción lo genera el sistema y puede incluir rutas.

| `TwedMediaInfoError` | Cuándo ocurre |
|---|---|
| `NONE` | El último `open` tuvo éxito |
| `INVALID_ARGUMENT` | Argumento `null`, ruta vacía, directorio, Uri inválido o sin ruta |
| `FILE_NOT_FOUND` | La ruta o el contenido del Uri no existe |
| `PERMISSION_DENIED` | Sin permiso de lectura (`SecurityException`, `EACCES`/`EPERM`) |
| `IO_ERROR` | Falló la lectura; el provider no entregó un descriptor o datos |
| `EMPTY_FILE` | El archivo tiene tamaño 0 |
| `TOO_MANY_SEEKS` | Se superó el límite anti-bucle de saltos |
| `CANCELLED` | El hilo fue interrumpido durante la apertura |
| `NATIVE_INIT_FAILED` | No se pudo iniciar el análisis en la capa nativa |
| `NATIVE_ERROR` | La capa nativa reportó un fallo o una excepción |
| `UNEXPECTED` | `RuntimeException` inesperada |

**Java**

```java
try (TwedMediaInfo mi = new TwedMediaInfo(context)) {
    if (!mi.open(uri)) {
        switch (mi.getLastError()) {
            case PERMISSION_DENIED:
                // pedir permiso o volver a pedir el archivo al usuario
                break;
            case FILE_NOT_FOUND:
                // el archivo ya no existe
                break;
            case CANCELLED:
                // el hilo fue interrumpido: no es un fallo del archivo
                break;
            default:
                Log.w("App", mi.getLastError() + ": " + mi.getLastErrorMessage());
        }
        return;
    }
    // ... consultas
}
```

**Kotlin**

```kotlin
TwedMediaInfo(context).use { mi ->
    if (!mi.open(uri)) {
        when (mi.lastError) {
            TwedMediaInfoError.PERMISSION_DENIED -> { /* pedir permiso o volver a pedir el archivo */ }
            TwedMediaInfoError.FILE_NOT_FOUND -> { /* el archivo ya no existe */ }
            TwedMediaInfoError.CANCELLED -> { /* el hilo fue interrumpido */ }
            else -> Log.w("App", "${mi.lastError}: ${mi.lastErrorMessage}")
        }
        return
    }
    // ... consultas
}
```

### 5.2 `isRecognized()` e `isPartial()`

- **`isRecognized()`**: `open(...)` devuelve `true` aunque MediaInfo **no reconozca el formato**. Este método devuelve `true` solo si hay un archivo abierto y MediaInfo identificó su formato (el campo General `Format` no está vacío). Hace una consulta solo cuando se lo llama.
- **`isPartial()`**: devuelve `true` si MediaInfo pidió un salto de lectura que la fuente (un provider que no permite *seek*) no pudo atender. No es un error: el resultado se entrega con lo que se alcanzó a leer y los campos que dependían de ese salto pueden venir vacíos. Se reinicia en cada `open(...)` y en `closeFile()`.

**Java**

```java
if (mediaInfo.open(uri)) {
    if (!mediaInfo.isRecognized()) {
        // el archivo se abrió, pero no es un formato que MediaInfo reconozca
    } else if (mediaInfo.isPartial()) {
        // los datos pueden estar incompletos
    }
}
```

**Kotlin**

```kotlin
if (mediaInfo.open(uri)) {
    if (!mediaInfo.isRecognized) {
        // el archivo se abrió, pero no es un formato que MediaInfo reconozca
    } else if (mediaInfo.isPartial) {
        // los datos pueden estar incompletos
    }
}
```

### 5.3 `FieldStatus`: por qué un campo no tiene valor

Cuando `get(...)` devuelve `""`, `getFieldStatus(...)` (o `getGeneralFieldStatus(...)` para el stream General) explica la causa. Es una llamada **aparte**: `get(...)` no cambia ni cuesta más cuando hay valor, así que úsala solo cuando `get(...)` devolvió vacío.

| Valor | Significado |
|---|---|
| `HAS_VALUE` | El campo tiene un valor no vacío |
| `FILE_NOT_OPEN` | No hay archivo abierto |
| `INVALID_REQUEST` | `streamKind` fuera de rango, `streamNumber` negativo, parámetro nulo o vacío |
| `STREAM_NOT_PRESENT` | El archivo no tiene ese stream (tipo o número) |
| `UNKNOWN_PARAMETER` | Ese nombre no es un campo de ese tipo de stream |
| `NO_VALUE` | Campo válido, stream existente, sin valor |

Los alias heredados de MediaInfo (por ejemplo `Artist`, `Channels`, `PlayTime`) se reconocen como campos conocidos.

> **Limitación:** `NO_VALUE` no distingue «el archivo no trae el campo» de «el archivo lo trae vacío». MediaInfo devuelve cadena vacía en ambos casos y no expone esa diferencia.

**Java**

```java
String title = mediaInfo.getGeneral(General.TITLE);
if (title.isEmpty()) {
    switch (mediaInfo.getGeneralFieldStatus(General.TITLE)) {
        case UNKNOWN_PARAMETER:
            // el nombre del parámetro está mal escrito
            break;
        case NO_VALUE:
            // el archivo no tiene título
            break;
        default:
            break;
    }
}

// Consultar un stream que quizá no existe
FieldStatus status = mediaInfo.getFieldStatus(StreamKind.VIDEO, 0, Video.WIDTH);
if (status == FieldStatus.STREAM_NOT_PRESENT) {
    // el archivo no tiene video (por ejemplo, es solo audio)
}
```

**Kotlin**

```kotlin
val title = mediaInfo.getGeneral(General.TITLE)
if (title.isEmpty()) {
    when (mediaInfo.getGeneralFieldStatus(General.TITLE)) {
        FieldStatus.UNKNOWN_PARAMETER -> { /* el nombre del parámetro está mal escrito */ }
        FieldStatus.NO_VALUE -> { /* el archivo no tiene título */ }
        else -> Unit
    }
}

// Consultar un stream que quizá no existe
val status = mediaInfo.getFieldStatus(StreamKind.VIDEO, 0, Video.WIDTH)
if (status == FieldStatus.STREAM_NOT_PRESENT) {
    // el archivo no tiene video (por ejemplo, es solo audio)
}
```

### 5.4 Flujo de decisión recomendado

1. `open(...)` devuelve `false` → mira `getLastError()` y `getLastErrorMessage()`.
2. `open(...)` devuelve `true` pero `!isRecognized()` → el archivo no es un formato reconocido.
3. `isPartial()` es `true` → los datos pueden estar incompletos.
4. `get(...)` devolvió vacío → llama a `getFieldStatus(...)`.

Ejemplo completo:

**Java**

```java
try (TwedMediaInfo mi = new TwedMediaInfo(context)) {
    if (!mi.open(uri)) { handleError(mi.getLastError(), mi.getLastErrorMessage()); return; }
    if (!mi.isRecognized()) { handleUnsupported(); return; }
    if (mi.isPartial()) { markAsPartial(); }

    String title = mi.getGeneral(General.TITLE);
    if (title.isEmpty() && mi.getGeneralFieldStatus(General.TITLE) == FieldStatus.UNKNOWN_PARAMETER) {
        handleTypo();
    }
}
```

**Kotlin**

```kotlin
TwedMediaInfo(context).use { mi ->
    if (!mi.open(uri)) { handleError(mi.lastError, mi.lastErrorMessage); return }
    if (!mi.isRecognized) { handleUnsupported(); return }
    if (mi.isPartial) markAsPartial()

    val title = mi.getGeneral(General.TITLE)
    if (title.isEmpty() && mi.getGeneralFieldStatus(General.TITLE) == FieldStatus.UNKNOWN_PARAMETER) {
        handleTypo()
    }
}
```

---

## 6. Ciclo de vida e hilos

### 6.1 Cerrar la instancia

`TwedMediaInfo` implementa `AutoCloseable`. Usa siempre **`try-with-resources`** (Java) o **`use { }`** (Kotlin), como en todos los ejemplos de este documento.

| Método | Qué hace |
|---|---|
| `close()` | Libera **todo** (archivo e instancia nativa). Es idempotente: llamarlo dos veces no falla. Tras `close()`, cualquier otro uso lanza `IllegalStateException`. |
| `closeFile()` | Cierra solo el archivo abierto y deja la instancia lista para abrir otro. |
| `destroy()` | `@Deprecated`: alias de `close()`. |

Si olvidas cerrar la instancia, un *finalizador* de seguridad libera el recurso nativo y deja un aviso en Logcat; no dependas de él.

### 6.2 Reutilizar una instancia para varios archivos

**Java**

```java
try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
    for (File file : files) {
        if (mediaInfo.open(file)) {
            String format = mediaInfo.getGeneral(General.FORMAT);
            // ... usar los datos
            mediaInfo.closeFile();   // opcional: open() ya cierra el archivo anterior
        }
    }
}   // close() libera todo
```

**Kotlin**

```kotlin
TwedMediaInfo().use { mediaInfo ->
    for (file in files) {
        if (mediaInfo.open(file)) {
            val format = mediaInfo.getGeneral(General.FORMAT)
            // ... usar los datos
            mediaInfo.closeFile()   // opcional: open() ya cierra el archivo anterior
        }
    }
}   // close() libera todo
```

### 6.3 Hilos

La clase es **thread-safe**: todas las operaciones públicas de una instancia se serializan con un lock. Consecuencias:

- `open(...)` es **bloqueante**: no lo llames desde el hilo principal.
- **Mientras un hilo está dentro de `open(Uri)` (con providers lentos puede tardar segundos), cualquier consulta desde otro hilo espera.** No consultes una instancia desde otro hilo mientras se está abriendo.
- Lo más simple es abrir, consultar y cerrar dentro del mismo hilo de fondo, con una instancia por tarea.

**Java**

```java
ExecutorService executor = Executors.newSingleThreadExecutor();

executor.execute(() -> {
    try (TwedMediaInfo mi = new TwedMediaInfo(context)) {
        if (mi.open(uri)) {
            String format = mi.getGeneral(General.FORMAT);
            runOnUiThread(() -> textView.setText(format));
        }
    }
});
```

**Kotlin (corrutinas)**

```kotlin
// Requiere kotlinx-coroutines en tu app
suspend fun readFormat(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    TwedMediaInfo(context).use { mi ->
        if (mi.open(uri)) mi.getGeneral(General.FORMAT) else null
    }
}
```

Si quieres que cancelar la corrutina interrumpa la apertura (`TwedMediaInfoError.CANCELLED`), envuelve el bloque con `runInterruptible(Dispatchers.IO) { ... }` en lugar de `withContext(Dispatchers.IO)`.

---

## 7. Referencia de la API

### 7.1 Paquetes

| Paquete | Contenido |
|---|---|
| `com.twedmediainfo.android` | `TwedMediaInfo`, `StreamKind`, `TwedMediaInfoError`, `FieldStatus` |
| `com.twedmediainfo.android.parameters` | Catálogos de constantes: `General`, `Video`, `Audio`, `Image`, `Text`, `Other` |
| `com.twedmediainfo.android.internal` | `NativeBridge`, `NativeLoader`. **No forman parte de la API pública** (ver [sección 10](#10-arquitectura-de-alto-nivel)). |

### 7.2 Constructores

| Constructor | Descripción |
|---|---|
| `TwedMediaInfo()` | Instancia sin `Context`. Permite `open(String)` y `open(File)`. |
| `TwedMediaInfo(Context context)` | Instancia con `Context` (se guarda `context.getApplicationContext()`). Necesaria para `open(Uri)`. Lanza `NullPointerException` si `context` es `null`. |

Ambos lanzan `IllegalStateException` si no se puede crear la instancia nativa.

### 7.3 Métodos

| Método | Retorno | Descripción |
|---|---|---|
| `open(String filePath)` | `boolean` | Abre un archivo por ruta absoluta. |
| `open(File file)` | `boolean` | Equivale a `open(file.getAbsolutePath())`. |
| `open(Uri uri)` | `boolean` | Abre un `content://` o `file://`. Requiere `TwedMediaInfo(Context)`. |
| `getLastError()` | `TwedMediaInfoError` | Motivo del último fallo de `open(...)`; `NONE` si tuvo éxito. |
| `getLastErrorMessage()` | `String` | Mensaje legible del último fallo; `""` si `NONE`. |
| `getLastException()` | `Throwable` | Causa original del último fallo, o `null`. |
| `isRecognized()` | `boolean` | `true` si hay archivo abierto y el formato fue reconocido. |
| `isPartial()` | `boolean` | `true` si el análisis pudo quedar incompleto (fuente sin seek). |
| `getGeneral(String parameter)` | `String` | Parámetro del stream General. |
| `get(int streamKind, int streamNumber, String parameter)` | `String` | Parámetro de cualquier stream. |
| `countStreams(int streamKind)` | `int` | Número de streams de un tipo. |
| `has(int streamKind, int streamNumber, String parameter)` | `boolean` | `true` si el valor no está vacío. |
| `getInt(kind, number, parameter, defaultValue)` | `int` | Valor numérico entero con valor por defecto. |
| `getLong(kind, number, parameter, defaultValue)` | `long` | Valor numérico entero largo con valor por defecto. |
| `getDouble(kind, number, parameter, defaultValue)` | `double` | Valor decimal con valor por defecto. |
| `getGeneralInt(...)`, `getGeneralLong(...)`, `getGeneralDouble(...)` | `int` / `long` / `double` | Igual, sobre el stream General: `getGeneralInt(parameter, defaultValue)`. |
| `getFieldStatus(int streamKind, int streamNumber, String parameter)` | `FieldStatus` | Explica por qué un valor vino vacío. |
| `getGeneralFieldStatus(String parameter)` | `FieldStatus` | Igual, sobre el stream General. |
| `closeFile()` | `void` | Cierra el archivo y deja la instancia reutilizable. |
| `close()` | `void` | Libera todo. Idempotente. |
| `destroy()` | `void` | `@Deprecated`, alias de `close()`. |
| `static getMediaInfoVersion()` | `String` | Versión de MediaInfoLib embebida. |
| `static getZenLibVersion()` | `String` | Versión de ZenLib embebida. |

Tras `close()`, todos los métodos lanzan `IllegalStateException`, excepto `close()`, `destroy()`, los `getLast*` y los estáticos.

---

## 8. Catálogos de parámetros

El paquete `com.twedmediainfo.android.parameters` contiene seis clases `final` con constructor privado, que agrupan constantes `public static final String`:

| Clase | Stream | Se usa con |
|---|---|---|
| `General` | Contenedor / archivo completo | `getGeneral(...)` o `StreamKind.GENERAL` |
| `Video` | Video | `StreamKind.VIDEO` |
| `Audio` | Audio | `StreamKind.AUDIO` |
| `Image` | Imagen | `StreamKind.IMAGE` |
| `Text` | Texto (subtítulos) | `StreamKind.TEXT` |
| `Other` | Otros (capítulos, menús, timecodes, etc.) | `StreamKind.OTHER` |

### Cómo funcionan

- El valor de cada constante es el nombre del parámetro de MediaInfoLib 26.05 (definidos en los archivos `Source/Resource/Text/Stream/*.csv`). Por ejemplo, `Video.WIDTH` vale `"Width"` y `Audio.CHANNELS` vale `"Channel(s)"`.
- Las constantes con sufijo `_STRING` corresponden a la variante `/String` del parámetro (valor formateado para mostrar), por ejemplo `Video.FRAME_RATE` (`"FrameRate"`) y `Video.FRAME_RATE_STRING` (`"FrameRate/String"`).
- Varias clases comparten nombres de constantes con el mismo valor (por ejemplo `FORMAT` vale `"Format"` en todas). Lo que determina de qué stream se lee es el `StreamKind` que pasas a `get(...)`, no la clase de la constante.
- Los catálogos incluyen los parámetros más relevantes, no todos. Se excluyeron intencionalmente los marcados como obsoletos en MediaInfoLib (por ejemplo, la familia `Codec` y `Resolution`) y los de uso interno. Cualquier otro parámetro se puede consultar pasando su nombre como `String`.

### Ejemplos representativos

| Clase | Constante | Valor |
|---|---|---|
| `General` | `FORMAT` | `"Format"` |
| `General` | `FILE_SIZE` | `"FileSize"` |
| `General` | `DURATION_STRING` | `"Duration/String"` |
| `General` | `VIDEO_COUNT` | `"VideoCount"` |
| `General` | `AUDIO_COUNT` | `"AudioCount"` |
| `General` | `TEXT_COUNT` | `"TextCount"` |
| `Video` | `WIDTH` | `"Width"` |
| `Video` | `HEIGHT` | `"Height"` |
| `Video` | `FRAME_RATE_STRING` | `"FrameRate/String"` |
| `Video` | `BITRATE_STRING` | `"BitRate/String"` |
| `Audio` | `CHANNELS` | `"Channel(s)"` |
| `Audio` | `SAMPLING_RATE` | `"SamplingRate"` |
| `Audio` | `BITRATE_STRING` | `"BitRate/String"` |
| `Audio` | `LANGUAGE_STRING` | `"Language/String"` |
| `Image` | `TYPE` | `"Type"` |
| `Image` | `BIT_DEPTH_STRING` | `"BitDepth/String"` |
| `Image` | `SUMMARY` | `"Summary"` |
| `Text` | `LINES_COUNT` | `"Lines_Count"` |
| `Text` | `LANGUAGE_STRING` | `"Language/String"` |
| `Other` | `TYPE` | `"Type"` |
| `Other` | `TIMECODE_FIRST_FRAME` | `"TimeCode_FirstFrame"` |

### Notas por clase

- **`General`**: describe el archivo completo, no un stream individual. Incluye los conteos por tipo de stream (`VIDEO_COUNT`, `AUDIO_COUNT`, `TEXT_COUNT`, etc.). `FILE_SIZE` solo tiene variantes hasta `/String4`.
- **`Image`**: en MediaInfoLib 26.05 el stream Image no expone `Duration`, `BitRate` ni `FrameRate`.
- **`Text`**: `WIDTH` y `HEIGHT` se miden en caracteres (columnas y filas), no en píxeles.
- **`Other`**: `TYPE` indica el tipo específico del stream (capítulos, menú, timecode, etc.).

---

## 9. Limitaciones conocidas

- **Bloqueante:** `open(...)` debe usarse fuera del hilo principal; mientras un hilo abre un archivo, las consultas desde otros hilos esperan.
- **Fuentes sin seek:** los providers que no permiten saltos de lectura pueden dar un resultado parcial (`isPartial()`); los campos que dependían del salto pueden venir vacíos.
- **`NO_VALUE` no distingue campo ausente de campo vacío** (limitación de MediaInfo).
- **`open(...)` devuelve `true` para formatos no reconocidos:** usa `isRecognized()`.
- **Rutas físicas:** solo funcionan con archivos a los que tu app tenga acceso. Para el resto, usa un `Uri`.
- **ABIs incluidas:** solo `arm64-v8a` y `armeabi-v7a`. **No se incluyen `x86` ni `x86_64`**: en esos dispositivos o emuladores la carga de la biblioteca nativa falla (`UnsatisfiedLinkError`). Usa un dispositivo ARM o una imagen de emulador ARM.
- **Los catálogos no cubren todos los parámetros de MediaInfoLib.** Los parámetros no listados se consultan por nombre como `String`.
- **No hay clase de catálogo para `StreamKind.MENU`.**
- **Los getters tipados** (`getInt`, `getLong`, `getDouble`) cubren los casos numéricos simples, no valores compuestos.

---

## 10. Arquitectura de alto nivel

```text
API Java  →  JNI  →  capa nativa  →  MediaInfoLib
```

- **API Java:** las clases públicas descritas en este documento.
- **JNI:** la clase `NativeBridge` (paquete `com.twedmediainfo.android.internal`) declara los métodos nativos, y `NativeLoader` carga la biblioteca con `System.loadLibrary("twedmediainfo")`. Estas clases **no son API pública** y pueden cambiar sin previo aviso, aunque `NativeBridge` esté declarada como `public` por necesidades técnicas.
- **Capa nativa:** `libtwedmediainfo.so`, que incluye MediaInfoLib, ZenLib y zlib enlazadas de forma estática.

---

## 11. Compatibilidad

Datos de la versión 1.0.0:

| Elemento | Valor |
|---|---|
| `minSdk` | 24 |
| `compileSdk` | 37 |
| Java/JVM | 17 |
| ABIs | `arm64-v8a`, `armeabi-v7a` (sin `x86` / `x86_64`) |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| MediaInfoLib | 26.05 |
| ZenLib | 0.4.41 |
| zlib | 1.3.2 |
| Tamaño de página ELF | Compatible con 16 KB |

---

## 12. Migración desde 0.1.0

Si vienes de la 0.1.0, hay **un único cambio incompatible**: `close()` ahora es el de `AutoCloseable` y **libera todo**. El comportamiento antiguo de `close()` (cerrar solo el archivo) pasó a `closeFile()`.

**Kotlin**

```kotlin
// 0.1.0
mediaInfo.close()      // cerraba solo el archivo
// 1.0.0
mediaInfo.closeFile()  // equivalente al antiguo close()
mediaInfo.close()      // ahora libera todo (usar use { })
```

**Java**

```java
// 0.1.0
mediaInfo.close();      // cerraba solo el archivo
// 1.0.0
mediaInfo.closeFile();  // equivalente al antiguo close()
mediaInfo.close();      // ahora libera todo (usar try-with-resources)
```

Otros cambios respecto a la 0.1.0:

- `destroy()` sigue existiendo, pero está *deprecated*: usa `close()` (o, mejor, `try-with-resources` / `use { }`).
- Tras `close()`, cualquier uso lanza `IllegalStateException`.
- `open(Uri)` pasa a ser una entrada oficialmente soportada, junto con `open(File)`.
- Nuevas APIs: `getLastError()`, `getLastErrorMessage()`, `getLastException()`, `isRecognized()`, `isPartial()`, `getFieldStatus(...)`, `getGeneralFieldStatus(...)`, `has(...)`, `getInt(...)`, `getLong(...)`, `getDouble(...)` y los enums `TwedMediaInfoError` y `FieldStatus`.
- La clase es thread-safe.

---

## 13. Licencia

El código de TwedMediaInfo se distribuye bajo la licencia Apache 2.0. Las licencias de las bibliotecas de terceros (MediaInfoLib, ZenLib y zlib) están en la carpeta [`LICENSE_THIRD_PARTY/`](LICENSE_THIRD_PARTY/) y no se repiten en este documento.
