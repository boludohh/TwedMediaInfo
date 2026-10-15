/*
 * Copyright 2026 TwedMediaInfo — boludohh
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
 * Explica por qué una consulta devolvió (o no) un valor.
 * <p>
 * Se obtiene con {@link TwedMediaInfo#getFieldStatus(int, int, String)} y
 * {@link TwedMediaInfo#getGeneralFieldStatus(String)}. Está pensado para llamarse
 * <b>solo cuando {@code get(...)} devolvió vacío</b>: {@code get(...)} no cambia
 * ni cuesta más por existir este diagnóstico.
 * <p>
 * <b>Limitación:</b> MediaInfo no distingue «el archivo no trae el campo» de
 * «el archivo lo trae vacío». Ambos casos se informan como {@link #NO_VALUE}.
 */
public enum FieldStatus {

    /** El campo tiene un valor no vacío. */
    HAS_VALUE,

    /** No hay archivo abierto (instancia sin abrir o el {@code open} falló). */
    FILE_NOT_OPEN,

    /** Petición inválida: tipo de stream fuera de rango, número negativo, parámetro nulo o vacío. */
    INVALID_REQUEST,

    /** El archivo no tiene ese stream (ni ese tipo de stream ni ese número). */
    STREAM_NOT_PRESENT,

    /** Ese nombre no es un campo conocido para ese tipo de stream (posible error de tipeo). */
    UNKNOWN_PARAMETER,

    /** Campo válido, el stream existe, pero no tiene valor. */
    NO_VALUE
}
