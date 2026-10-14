/*
 * Copyright 2026 TwedMediaInfo Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twedmediainfo.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import com.twedmediainfo.android.internal.NativeBridge;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Wrapper de MediaInfoLib 26.05 para análisis de archivos multimedia en Android.
 * <p>
 * Esta clase expone las capacidades de MediaInfoLib a través de una API Java.
 * La capa nativa (MediaInfoLib, ZenLib y zlib) está enlazada estáticamente
 * en {@code libtwedmediainfo.so}.
 * <p>
 * <b>Entradas soportadas:</b> ruta de archivo ({@link #open(String)}, {@link #open(File)}) y
 * {@link Uri} ({@link #open(Uri)}: {@code content://}, Storage Access Framework, MediaStore y
 * {@code file://}).
 * <p>
 * <b>Ciclo de vida:</b> la clase implementa {@link AutoCloseable}. {@link #close()} libera todo
 * (archivo e instancia nativa) y es idempotente; {@link #closeFile()} solo cierra el archivo y deja
 * la instancia reutilizable. Tras {@code close()} cualquier uso lanza {@link IllegalStateException}.
 * Como red de seguridad, si se olvida cerrar, el finalizador libera la memoria nativa y registra
 * una advertencia en Logcat; no se debe depender de eso.
 * <p>
 * <b>Errores:</b> los métodos {@code open} no lanzan excepciones por fallos esperados: devuelven
 * {@code false} y el motivo queda en {@link #getLastError()}, {@link #getLastErrorMessage()} y
 * {@link #getLastException()}.
 * <p>
 * <b>Concurrencia:</b> todas las operaciones públicas se serializan por instancia, así que la clase
 * es thread-safe. Consecuencia: mientras un hilo está dentro de {@link #open(Uri)} (puede tardar
 * segundos con proveedores lentos), cualquier consulta desde otro hilo <b>espera</b>. No llamar
 * {@code open} desde el hilo principal ni consultar desde otro hilo mientras se abre.
 * <p>
 * Uso típico con File:
 * <pre>{@code
 * try (TwedMediaInfo mediaInfo = new TwedMediaInfo()) {
 *     if (mediaInfo.open("/path/to/file.mp4")) {
 *         String format = mediaInfo.getGeneral("Format");
 *         int audioCount = mediaInfo.countStreams(StreamKind.AUDIO);
 *         for (int i = 0; i < audioCount; i++) {
 *             String audioFormat = mediaInfo.get(StreamKind.AUDIO, i, Audio.FORMAT);
 *         }
 *     } else {
 *         Log.w("App", "No se pudo abrir: " + mediaInfo.getLastError());
 *     }
 * }
 * }</pre>
 * <p>
 * Uso típico con Uri (fuera del hilo principal):
 * <pre>{@code
 * try (TwedMediaInfo mediaInfo = new TwedMediaInfo(context)) {
 *     if (mediaInfo.open(uri)) {
 *         String format = mediaInfo.getGeneral("Format");
 *         // ... mismas consultas que con File
 *     }
 * }
 * }</pre>
 */
public class TwedMediaInfo implements AutoCloseable {

    private static final String TAG = "TwedMediaInfo";

    /** Tamaño del buffer de lectura: 64 KiB, el grano natural de los saltos de MediaInfo. */
    private static final int READ_BUFFER_SIZE = 64 * 1024;

    /** Protección anti-bucle: máximo de saltos que se atienden en un mismo open(Uri). */
    private static final int MAX_SEEKS = 10_000;

    /** Lecturas consecutivas de 0 bytes toleradas antes de dar la fuente por agotada. */
    private static final int MAX_ZERO_READS = 8;

    /** Bit 3 del estado de MediaInfo: «Is Finalized». */
    private static final int STATUS_FINALIZED = 0x08;

    private enum State { IDLE, OPEN, DESTROYED }

    private static volatile String cachedMediaInfoVersion;
    private static volatile String cachedZenLibVersion;

    /** Serializa todas las operaciones públicas de esta instancia. */
    private final Object lock = new Object();
    private final Context context;

    // Todo el estado mutable de abajo solo se toca dentro de synchronized (lock).
    private long handle;
    private State state = State.IDLE;
    private boolean partial;
    private TwedMediaInfoError lastError = TwedMediaInfoError.NONE;
    private String lastErrorMessage = "";
    private Throwable lastException;

    /**
     * Crea una nueva instancia de TwedMediaInfo sin Context.
     * <p>
     * Este constructor permite usar {@link #open(String)} y {@link #open(File)}.
     * Para usar {@link #open(Uri)} se requiere el constructor con Context.
     *
     * @throws IllegalStateException si no se puede crear la instancia nativa
     */
    public TwedMediaInfo() {
        this.context = null;
        this.handle = NativeBridge.nativeCreate();

        if (handle == 0L) {
            throw new IllegalStateException("Failed to create native MediaInfo instance");
        }
    }

    /**
     * Crea una nueva instancia de TwedMediaInfo con Context para soporte de Uri.
     * <p>
     * Este constructor permite usar tanto {@link #open(String)} como {@link #open(Uri)}.
     * El Context se guarda internamente como applicationContext para evitar retener
     * referencias a Activity/Service.
     *
     * @param context Context de Android (se usará getApplicationContext())
     * @throws NullPointerException si {@code context} es {@code null}
     * @throws IllegalStateException si no se puede crear la instancia nativa
     */
    public TwedMediaInfo(Context context) {
        Objects.requireNonNull(context, "context");
        Context app = context.getApplicationContext();
        this.context = (app != null) ? app : context;
        this.handle = NativeBridge.nativeCreate();

        if (handle == 0L) {
            throw new IllegalStateException("Failed to create native MediaInfo instance");
        }
    }

    // ==========================================================================
    // Apertura
    // ==========================================================================

    /**
     * Abre un archivo multimedia para análisis mediante ruta de archivo.
     * <p>
     * Si ya hay un archivo abierto, lo cierra antes de abrir el nuevo. No lanza excepciones por
     * fallos esperados: si devuelve {@code false}, consultar {@link #getLastError()}.
     * <p>
     * Nota: devuelve {@code true} aunque MediaInfo no reconozca el formato; usar
     * {@link #isRecognized()} para distinguirlo.
     *
     * @param filePath Ruta absoluta al archivo.
     * @return {@code true} si el archivo se abrió correctamente, {@code false} en caso contrario.
     * @throws IllegalStateException si la instancia ya fue cerrada con {@link #close()}
     */
    public boolean open(String filePath) {
        synchronized (lock) {
            checkAlive();
            return openPathLocked(filePath);
        }
    }

    /**
     * Abre un archivo multimedia para análisis. Equivale a
     * {@code open(file.getAbsolutePath())}.
     *
     * @param file Archivo a analizar.
     * @return {@code true} si el archivo se abrió correctamente, {@code false} en caso contrario.
     * @throws IllegalStateException si la instancia ya fue cerrada con {@link #close()}
     */
    public boolean open(File file) {
        synchronized (lock) {
            checkAlive();
            if (file == null) {
                beginOpen();
                return fail(TwedMediaInfoError.INVALID_ARGUMENT, "El archivo es nulo", null);
            }
            return openPathLocked(file.getAbsolutePath());
        }
    }

    /**
     * Abre un archivo multimedia para análisis mediante Uri.
     * <p>
     * Soporta {@code content://} (MediaStore, Storage Access Framework, proveedores propios) y
     * {@code file://}. Utiliza ContentResolver para obtener acceso al contenido.
     * Requiere que esta instancia haya sido creada con {@link #TwedMediaInfo(Context)}.
     * <p>
     * Si ya hay un archivo abierto, lo cierra antes de abrir el nuevo.
     * <p>
     * <b>Es una llamada bloqueante</b>: hacer I/O, así que debe llamarse fuera del hilo principal.
     * Respeta {@link Thread#interrupt()} (el fallo queda como {@link TwedMediaInfoError#CANCELLED}).
     * Mientras dura, las consultas desde otros hilos esperan.
     * <p>
     * Si el proveedor no permite seek y MediaInfo necesita un salto (por ejemplo, un MP4 con el
     * {@code moov} al final), no se falla: se entrega un resultado parcial y
     * {@link #isPartial()} devuelve {@code true}.
     *
     * @param uri Uri del archivo multimedia (content://, file://)
     * @return {@code true} si el archivo se abrió correctamente, {@code false} en caso contrario.
     * @throws IllegalStateException si esta instancia fue creada sin Context o ya fue cerrada
     */
    public boolean open(Uri uri) {
        synchronized (lock) {
            checkAlive();
            if (context == null) {
                throw new IllegalStateException(
                    "open(Uri) requires TwedMediaInfo(Context) constructor. " +
                    "Use new TwedMediaInfo(context) instead of new TwedMediaInfo()."
                );
            }

            beginOpen();

            if (uri == null) {
                return fail(TwedMediaInfoError.INVALID_ARGUMENT, "El Uri es nulo", null);
            }

            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) {
                    return fail(TwedMediaInfoError.INVALID_ARGUMENT, "El Uri file:// no tiene ruta", null);
                }
                return openPathLocked(path);
            }

            return openUriLocked(uri);
        }
    }

    // ---- Implementación de open (requiere lock) -----------------------------

    /** Prepara un open nuevo: cierra el archivo anterior y limpia error y estado parcial. */
    private void beginOpen() {
        if (state == State.OPEN) {
            NativeBridge.nativeClose(handle);
            state = State.IDLE;
        }
        partial = false;
        lastError = TwedMediaInfoError.NONE;
        lastErrorMessage = "";
        lastException = null;
    }

    private boolean openPathLocked(String filePath) {
        beginOpen();

        if (filePath == null || filePath.isEmpty()) {
            return fail(TwedMediaInfoError.INVALID_ARGUMENT, "La ruta es nula o vacía", null);
        }

        // Os.stat distingue «no existe» de «sin permiso»; File.exists()/canRead() no.
        StructStat st;
        try {
            st = Os.stat(filePath);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) {
                return fail(TwedMediaInfoError.FILE_NOT_FOUND, "El archivo no existe", e);
            }
            if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM) {
                return fail(TwedMediaInfoError.PERMISSION_DENIED, "Sin permiso para leer el archivo", e);
            }
            return fail(TwedMediaInfoError.IO_ERROR, "No se pudo consultar el archivo (errno " + e.errno + ")", e);
        }

        if (OsConstants.S_ISDIR(st.st_mode)) {
            return fail(TwedMediaInfoError.INVALID_ARGUMENT, "La ruta es un directorio", null);
        }
        if (st.st_size == 0) {
            return fail(TwedMediaInfoError.EMPTY_FILE, "El archivo está vacío", null);
        }

        boolean ok;
        try {
            ok = NativeBridge.nativeOpen(handle, filePath);
        } catch (RuntimeException e) {
            return fail(TwedMediaInfoError.UNEXPECTED, "Error inesperado al abrir el archivo", e);
        }
        if (!ok) {
            return nativeFail(TwedMediaInfoError.NATIVE_ERROR, "MediaInfo no pudo abrir el archivo");
        }

        state = State.OPEN;
        return true;
    }

    private boolean openUriLocked(Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return fail(TwedMediaInfoError.IO_ERROR, "El proveedor no entregó un descriptor de archivo", null);
            }

            // El descriptor tiene un único dueño (el pfd). El canal sale de un
            // AutoCloseInputStream sobre ese mismo pfd; ParcelFileDescriptor.close() es
            // idempotente, así que el cierre en cadena es inocuo.
            try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                 FileChannel channel = in.getChannel()) {
                return analyzeChannel(uri, pfd, channel);
            }
        } catch (SecurityException e) {
            return failUnlessOpen(TwedMediaInfoError.PERMISSION_DENIED, "Sin permiso para leer el contenido del Uri", e);
        } catch (FileNotFoundException e) {
            return failUnlessOpen(TwedMediaInfoError.FILE_NOT_FOUND, "El contenido del Uri no existe", e);
        } catch (IllegalArgumentException e) {
            return failUnlessOpen(TwedMediaInfoError.INVALID_ARGUMENT, "Uri inválido o esquema no soportado", e);
        } catch (ClosedByInterruptException e) {
            // Subclase de IOException: debe ir antes del catch genérico de IOException.
            return failUnlessOpen(TwedMediaInfoError.CANCELLED, "Operación cancelada (hilo interrumpido)", e);
        } catch (IOException e) {
            return failUnlessOpen(TwedMediaInfoError.IO_ERROR, "Error de E/S al leer el contenido del Uri", e);
        } catch (RuntimeException e) {
            return failUnlessOpen(TwedMediaInfoError.UNEXPECTED, "Error inesperado al abrir el Uri", e);
        }
    }

    /**
     * Bucle de lectura basado en el ejemplo oficial de MediaInfoLib: lee bloques de 64 KiB, los
     * pasa a MediaInfo y atiende los saltos que pida sobre la MISMA instancia nativa.
     */
    private boolean analyzeChannel(Uri uri, ParcelFileDescriptor pfd, FileChannel channel) throws IOException {
        // Tamaño: fstat; si no es fiable, la columna SIZE del proveedor; si no, desconocido (-1).
        long statSize = pfd.getStatSize();
        long fileSize = statSize;
        if (fileSize <= 0) {
            long queried = querySize(uri);
            if (queried > 0) {
                fileSize = queried;
            } else if (queried == 0 && statSize == 0) {
                fileSize = 0;      // vacío confirmado por ambas fuentes
            } else {
                fileSize = -1;     // desconocido
            }
        }
        if (fileSize == 0) {
            return fail(TwedMediaInfoError.EMPTY_FILE, "El contenido está vacío", null);
        }

        // Seekability: lseek(SEEK_CUR) falla con ESPIPE en pipes y sockets.
        boolean seekable;
        try {
            Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            seekable = true;
        } catch (ErrnoException e) {
            seekable = false;
        }

        if (!NativeBridge.nativeOpenBufferInit(handle, fileSize)) {
            return nativeFail(TwedMediaInfoError.NATIVE_INIT_FAILED, "No se pudo iniciar el análisis");
        }

        byte[] buf = new byte[READ_BUFFER_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int seeks = 0;
        int zeroReads = 0;
        long totalRead = 0;
        boolean eof = false;
        boolean partialResult = false;

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                return fail(TwedMediaInfoError.CANCELLED, "Operación cancelada (hilo interrumpido)", null);
            }

            bb.clear();
            int n = channel.read(bb);
            if (n < 0) {                       // EOF
                eof = true;
                break;
            }
            if (n == 0) {
                if (++zeroReads > MAX_ZERO_READS) {
                    break;
                }
                continue;
            }
            zeroReads = 0;
            totalRead += n;

            int status = NativeBridge.nativeOpenBufferContinue(handle, buf, n);
            if (status < 0) {
                return nativeFail(TwedMediaInfoError.NATIVE_ERROR, "Fallo nativo durante el análisis");
            }
            if ((status & STATUS_FINALIZED) != 0) {   // no se necesitan más datos
                break;
            }

            long goTo = NativeBridge.nativeOpenBufferGoToGet(handle);   // -1 = sin salto pendiente
            if (goTo >= 0) {
                // El orden importa: primero «no hacen falta más bytes»...
                if (fileSize >= 0 && goTo >= fileSize) {
                    break;
                }
                // ...después «la fuente no permite saltar» (resultado parcial)...
                if (!seekable) {
                    partialResult = true;
                    break;
                }
                // ...y por último el salto real.
                if (++seeks > MAX_SEEKS) {
                    return fail(TwedMediaInfoError.TOO_MANY_SEEKS, "MediaInfo pidió demasiados saltos", null);
                }
                channel.position(goTo);
                // MISMA instancia, nuevo offset. No se recrea nada.
                if (!NativeBridge.nativeOpenBufferSeek(handle, fileSize, goTo)) {
                    return nativeFail(TwedMediaInfoError.NATIVE_ERROR, "MediaInfo no pudo reposicionarse tras el salto");
                }
            }
        }

        if (eof && totalRead == 0) {
            return fail(TwedMediaInfoError.EMPTY_FILE, "El contenido está vacío", null);
        }

        if (!NativeBridge.nativeOpenBufferFinalize(handle)) {
            return nativeFail(TwedMediaInfoError.NATIVE_ERROR, "MediaInfo no pudo finalizar el análisis");
        }

        state = State.OPEN;
        partial = partialResult;
        return true;
    }

    /** Consulta OpenableColumns.SIZE; devuelve -1 si no está disponible o la consulta falla. */
    private long querySize(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException e) {
            // El proveedor no soporta la consulta: tamaño desconocido.
        }
        return -1;
    }

    // ---- Manejo de errores (requiere lock) ----------------------------------

    /**
     * Registra el error, cierra lo que haya quedado a medias en la capa nativa y deja la
     * instancia en IDLE. Siempre devuelve {@code false}.
     */
    private boolean fail(TwedMediaInfoError error, String message, Throwable cause) {
        lastError = error;
        lastErrorMessage = (message != null) ? message : "";
        lastException = cause;
        partial = false;
        if (handle != 0L) {
            NativeBridge.nativeClose(handle);   // no dejar un MediaInfo a medio inicializar
        }
        state = State.IDLE;
        return false;
    }

    /** Como {@link #fail}, agregando al mensaje el detalle que dejó la capa nativa. */
    private boolean nativeFail(TwedMediaInfoError error, String baseMessage) {
        String detail = null;
        try {
            detail = NativeBridge.nativeGetLastError(handle);
        } catch (RuntimeException ignored) {
            // Sin detalle nativo: se usa solo el mensaje base.
        }
        String message = (detail == null || detail.isEmpty()) ? baseMessage : baseMessage + ": " + detail;
        return fail(error, message, null);
    }

    /**
     * Para fallos detectados en los catch de open(Uri). Si el análisis ya terminó con éxito
     * (state == OPEN) y el error vino solo de cerrar los recursos de E/S, el resultado es
     * válido y no se descarta.
     */
    private boolean failUnlessOpen(TwedMediaInfoError error, String message, Throwable cause) {
        if (state == State.OPEN) {
            Log.w(TAG, "Error al cerrar recursos de E/S tras un análisis exitoso: " + cause);
            return true;
        }
        return fail(error, message, cause);
    }

    private void checkAlive() {
        if (state == State.DESTROYED) {
            throw new IllegalStateException("TwedMediaInfo ya fue cerrada");
        }
    }

    // ==========================================================================
    // Diagnóstico de errores y estado del análisis
    // ==========================================================================

    /**
     * Motivo por el que falló el último {@code open(...)}.
     *
     * @return {@link TwedMediaInfoError#NONE} si el último {@code open} tuvo éxito.
     */
    public TwedMediaInfoError getLastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /**
     * Mensaje legible del último error (no incluye rutas completas ni Uri completos).
     *
     * @return String vacío si el último {@code open} tuvo éxito.
     */
    public String getLastErrorMessage() {
        synchronized (lock) {
            return lastErrorMessage;
        }
    }

    /**
     * Causa original del último error, si hubo una excepción.
     *
     * @return La excepción, o {@code null}. Puede contener rutas o Uri: no registrarla sin filtrar.
     */
    public Throwable getLastException() {
        synchronized (lock) {
            return lastException;
        }
    }

    /**
     * Indica si MediaInfo reconoció el formato del archivo abierto.
     * <p>
     * {@code open(...)} puede devolver {@code true} aunque el formato no sea reconocido;
     * este método lo distingue (hay archivo abierto y el campo General «Format» no está vacío).
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public boolean isRecognized() {
        synchronized (lock) {
            checkAlive();
            if (state != State.OPEN) {
                return false;
            }
            String format = NativeBridge.nativeGetGeneral(handle, "Format");
            return format != null && !format.isEmpty();
        }
    }

    /**
     * Indica si el análisis pudo quedar incompleto: MediaInfo pidió un salto que la fuente
     * (por ejemplo, un proveedor no seekable) no pudo atender. Se reinicia en cada
     * {@code open} y en {@link #closeFile()}.
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public boolean isPartial() {
        synchronized (lock) {
            checkAlive();
            return partial;
        }
    }

    // ==========================================================================
    // Consultas
    // ==========================================================================

    /**
     * Obtiene información general del archivo (Stream_General, stream 0).
     * <p>
     * Equivalente a {@code get(StreamKind.GENERAL, 0, parameter)}.
     *
     * @param parameter Nombre del parámetro (ej: "Format", "Duration", "FileSize").
     * @return Valor del parámetro o string vacío si no existe o no hay archivo abierto.
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public String getGeneral(String parameter) {
        synchronized (lock) {
            checkAlive();
            if (state != State.OPEN) {
                return "";
            }
            String value = NativeBridge.nativeGetGeneral(handle, parameter == null ? "" : parameter);
            return value != null ? value : "";
        }
    }

    /**
     * Obtiene información de cualquier stream.
     *
     * @param streamKind Tipo de stream (usar constantes de {@link StreamKind}).
     * @param streamNumber Número de stream, 0-based.
     * @param parameter Nombre del parámetro (usar {@link com.twedmediainfo.android.parameters.Audio}
     *                  para audio, o el nombre directo de Audio.csv / Video.csv / etc.).
     * @return Valor del parámetro o string vacío si no existe, si los índices son inválidos
     *         o si no hay archivo abierto. Para saber por qué está vacío, usar
     *         {@link #getFieldStatus(int, int, String)}.
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public String get(int streamKind, int streamNumber, String parameter) {
        synchronized (lock) {
            checkAlive();
            if (state != State.OPEN || !isValidRequest(streamKind, streamNumber)) {
                return "";
            }
            String value = NativeBridge.nativeGet(handle, streamKind, streamNumber,
                    parameter == null ? "" : parameter);
            return value != null ? value : "";
        }
    }

    /**
     * Cuenta el número de streams de un tipo específico.
     *
     * @param streamKind Tipo de stream (usar constantes de {@link StreamKind}).
     * @return Número de streams de ese tipo, 0 si no hay streams, el tipo es inválido
     *         o el archivo no está abierto.
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public int countStreams(int streamKind) {
        synchronized (lock) {
            checkAlive();
            if (state != State.OPEN || streamKind < 0 || streamKind >= StreamKind.COUNT) {
                return 0;
            }
            return NativeBridge.nativeCountStreams(handle, streamKind);
        }
    }

    private static boolean isValidRequest(int streamKind, int streamNumber) {
        return streamKind >= 0 && streamKind < StreamKind.COUNT && streamNumber >= 0;
    }

    // ---- Getters tipados ----------------------------------------------------

    /**
     * Indica si el parámetro tiene un valor no vacío.
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public boolean has(int streamKind, int streamNumber, String parameter) {
        return !get(streamKind, streamNumber, parameter).isEmpty();
    }

    /**
     * Obtiene un parámetro como {@code int}. Nunca lanza por valor ausente o no numérico.
     * <p>
     * Acepta decimales (por ejemplo {@code "29.970"} devuelve 29: se trunca). Devuelve
     * {@code defaultValue} si el valor está vacío, no es numérico, es compuesto (como
     * {@code "1 / 2"}) o queda fuera del rango de {@code int}.
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public int getInt(int streamKind, int streamNumber, String parameter, int defaultValue) {
        Double value = parseDecimal(numericText(get(streamKind, streamNumber, parameter)));
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return defaultValue;
        }
        return (int) value.doubleValue();
    }

    /**
     * Obtiene un parámetro como {@code long}. Misma política que {@link #getInt}.
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public long getLong(int streamKind, int streamNumber, String parameter, long defaultValue) {
        String text = numericText(get(streamKind, streamNumber, parameter));
        if (text == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(text);   // exacto para enteros grandes (FileSize, etc.)
        } catch (NumberFormatException ignored) {
            // Puede ser decimal: se intenta vía double.
        }
        Double value = parseDecimal(text);
        if (value == null || value >= 9.223372036854775807E18 || value < -9.223372036854775808E18) {
            return defaultValue;
        }
        return (long) value.doubleValue();
    }

    /**
     * Obtiene un parámetro como {@code double}, independiente del locale. Devuelve
     * {@code defaultValue} si el valor está vacío, no es numérico, es compuesto o no es finito.
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public double getDouble(int streamKind, int streamNumber, String parameter, double defaultValue) {
        Double value = parseDecimal(numericText(get(streamKind, streamNumber, parameter)));
        return (value != null) ? value : defaultValue;
    }

    /** Equivalente a {@code getInt(StreamKind.GENERAL, 0, parameter, defaultValue)}. */
    public int getGeneralInt(String parameter, int defaultValue) {
        return getInt(StreamKind.GENERAL, 0, parameter, defaultValue);
    }

    /** Equivalente a {@code getLong(StreamKind.GENERAL, 0, parameter, defaultValue)}. */
    public long getGeneralLong(String parameter, long defaultValue) {
        return getLong(StreamKind.GENERAL, 0, parameter, defaultValue);
    }

    /** Equivalente a {@code getDouble(StreamKind.GENERAL, 0, parameter, defaultValue)}. */
    public double getGeneralDouble(String parameter, double defaultValue) {
        return getDouble(StreamKind.GENERAL, 0, parameter, defaultValue);
    }

    /** Devuelve el texto sin espacios si solo tiene caracteres numéricos; si no, {@code null}. */
    private static String numericText(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // Se rechaza todo lo que Double.parseDouble aceptaría pero no es un número simple
        // ("NaN", "Infinity", "0x1p3", sufijos "1d"/"1f"). Valores como "1 / 2" también quedan fuera.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E';
            if (!allowed) {
                return null;
            }
        }
        return text;
    }

    private static Double parseDecimal(String text) {
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Diagnóstico de campos ----------------------------------------------

    /**
     * Explica por qué un campo tiene (o no) valor. Pensado para llamarse solo cuando
     * {@link #get(int, int, String)} devolvió vacío.
     * <p>
     * <b>Limitación:</b> {@link FieldStatus#NO_VALUE} cubre tanto «el archivo no trae el campo»
     * como «el archivo lo trae vacío»: MediaInfo no los distingue.
     *
     * @return Nunca {@code null}. No lanza excepciones salvo por mal uso.
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public FieldStatus getFieldStatus(int streamKind, int streamNumber, String parameter) {
        synchronized (lock) {
            checkAlive();
            if (state != State.OPEN) {
                return FieldStatus.FILE_NOT_OPEN;
            }
            if (!isValidRequest(streamKind, streamNumber) || parameter == null || parameter.isEmpty()) {
                return FieldStatus.INVALID_REQUEST;
            }
            return mapFieldStatus(NativeBridge.nativeGetFieldStatus(handle, streamKind, streamNumber, parameter));
        }
    }

    /**
     * Equivalente a {@code getFieldStatus(StreamKind.GENERAL, 0, parameter)}.
     *
     * @throws IllegalStateException si la instancia ya fue cerrada
     */
    public FieldStatus getGeneralFieldStatus(String parameter) {
        return getFieldStatus(StreamKind.GENERAL, 0, parameter);
    }

    private static FieldStatus mapFieldStatus(int code) {
        switch (code) {
            case 0: return FieldStatus.HAS_VALUE;
            case 1: return FieldStatus.FILE_NOT_OPEN;
            case 2: return FieldStatus.INVALID_REQUEST;
            case 3: return FieldStatus.STREAM_NOT_PRESENT;
            case 4: return FieldStatus.UNKNOWN_PARAMETER;
            case 5: return FieldStatus.NO_VALUE;
            default: return FieldStatus.NO_VALUE;   // incluye -1 (excepción nativa)
        }
    }

    // ==========================================================================
    // Cierre
    // ==========================================================================

    /**
     * Cierra el archivo actualmente abierto y deja la instancia lista para abrir otro.
     * <p>
     * Es el comportamiento que tenía {@code close()} en la 0.1.0. No libera la instancia
     * nativa; para eso usar {@link #close()}. Reinicia {@link #isPartial()}. Si la instancia ya
     * fue cerrada con {@link #close()}, no hace nada.
     */
    public void closeFile() {
        synchronized (lock) {
            if (state == State.DESTROYED) {
                return;
            }
            if (state == State.OPEN) {
                NativeBridge.nativeClose(handle);
            }
            state = State.IDLE;
            partial = false;
        }
    }

    /**
     * Libera todo: cierra el archivo y destruye la instancia nativa.
     * <p>
     * Es idempotente. Tras llamarlo, cualquier otro uso lanza {@link IllegalStateException}
     * (salvo {@code close()}, {@code destroy()} y los {@code getLast*}). Pensado para
     * {@code try-with-resources} (Java) y {@code use {}} (Kotlin).
     */
    @Override
    public void close() {
        synchronized (lock) {
            releaseLocked();
        }
    }

    /**
     * Libera los recursos nativos.
     *
     * @deprecated Usar {@link #close()} (con try-with-resources / {@code use {}}). Es un alias.
     */
    @Deprecated
    public void destroy() {
        close();
    }

    /** Libera el handle nativo si sigue vivo. Requiere lock. */
    private void releaseLocked() {
        if (state == State.DESTROYED) {
            return;
        }
        long h = handle;
        handle = 0L;
        state = State.DESTROYED;
        partial = false;
        if (h != 0L) {
            NativeBridge.nativeDestroy(h);   // el destructor nativo también cierra el archivo
        }
    }

    /**
     * Red de seguridad: si el desarrollador olvidó cerrar, libera la memoria nativa.
     * Adquiere el mismo lock para no correr contra una llamada nativa en curso. Nunca lanza.
     */
    @SuppressWarnings({"deprecation", "removal"})
    @Override
    protected void finalize() throws Throwable {
        try {
            synchronized (lock) {
                if (state != State.DESTROYED) {
                    boolean hadNative = handle != 0L;
                    releaseLocked();
                    if (hadNative) {
                        Log.w(TAG, "Instancia no cerrada; use try-with-resources");
                    }
                }
            }
        } catch (Throwable ignored) {
            // Un finalizador nunca debe lanzar.
        } finally {
            super.finalize();
        }
    }

    // ==========================================================================
    // Versiones
    // ==========================================================================

    /**
     * Obtiene la versión de MediaInfoLib embebida (se consulta una sola vez a la capa nativa).
     *
     * @return String con la versión de MediaInfoLib
     */
    public static String getMediaInfoVersion() {
        String version = cachedMediaInfoVersion;
        if (version == null) {
            version = NativeBridge.nativeGetMediaInfoVersion();
            if (version == null) {
                return "";
            }
            cachedMediaInfoVersion = version;
        }
        return version;
    }

    /**
     * Obtiene la versión de ZenLib embebida (se consulta una sola vez a la capa nativa).
     *
     * @return String con la versión de ZenLib
     */
    public static String getZenLibVersion() {
        String version = cachedZenLibVersion;
        if (version == null) {
            version = NativeBridge.nativeGetZenLibVersion();
            if (version == null) {
                return "";
            }
            cachedZenLibVersion = version;
        }
        return version;
    }
}
