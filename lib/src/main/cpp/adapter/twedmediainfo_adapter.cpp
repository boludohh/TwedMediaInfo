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

#include "twedmediainfo_adapter.h"

#include <memory>

#include <MediaInfo/MediaInfo.h>
#include <ZenLib/Ztring.h>
#include <ZenLib/Conf.h>

// Versión de ZenLib: la define CMake (TWED_ZENLIB_VERSION) a partir de la carpeta
// third_party/ZenLib-<versión>. El valor de respaldo evita fallar si se compila sin CMake.
#ifndef TWED_ZENLIB_VERSION
#define TWED_ZENLIB_VERSION "unknown"
#endif

namespace twedmediainfo {

namespace {

// Cantidad de tipos de stream válidos (General..Menu).
constexpr int kStreamMax = static_cast<int>(MediaInfoLib::Stream_Max);

inline MediaInfoLib::MediaInfo* asMediaInfo(void* handle) {
    return static_cast<MediaInfoLib::MediaInfo*>(handle);
}

// MediaInfo usa (int64u)-1 como «tamaño desconocido».
inline ZenLib::int64u toSizeParam(int64_t fileSize) {
    return (fileSize >= 0)
        ? static_cast<ZenLib::int64u>(fileSize)
        : static_cast<ZenLib::int64u>(-1);
}

} // namespace

TwedMediaInfoAdapter::TwedMediaInfoAdapter()
    : nativeHandle_(nullptr)
    , mode_(Mode::None) {
}

TwedMediaInfoAdapter::~TwedMediaInfoAdapter() {
    close();
}

// ==============================================================================
// Versiones
// ==============================================================================

std::string TwedMediaInfoAdapter::mediaInfoVersion() {
    // Option_Static es estático: no hace falta crear un MediaInfo completo.
    // La inicialización de una variable estática local es thread-safe (C++11).
    static const std::string version = []() {
        ZenLib::Ztring v = MediaInfoLib::MediaInfo::Option_Static(__T("Info_Version"));
        return v.To_UTF8();
    }();
    return version;
}

std::string TwedMediaInfoAdapter::zenLibVersion() {
    return std::string(TWED_ZENLIB_VERSION);
}

// ==============================================================================
// Apertura / cierre
// ==============================================================================

void TwedMediaInfoAdapter::setLastError(const std::string& message) {
    lastNativeError_ = message;
}

bool TwedMediaInfoAdapter::open(const std::string& path) {
    lastNativeError_.clear();
    close();

    std::unique_ptr<MediaInfoLib::MediaInfo> mi(new MediaInfoLib::MediaInfo());
    ZenLib::Ztring zPath = ZenLib::Ztring().From_UTF8(path);

    size_t result = mi->Open(zPath);
    if (result > 0) {
        nativeHandle_ = static_cast<void*>(mi.release());
        mode_ = Mode::File;
        return true;
    }

    lastNativeError_ = "MediaInfo no pudo abrir el archivo";
    return false;  // unique_ptr libera la instancia
}

bool TwedMediaInfoAdapter::openBufferInit(int64_t fileSize) {
    lastNativeError_.clear();
    close();

    std::unique_ptr<MediaInfoLib::MediaInfo> mi(new MediaInfoLib::MediaInfo());

    size_t result = mi->Open_Buffer_Init(toSizeParam(fileSize), 0);
    if (result > 0) {
        nativeHandle_ = static_cast<void*>(mi.release());
        mode_ = Mode::Buffer;
        return true;
    }

    lastNativeError_ = "MediaInfo no pudo iniciar el análisis por buffer";
    return false;
}

bool TwedMediaInfoAdapter::openBufferSeek(int64_t fileSize, int64_t offset) {
    if (mode_ != Mode::Buffer || nativeHandle_ == nullptr) {
        lastNativeError_ = "No hay un análisis por buffer activo";
        return false;
    }
    if (offset < 0) {
        lastNativeError_ = "Offset de seek inválido";
        return false;
    }

    // Misma instancia: MediaInfo reutiliza su parser y solo se posiciona
    // (MediaInfo_Internal::Open_Buffer_Init(File_Size, File_Offset)).
    auto* mi = asMediaInfo(nativeHandle_);
    size_t result = mi->Open_Buffer_Init(toSizeParam(fileSize), static_cast<ZenLib::int64u>(offset));
    if (result > 0) {
        return true;
    }

    lastNativeError_ = "MediaInfo no pudo reposicionarse tras el seek";
    return false;
}

int TwedMediaInfoAdapter::openBufferContinue(const uint8_t* buffer, size_t size) {
    if (mode_ != Mode::Buffer || nativeHandle_ == nullptr) {
        return -1;
    }

    auto* mi = asMediaInfo(nativeHandle_);

    size_t result = mi->Open_Buffer_Continue(
        static_cast<const ZenLib::int8u*>(buffer),
        size
    );

    return static_cast<int>(result);
}

int64_t TwedMediaInfoAdapter::openBufferGoToGet() {
    if (mode_ != Mode::Buffer || nativeHandle_ == nullptr) {
        return -1;
    }

    auto* mi = asMediaInfo(nativeHandle_);

    ZenLib::int64u goTo = mi->Open_Buffer_Continue_GoTo_Get();

    // MediaInfo retorna (ZenLib::int64u)-1 si no hay seek pendiente
    // Convertir a int64_t para retornar -1 en ese caso
    if (goTo == static_cast<ZenLib::int64u>(-1)) {
        return -1;
    }

    return static_cast<int64_t>(goTo);
}

bool TwedMediaInfoAdapter::openBufferFinalize() {
    if (mode_ != Mode::Buffer || nativeHandle_ == nullptr) {
        lastNativeError_ = "No hay un análisis por buffer activo";
        return false;
    }

    auto* mi = asMediaInfo(nativeHandle_);

    size_t result = mi->Open_Buffer_Finalize();
    if (result == 0) {
        lastNativeError_ = "MediaInfo no pudo finalizar el análisis";
        return false;
    }

    // El modo sigue siendo Buffer: los streams se conservan.
    return true;
}

void TwedMediaInfoAdapter::close() {
    if (nativeHandle_ != nullptr) {
        auto* mi = asMediaInfo(nativeHandle_);
        // Primero se deja el adapter en un estado coherente: aunque Close() lance,
        // no queda un puntero colgante.
        nativeHandle_ = nullptr;
        mode_ = Mode::None;
        try {
            mi->Close();
        } catch (...) {
            // El destructor llama a close(): nunca debe propagar.
        }
        delete mi;
    }
    mode_ = Mode::None;
}

// ==============================================================================
// Consultas
// ==============================================================================

std::string TwedMediaInfoAdapter::getGeneralInfo(const std::string& parameter) const {
    return get(0, 0, parameter);  // Stream_General = 0, stream 0
}

std::string TwedMediaInfoAdapter::get(int streamKind, int streamNumber, const std::string& parameter) const {
    if (!isActive()) {
        return "";
    }
    if (streamKind < 0 || streamKind >= kStreamMax || streamNumber < 0) {
        return "";
    }

    auto* mi = asMediaInfo(nativeHandle_);
    ZenLib::Ztring zParam = ZenLib::Ztring().From_UTF8(parameter);

    ZenLib::Ztring value = mi->Get(
        static_cast<MediaInfoLib::stream_t>(streamKind),
        static_cast<size_t>(streamNumber),
        zParam
    );

    return value.To_UTF8();
}

int TwedMediaInfoAdapter::countStreams(int streamKind) const {
    if (!isActive()) {
        return 0;
    }
    if (streamKind < 0 || streamKind >= kStreamMax) {
        return 0;
    }

    auto* mi = asMediaInfo(nativeHandle_);

    size_t count = mi->Count_Get(
        static_cast<MediaInfoLib::stream_t>(streamKind)
    );

    return static_cast<int>(count);
}

int TwedMediaInfoAdapter::fieldStatus(int streamKind, int streamNumber, const std::string& parameter) const {
    if (!isActive()) {
        return 1;  // FILE_NOT_OPEN
    }
    if (streamKind < 0 || streamKind >= kStreamMax || streamNumber < 0 || parameter.empty()) {
        return 2;  // INVALID_REQUEST
    }

    auto* mi = asMediaInfo(nativeHandle_);
    auto kind = static_cast<MediaInfoLib::stream_t>(streamKind);
    auto num = static_cast<size_t>(streamNumber);

    // Cantidad de streams de ese tipo presentes en el archivo.
    if (mi->Count_Get(kind) <= num) {
        return 3;  // STREAM_NOT_PRESENT
    }

    ZenLib::Ztring zParam = ZenLib::Ztring().From_UTF8(parameter);

    if (!mi->Get(kind, num, zParam).empty()) {
        return 0;  // HAS_VALUE
    }

    // Vacío: ¿el nombre existe en el diccionario de ese tipo de stream?
    // Los alias heredados (Artist, Channels, PlayTime...) se resuelven dentro de Get()
    // antes de buscar en el diccionario, así que también salen como conocidos.
    if (mi->Get(kind, num, zParam, MediaInfoLib::Info_Name).empty()) {
        return 4;  // UNKNOWN_PARAMETER
    }

    return 5;  // NO_VALUE (campo válido, stream existe, sin valor)
}

} // namespace twedmediainfo
