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

#ifndef TWEDMEDIAINFO_ADAPTER_H
#define TWEDMEDIAINFO_ADAPTER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace twedmediainfo {

/**
 * Adapter para MediaInfoLib 26.05
 * Proporciona una interfaz C++ limpia sobre MediaInfoLib para uso desde JNI.
 *
 * Esta clase NO es thread-safe: la serialización de las llamadas la hace
 * la capa Java ({@code TwedMediaInfo}) con un lock por instancia.
 */
class TwedMediaInfoAdapter {
public:
    /** Modo de análisis actual de la instancia. */
    enum class Mode { None, File, Buffer };

    TwedMediaInfoAdapter();
    ~TwedMediaInfoAdapter();

    // Prevent copying
    TwedMediaInfoAdapter(const TwedMediaInfoAdapter&) = delete;
    TwedMediaInfoAdapter& operator=(const TwedMediaInfoAdapter&) = delete;

    // ------------------------------------------------------------------
    // Versiones (estáticas: no crean ninguna instancia de MediaInfo)
    // ------------------------------------------------------------------

    /**
     * Obtiene la versión de MediaInfoLib (se calcula una sola vez).
     */
    static std::string mediaInfoVersion();

    /**
     * Obtiene la versión de ZenLib (definida en tiempo de compilación por CMake).
     */
    static std::string zenLibVersion();

    // ------------------------------------------------------------------
    // Apertura / cierre
    // ------------------------------------------------------------------

    /**
     * Abre un archivo para análisis.
     * @param path Ruta absoluta al archivo (UTF-8).
     * @return true si el archivo fue abierto correctamente.
     */
    bool open(const std::string& path);

    /**
     * Inicia un análisis nuevo por buffer: crea una instancia NUEVA de MediaInfo
     * y llama a Open_Buffer_Init(fileSize, 0).
     * @param fileSize Tamaño total del archivo (-1 si desconocido).
     * @return true si se inicializó correctamente.
     */
    bool openBufferInit(int64_t fileSize);

    /**
     * Informa a MediaInfo de que la fuente saltó a otra posición.
     * Usa la MISMA instancia (no recrea nada) y pasa el offset real:
     * Open_Buffer_Init(fileSize, offset).
     * @param fileSize Tamaño total del archivo (-1 si desconocido).
     * @param offset   Nueva posición absoluta (>= 0).
     * @return false si el modo no es Buffer, si offset < 0 o si falla.
     */
    bool openBufferSeek(int64_t fileSize, int64_t offset);

    /**
     * Continúa el análisis pasando un buffer de datos.
     * @param buffer Buffer con datos del archivo.
     * @param size Número de bytes válidos en el buffer.
     * @return Bitfield de estado:
     *         bit 0: Is Accepted (formato conocido)
     *         bit 1: Is Filled (datos principales recolectados)
     *         bit 2: Is Updated (algunos datos actualizados)
     *         bit 3: Is Finalized (no se necesitan más datos)
     *         o -1 si no hay un análisis por buffer activo.
     */
    int openBufferContinue(const uint8_t* buffer, size_t size);

    /**
     * Obtiene la posición de seek solicitada por MediaInfo.
     * @return Offset solicitado, o -1 si no hay seek pendiente,
     *         o fileSize si no se necesitan más bytes.
     */
    int64_t openBufferGoToGet();

    /**
     * Finaliza el análisis del buffer. El modo sigue siendo Buffer: los
     * streams se conservan y get()/countStreams() siguen funcionando.
     * @return true si se finalizó correctamente.
     */
    bool openBufferFinalize();

    /**
     * Cierra el archivo actualmente abierto y libera la instancia de MediaInfo.
     */
    void close();

    /** true si hay un análisis abierto (archivo o buffer) con instancia viva. */
    bool isActive() const { return mode_ != Mode::None && nativeHandle_ != nullptr; }

    Mode mode() const { return mode_; }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /**
     * Obtiene información general del archivo (Stream_General, stream 0).
     * @param parameter Nombre del parámetro (ej: "Format", "Duration").
     * @return Valor del parámetro o string vacío.
     */
    std::string getGeneralInfo(const std::string& parameter) const;

    /**
     * Obtiene información de cualquier stream.
     * @param streamKind Tipo de stream (0=General, 1=Video, 2=Audio, 3=Text, etc.)
     * @param streamNumber Número de stream (0-based)
     * @param parameter Nombre del parámetro
     * @return Valor del parámetro o string vacío (también si streamKind/streamNumber son inválidos)
     */
    std::string get(int streamKind, int streamNumber, const std::string& parameter) const;

    /**
     * Cuenta el número de streams de un tipo específico.
     * @param streamKind Tipo de stream (0=General, 1=Video, 2=Audio, 3=Text, etc.)
     * @return Número de streams de ese tipo (0 si streamKind está fuera de rango)
     */
    int countStreams(int streamKind) const;

    /**
     * Diagnóstico de un campo: por qué un valor está vacío.
     * Códigos: 0 HAS_VALUE, 1 FILE_NOT_OPEN, 2 INVALID_REQUEST,
     *          3 STREAM_NOT_PRESENT, 4 UNKNOWN_PARAMETER, 5 NO_VALUE.
     *
     * Limitación: MediaInfo no distingue «el archivo no trae el campo» de
     * «el archivo lo trae vacío»; ambos casos se informan como 5 (NO_VALUE).
     */
    int fieldStatus(int streamKind, int streamNumber, const std::string& parameter) const;

    // ------------------------------------------------------------------
    // Diagnóstico y buffers internos
    // ------------------------------------------------------------------

    /** Último error nativo corto (vacío si no hay). Se limpia al empezar cada open. */
    const std::string& lastError() const { return lastNativeError_; }

    /** Registra un error nativo corto (sin rutas de archivo). */
    void setLastError(const std::string& message);

    /** Buffer reutilizable para copiar los datos que llegan desde Java. */
    std::vector<uint8_t>& scratchBuffer() { return scratch_; }

private:
    void* nativeHandle_;  // MediaInfo* internally
    Mode mode_;
    std::string lastNativeError_;
    std::vector<uint8_t> scratch_;
};

} // namespace twedmediainfo

#endif // TWEDMEDIAINFO_ADAPTER_H
