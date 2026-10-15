# TwedMediaInfo

TwedMediaInfo es una biblioteca Android para analizar archivos multimedia y acceder a sus metadatos e información técnica (formato, duración, resolución, códecs, canales de audio, etiquetas, etc.) mediante [MediaInfoLib](https://github.com/MediaArea/MediaInfoLib).

El proyecto adapta e integra tecnologías existentes para Android: una API pública en Java, una capa JNI y un AAR distribuible que ya incluye todo lo necesario para funcionar. No requiere dependencias adicionales de runtime.

Para la guía de uso de la API, con ejemplos en Java y Kotlin, consulta [`DOC.md`](DOC.md).

## Características

- **Rutas físicas:** analiza archivos por ruta (`String`) o por `java.io.File`.
- **`Uri`:** analiza `content://` (Storage Access Framework, MediaStore y providers propios) y `file://`.
- **Estados y diagnóstico:** `open(...)` informa por qué falló (`getLastError()`), `isRecognized()` indica si el formato fue reconocido, `isPartial()` avisa de un análisis posiblemente incompleto y `getFieldStatus(...)` explica por qué un campo vino vacío.
- **Seguridad en hilos:** una instancia se puede usar desde varios hilos; las operaciones se serializan.
- **Ciclo de vida seguro:** implementa `AutoCloseable` (`try-with-resources` en Java, `use { }` en Kotlin).
- **Getters tipados:** `getInt`, `getLong` y `getDouble` con valor por defecto.
- **Catálogos de parámetros:** constantes para General, Video, Audio, Image, Text y Other.

## Ejemplo rápido

Kotlin:

```kotlin
// Llamar desde un hilo de fondo.
TwedMediaInfo(context).use { mi ->
    if (!mi.open(uri)) {
        Log.w("App", "No se abrió: ${mi.lastError} - ${mi.lastErrorMessage}")
        return
    }
    val format = mi.getGeneral(General.FORMAT)
    val width = mi.getInt(StreamKind.VIDEO, 0, Video.WIDTH, 0)
}
```

Java:

```java
// Llamar desde un hilo de fondo.
try (TwedMediaInfo mi = new TwedMediaInfo(context)) {
    if (mi.open(uri)) {
        String format = mi.getGeneral(General.FORMAT);
        int width = mi.getInt(StreamKind.VIDEO, 0, Video.WIDTH, 0);
    } else {
        Log.w("App", "No se abrió: " + mi.getLastError());
    }
}
```

## Instalación

El AAR oficial de TwedMediaInfo se distribuye únicamente mediante Maven Central. Asegúrate de tener `mavenCentral()` entre los repositorios de tu proyecto.

En el módulo de tu aplicación Android (Gradle Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.boludohh:twedmediainfo:1.0.0")
}
```

Con Gradle Groovy:

```groovy
dependencies {
    implementation 'io.github.boludohh:twedmediainfo:1.0.0'
}
```

No es necesario declarar MediaInfoLib, ZenLib ni zlib como dependencias adicionales. Sus componentes necesarios están enlazados dentro de la biblioteca nativa que distribuye el AAR.

## Entradas soportadas

Desde la versión 1.0.0, las entradas oficialmente soportadas son:

| Entrada | Método | Notas |
|---|---|---|
| Ruta física | `open(String)` | Ruta absoluta a un archivo accesible por tu app. |
| Archivo | `open(File)` | Equivale a `open(file.getAbsolutePath())`. |
| `content://` | `open(Uri)` | Storage Access Framework, MediaStore y providers propios. Requiere crear la instancia con `TwedMediaInfo(Context)`. |
| `file://` | `open(Uri)` | Se resuelve como una ruta física. |

`open(...)` es bloqueante: úsalo fuera del hilo principal. Si falla, no lanza excepciones por fallos esperados: devuelve `false` y el motivo está en `getLastError()`.

## Compatibilidad

- Min SDK: 24
- Compile SDK: 37
- Java/JVM: 17
- ABIs: `arm64-v8a`, `armeabi-v7a`. **`x86` y `x86_64` no están soportados**: en esos dispositivos o emuladores la carga de la biblioteca nativa falla (`UnsatisfiedLinkError`); usa un dispositivo ARM o una imagen de emulador ARM.
- Alineación ELF de 16 KB: las bibliotecas nativas están preparadas para dispositivos Android que la requieren.

Versiones de las bibliotecas nativas incluidas:

- MediaInfoLib: 26.05
- ZenLib: 0.4.41
- zlib: 1.3.2

## Documentación

- [`DOC`](DOC.md): documentación técnica de la API pública y ejemplos de uso en Java y Kotlin.
- [`SUPPORTED`](SUPPORTED.md): formatos, tipos de stream y parámetros soportados.

## Contribuciones y derechos de autor

TwedMediaInfo es una adaptación e integración para Android. El análisis de los archivos lo realiza MediaInfoLib, desarrollada por MediaArea.net SARL; TwedMediaInfo no reimplementa ese análisis y reconoce el trabajo de sus autores y de los autores de las bibliotecas que redistribuye.

### Software de terceros

TwedMediaInfo utiliza y redistribuye software desarrollado por terceros. Sus derechos de autor y condiciones de licencia se mantienen. El texto completo de cada licencia está en [`LICENSE_THIRD_PARTY/`](LICENSE_THIRD_PARTY/).

#### MediaInfoLib

Copyright (c) 2002-2025 MediaArea.net SARL. All rights reserved.

Proporciona el motor principal utilizado para analizar archivos multimedia y obtener información técnica y metadatos.

Licencia: BSD 2-Clause License. Texto completo en [`MediaInfoLib-LICENSE`](LICENSE_THIRD_PARTY/MediaInfoLib-LICENSE).

#### ZenLib

Copyright (c) 2002-2020 MediaArea.net SARL. All rights reserved.

Proporciona componentes utilizados por MediaInfoLib.

Licencia: ZenLib License. Texto completo en [`ZenLib-LICENSE`](LICENSE_THIRD_PARTY/ZenLib-LICENSE).

#### zlib

Copyright (C) 1995-2026 Jean-loup Gailly and Mark Adler.

Proporciona componentes de compresión utilizados por MediaInfoLib.

Licencia: zlib License. Texto completo en [`zlib-LICENSE`](LICENSE_THIRD_PARTY/zlib-LICENSE).

## Licencia

Copyright 2026 TwedMediaInfo — boludohh

TwedMediaInfo se distribuye bajo la Apache License 2.0. El texto completo está en [`LICENSE`](LICENSE).

## Proyecto

TwedMediaInfo es un proyecto creado por mi. La biblioteca usa Java para la API Android y C++ para la integración nativa mediante JNI.

Si encuentras un problema o necesitas un caso de uso que no está cubierto, abre un [Issue](https://github.com/boludohh/TwedMediaInfo/issues).

Repositorio oficial: https://github.com/boludohh/TwedMediaInfo
