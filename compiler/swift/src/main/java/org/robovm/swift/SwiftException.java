/*
 * Copyright (C) 2026 RoboVM Swift Bridge contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.robovm.swift;

/**
 * Ein von der Swift-Seite gemeldeter Fehler.
 *
 * <p>Wichtige Abgrenzung: das hier entspricht einem Swift-{@code throws}, das
 * der Shim in einen Statuscode plus Fehler-Handle uebersetzt hat. Swift-<em>Traps</em>
 * -- {@code fatalError}, Force-Unwrap auf {@code nil}, Array-Overflow,
 * Integer-Overflow -- sind damit <em>nicht</em> abgedeckt. Die erzeugen eine
 * Trap-Instruktion und beenden den Prozess; sie lassen sich nicht in eine
 * Java-Exception verwandeln. Deshalb die Shim-Regel: keine Force-Unwraps,
 * jede fehlbare Operation als Statuscode.
 */
public class SwiftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SwiftException(String message) {
        super(message);
    }

    public SwiftException(String message, Throwable cause) {
        super(message, cause);
    }
}
