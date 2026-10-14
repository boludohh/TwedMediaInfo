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

#include <jni.h>
#include <android/log.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <string>
#include <vector>

#include "../adapter/twedmediainfo_adapter.h"

#define LOG_TAG "TwedMediaInfo"

// Los logs rutinarios solo existen en debug. LOGE se conserva en release.
// Ningún log debe incluir rutas de archivo.
#ifdef NDEBUG
#define LOGI(...) ((void)0)
#define LOGD(...) ((void)0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using twedmediainfo::TwedMediaInfoAdapter;

namespace {

// ==============================================================================
// Conversión de strings: UTF-8 estándar <-> UTF-16 (jchar)
//
// NewStringUTF / GetStringUTFChars trabajan con "Modified UTF-8" (los code points
// fuera del BMP van como dos sustitutos de 3 bytes y U+0000 como C0 80). MediaInfo
// entrega y espera UTF-8 estándar, así que se convierte a mano.
// ==============================================================================

constexpr size_t kStackChars = 256;     // atajo ASCII: buffer en la pila
constexpr jchar kReplacement = 0xFFFD;  // U+FFFD

void appendUtf16(std::u16string& out, uint32_t cp) {
    if (cp <= 0xFFFF) {
        out.push_back(static_cast<char16_t>(cp));
    } else {
        cp -= 0x10000;
        out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
        out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    }
}

// Decodifica UTF-8 estándar. Cada secuencia inválida se sustituye por U+FFFD:
// bytes de inicio inválidos, secuencias truncadas, overlong, sustitutos
// codificados (U+D800-U+DFFF) y valores > U+10FFFF.
std::u16string decodeUtf8(const std::string& in) {
    std::u16string out;
    out.reserve(in.size());

    const auto* p = reinterpret_cast<const unsigned char*>(in.data());
    const size_t n = in.size();
    size_t i = 0;

    while (i < n) {
        const unsigned char b = p[i];

        if (b < 0x80) {
            out.push_back(static_cast<char16_t>(b));
            ++i;
            continue;
        }

        uint32_t cp = 0;
        size_t need = 0;
        uint32_t minCp = 0;

        if (b >= 0xC2 && b <= 0xDF) {
            need = 1; cp = b & 0x1F; minCp = 0x80;
        } else if (b >= 0xE0 && b <= 0xEF) {
            need = 2; cp = b & 0x0F; minCp = 0x800;
        } else if (b >= 0xF0 && b <= 0xF4) {
            need = 3; cp = b & 0x07; minCp = 0x10000;
        } else {
            // 0x80-0xC1 (continuación suelta u overlong de 2 bytes) y 0xF5-0xFF
            out.push_back(kReplacement);
            ++i;
            continue;
        }

        size_t j = 1;
        bool complete = true;
        for (; j <= need; ++j) {
            if (i + j >= n || (p[i + j] & 0xC0) != 0x80) {
                complete = false;
                break;
            }
            cp = (cp << 6) | (p[i + j] & 0x3F);
        }

        if (!complete) {
            // Secuencia truncada: se consume el inicio y los bytes válidos que lo siguen.
            out.push_back(kReplacement);
            i += j;
            continue;
        }

        i += need + 1;

        if (cp < minCp || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(kReplacement);
        } else {
            appendUtf16(out, cp);
        }
    }

    return out;
}

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// Codifica UTF-16 a UTF-8 estándar. Un par sustituto válido produce 4 bytes;
// un sustituto suelto produce U+FFFD.
std::string encodeUtf16(const jchar* s, size_t len) {
    std::string out;
    out.reserve(len + len / 2);

    size_t i = 0;
    while (i < len) {
        uint32_t c = s[i++];
        if (c >= 0xD800 && c <= 0xDBFF) {
            if (i < len && s[i] >= 0xDC00 && s[i] <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (static_cast<uint32_t>(s[i]) - 0xDC00);
                ++i;
            } else {
                c = kReplacement;
            }
        } else if (c >= 0xDC00 && c <= 0xDFFF) {
            c = kReplacement;
        }
        appendUtf8(out, c);
    }

    return out;
}

// std::string (UTF-8) -> jstring. Devuelve nullptr si NewString falla (OOM, con
// excepción Java pendiente).
jstring toJString(JNIEnv* env, const std::string& utf8) {
    const size_t n = utf8.size();

    // Camino rápido: ASCII corto, sin usar el heap.
    if (n <= kStackChars) {
        jchar buf[kStackChars];
        bool ascii = true;
        for (size_t i = 0; i < n; ++i) {
            const unsigned char c = static_cast<unsigned char>(utf8[i]);
            if (c >= 0x80) {
                ascii = false;
                break;
            }
            buf[i] = static_cast<jchar>(c);
        }
        if (ascii) {
            return env->NewString(buf, static_cast<jsize>(n));
        }
    }

    // Camino general.
    std::u16string u16 = decodeUtf8(utf8);
    return env->NewString(reinterpret_cast<const jchar*>(u16.data()),
                          static_cast<jsize>(u16.size()));
}

// jstring -> std::string (UTF-8 estándar). nullptr -> "".
std::string fromJString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return std::string();
    }

    const jsize len = env->GetStringLength(jstr);
    if (len <= 0) {
        return std::string();
    }
    const size_t ulen = static_cast<size_t>(len);

    // Camino rápido: cadena corta en la pila; si es ASCII se copia directo.
    if (ulen <= kStackChars) {
        jchar buf[kStackChars];
        env->GetStringRegion(jstr, 0, len, buf);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return std::string();
        }

        bool ascii = true;
        for (size_t i = 0; i < ulen; ++i) {
            if (buf[i] >= 0x80) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            std::string out(ulen, '\0');
            for (size_t i = 0; i < ulen; ++i) {
                out[i] = static_cast<char>(buf[i]);
            }
            return out;
        }
        return encodeUtf16(buf, ulen);
    }

    // Camino general.
    std::vector<jchar> chars(ulen);
    env->GetStringRegion(jstr, 0, len, chars.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return std::string();
    }
    return encodeUtf16(chars.data(), ulen);
}

// jstring vacío sin usar contenedores de la STL (seguro dentro de un catch).
jstring emptyJString(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    static const jchar kEmpty[1] = {0};
    return env->NewString(kEmpty, 0);
}

// ==============================================================================
// Utilidades
// ==============================================================================

inline TwedMediaInfoAdapter* fromHandle(jlong handle) {
    return (handle == 0) ? nullptr : reinterpret_cast<TwedMediaInfoAdapter*>(handle);
}

// Registra una excepción C++ atrapada. Nunca debe lanzar (se usa dentro de un catch).
void onNativeException(TwedMediaInfoAdapter* adapter, const char* where, const char* what) {
    LOGE("%s: excepcion nativa: %s", where, what);
    if (adapter != nullptr) {
        try {
            std::string msg(where);
            msg += ": ";
            msg += what;
            adapter->setLastError(msg);
        } catch (...) {
            // Sin memoria para el mensaje: se ignora.
        }
    }
}

// Ninguna excepción C++ puede cruzar la frontera JNI: cada función exportada
// envuelve su cuerpo en try { ... } TWED_CATCH_ALL(...) y devuelve un valor neutro.
#define TWED_CATCH_ALL(WHERE, ADAPTER, RETVAL)                                   \
    catch (const std::exception& e) {                                            \
        onNativeException((ADAPTER), (WHERE), e.what());                         \
        return RETVAL;                                                           \
    } catch (...) {                                                              \
        onNativeException((ADAPTER), (WHERE), "excepcion desconocida");          \
        return RETVAL;                                                           \
    }

#define TWED_CATCH_ALL_VOID(WHERE, ADAPTER)                                      \
    catch (const std::exception& e) {                                            \
        onNativeException((ADAPTER), (WHERE), e.what());                         \
        return;                                                                  \
    } catch (...) {                                                              \
        onNativeException((ADAPTER), (WHERE), "excepcion desconocida");          \
        return;                                                                  \
    }

} // namespace

// ==============================================================================
// JNI Methods
// ==============================================================================

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeGetMediaInfoVersion(
        JNIEnv* env, jclass /* clazz */) {
    try {
        return toJString(env, TwedMediaInfoAdapter::mediaInfoVersion());
    } TWED_CATCH_ALL("nativeGetMediaInfoVersion", nullptr, emptyJString(env))
}

JNIEXPORT jstring JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeGetZenLibVersion(
        JNIEnv* env, jclass /* clazz */) {
    try {
        return toJString(env, TwedMediaInfoAdapter::zenLibVersion());
    } TWED_CATCH_ALL("nativeGetZenLibVersion", nullptr, emptyJString(env))
}

JNIEXPORT jlong JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeCreate(
        JNIEnv* /* env */, jclass /* clazz */) {
    try {
        auto* adapter = new TwedMediaInfoAdapter();
        LOGD("Native adapter created");
        return reinterpret_cast<jlong>(adapter);
    } TWED_CATCH_ALL("nativeCreate", nullptr, 0)
}

JNIEXPORT jboolean JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeOpen(
        JNIEnv* env, jclass /* clazz */, jlong handle, jstring path) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeOpen: invalid handle");
            return JNI_FALSE;
        }

        std::string filePath = fromJString(env, path);
        if (filePath.empty()) {
            adapter->setLastError("Ruta vacia");
            LOGE("nativeOpen: empty path");
            return JNI_FALSE;
        }

        bool result = adapter->open(filePath);
        if (result) {
            LOGD("File opened");
        } else {
            LOGE("nativeOpen: MediaInfo could not open the file");
        }
        return result ? JNI_TRUE : JNI_FALSE;
    } TWED_CATCH_ALL("nativeOpen", adapter, JNI_FALSE)
}

JNIEXPORT jboolean JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeOpenBufferInit(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle, jlong fileSize) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeOpenBufferInit: invalid handle");
            return JNI_FALSE;
        }

        bool result = adapter->openBufferInit(static_cast<int64_t>(fileSize));
        if (result) {
            LOGD("Buffer initialized with fileSize: %lld", static_cast<long long>(fileSize));
        } else {
            LOGE("Failed to initialize buffer");
        }
        return result ? JNI_TRUE : JNI_FALSE;
    } TWED_CATCH_ALL("nativeOpenBufferInit", adapter, JNI_FALSE)
}

JNIEXPORT jboolean JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeOpenBufferSeek(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle, jlong fileSize, jlong offset) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeOpenBufferSeek: invalid handle");
            return JNI_FALSE;
        }

        bool result = adapter->openBufferSeek(static_cast<int64_t>(fileSize),
                                              static_cast<int64_t>(offset));
        if (result) {
            LOGD("Buffer seek to offset: %lld", static_cast<long long>(offset));
        } else {
            LOGE("nativeOpenBufferSeek: seek failed");
        }
        return result ? JNI_TRUE : JNI_FALSE;
    } TWED_CATCH_ALL("nativeOpenBufferSeek", adapter, JNI_FALSE)
}

// Devuelve el bitfield de estado de MediaInfo, o -1 si hubo un error
// (handle/argumentos inválidos, excepción nativa, no hay análisis por buffer).
JNIEXPORT jint JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeOpenBufferContinue(
        JNIEnv* env, jclass /* clazz */, jlong handle, jbyteArray buffer, jint size) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeOpenBufferContinue: invalid handle");
            return -1;
        }
        if (buffer == nullptr || size < 0) {
            LOGE("nativeOpenBufferContinue: invalid buffer or size");
            adapter->setLastError("Buffer o tamano invalido");
            return -1;
        }

        const jsize arrayLength = env->GetArrayLength(buffer);
        if (size > arrayLength) {
            LOGE("nativeOpenBufferContinue: size exceeds array length");
            adapter->setLastError("El tamano supera el largo del array");
            return -1;
        }
        if (size == 0) {
            return 0;
        }

        // Buffer reutilizable: se reserva en el primer Continue, crece solo si
        // `size` lo exige y se libera en el destructor del adapter.
        std::vector<uint8_t>& scratch = adapter->scratchBuffer();
        const size_t needed = static_cast<size_t>(size);
        if (scratch.size() < needed) {
            scratch.resize(needed);
        }

        // GetByteArrayRegion copia sin bloquear el GC (a diferencia de GetPrimitiveArrayCritical).
        env->GetByteArrayRegion(buffer, 0, size, reinterpret_cast<jbyte*>(scratch.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("nativeOpenBufferContinue: GetByteArrayRegion failed");
            adapter->setLastError("No se pudo copiar el buffer");
            return -1;
        }

        return static_cast<jint>(adapter->openBufferContinue(scratch.data(), needed));
    } TWED_CATCH_ALL("nativeOpenBufferContinue", adapter, -1)
}

JNIEXPORT jlong JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeOpenBufferGoToGet(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeOpenBufferGoToGet: invalid handle");
            return -1;
        }

        return static_cast<jlong>(adapter->openBufferGoToGet());
    } TWED_CATCH_ALL("nativeOpenBufferGoToGet", adapter, -1)
}

JNIEXPORT jboolean JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeOpenBufferFinalize(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeOpenBufferFinalize: invalid handle");
            return JNI_FALSE;
        }

        bool result = adapter->openBufferFinalize();
        if (result) {
            LOGD("Buffer finalized successfully");
        } else {
            LOGE("Failed to finalize buffer");
        }
        return result ? JNI_TRUE : JNI_FALSE;
    } TWED_CATCH_ALL("nativeOpenBufferFinalize", adapter, JNI_FALSE)
}

JNIEXPORT jstring JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeGetGeneral(
        JNIEnv* env, jclass /* clazz */, jlong handle, jstring parameter) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeGetGeneral: invalid handle");
            return emptyJString(env);
        }

        std::string param = fromJString(env, parameter);
        std::string value = adapter->getGeneralInfo(param);
        return toJString(env, value);
    } TWED_CATCH_ALL("nativeGetGeneral", adapter, emptyJString(env))
}

JNIEXPORT jstring JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeGet(
        JNIEnv* env, jclass /* clazz */, jlong handle,
        jint streamKind, jint streamNumber, jstring parameter) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeGet: invalid handle");
            return emptyJString(env);
        }

        std::string param = fromJString(env, parameter);
        std::string value = adapter->get(streamKind, streamNumber, param);
        return toJString(env, value);
    } TWED_CATCH_ALL("nativeGet", adapter, emptyJString(env))
}

JNIEXPORT jint JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeCountStreams(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle, jint streamKind) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeCountStreams: invalid handle");
            return 0;
        }

        return static_cast<jint>(adapter->countStreams(streamKind));
    } TWED_CATCH_ALL("nativeCountStreams", adapter, 0)
}

// Diagnóstico de campo. Códigos: 0 HAS_VALUE, 1 FILE_NOT_OPEN, 2 INVALID_REQUEST,
// 3 STREAM_NOT_PRESENT, 4 UNKNOWN_PARAMETER, 5 NO_VALUE. Ante excepción devuelve -1.
JNIEXPORT jint JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeGetFieldStatus(
        JNIEnv* env, jclass /* clazz */, jlong handle,
        jint streamKind, jint streamNumber, jstring parameter) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            LOGE("nativeGetFieldStatus: invalid handle");
            return -1;
        }

        std::string param = fromJString(env, parameter);
        return static_cast<jint>(adapter->fieldStatus(streamKind, streamNumber, param));
    } TWED_CATCH_ALL("nativeGetFieldStatus", adapter, -1)
}

JNIEXPORT jstring JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeGetLastError(
        JNIEnv* env, jclass /* clazz */, jlong handle) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            return emptyJString(env);
        }

        return toJString(env, adapter->lastError());
    } TWED_CATCH_ALL("nativeGetLastError", adapter, emptyJString(env))
}

JNIEXPORT void JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeClose(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    TwedMediaInfoAdapter* adapter = nullptr;
    try {
        adapter = fromHandle(handle);
        if (adapter == nullptr) {
            return;
        }

        adapter->close();
        LOGD("File closed");
    } TWED_CATCH_ALL_VOID("nativeClose", adapter)
}

JNIEXPORT void JNICALL
Java_com_twedmediainfo_android_internal_NativeBridge_nativeDestroy(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    try {
        TwedMediaInfoAdapter* adapter = fromHandle(handle);
        if (adapter == nullptr) {
            return;
        }

        delete adapter;
        LOGD("Native adapter destroyed");
    } TWED_CATCH_ALL_VOID("nativeDestroy", nullptr)
}

} // extern "C"
