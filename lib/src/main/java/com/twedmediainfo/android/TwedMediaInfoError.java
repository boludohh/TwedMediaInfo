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

/**
 * Motivo por el que falló el último {@code open(...)} de {@link TwedMediaInfo}.
 * <p>
 * Se consulta con {@link TwedMediaInfo#getLastError()}. Los métodos {@code open}
 * no lanzan excepciones por fallos esperados: devuelven {@code false} y dejan
 * aquí el motivo.
 */
public enum TwedMediaInfoError {

    /** El último {@code open} tuvo éxito (o todavía no se llamó a ninguno). */
    NONE,

    /** Argumento inválido: {@code null}, ruta vacía, directorio, Uri mal formado, etc. */
    INVALID_ARGUMENT,

    /** El archivo (o el contenido del Uri) no existe. */
    FILE_NOT_FOUND,

    /** Sin permiso de lectura ({@link SecurityException} o errno EACCES/EPERM). */
    PERMISSION_DENIED,

    /** Falló la lectura ({@link java.io.IOException}) o el proveedor no entregó un descriptor. */
    IO_ERROR,

    /** El archivo tiene tamaño 0. */
    EMPTY_FILE,

    /** MediaInfo pidió demasiados saltos: protección contra bucles. */
    TOO_MANY_SEEKS,

    /** El hilo fue interrumpido mientras se abría el archivo. */
    CANCELLED,

    /** No se pudo iniciar el análisis en la capa nativa. */
    NATIVE_INIT_FAILED,

    /** La capa nativa reportó un fallo o una excepción. */
    NATIVE_ERROR,

    /** {@link RuntimeException} inesperada; la causa está en {@link TwedMediaInfo#getLastException()}. */
    UNEXPECTED
}
