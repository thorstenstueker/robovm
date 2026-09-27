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

import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Marshaler;
import org.robovm.rt.bro.annotation.Pointer;
import org.robovm.rt.bro.StringMarshalers;
import org.robovm.rt.bro.ptr.BytePtr;
import org.robovm.rt.bro.ptr.LongPtr;

/**
 * Einstiegspunkt in die Swift-Fassade (RvmSwiftBridge).
 *
 * <h3>Warum {@link Library#INTERNAL}</h3>
 * Der Shim wird als statisches xcframework ausgeliefert und landet damit direkt
 * im App-Executable -- es gibt keine eigene dylib, die man oeffnen koennte.
 * {@code Library.INTERNAL} laesst Bro ueber {@code dlopen(NULL)} im
 * Hauptexecutable aufloesen.
 *
 * <h3>Warum die Symbole ueberleben</h3>
 * RoboVM linkt auf Darwin immer mit {@code -dead_strip} und
 * {@code -exported_symbols_list} (siehe {@code AbstractTarget#build}), und
 * statische xcframework-Slices werden mit {@code force=false} eingebunden --
 * es gibt also kein {@code -force_load}. Ohne Gegenmassnahme wuerden die
 * C-Symbole wegoptimiert, weil sie nur per {@code dlsym} referenziert werden.
 * Deshalb deklariert
 * {@code src/main/robopods/META-INF/robovm/ios/robovm.xml} die Praefixe
 * {@code rvm_swift_*} und {@code rvm_demo_*} als {@code <exportedSymbols>};
 * exportierte Symbole sind Wurzeln fuer den Dead-Stripper.
 *
 * <h3>Threading</h3>
 * Alles, was SwiftUI beruehrt, muss auf dem MainActor laufen. Der Shim macht
 * den Hop selbst ({@code rvmOnMain}), die Java-Seite darf also von jedem
 * Thread aus rufen -- Rueckgaben sind dann aber erst nach dem Hop gueltig.
 */
@Library(Library.INTERNAL)
public final class SwiftRuntime {

    static { Bro.bind(SwiftRuntime.class); }

    /** Erfolg. */
    public static final int STATUS_OK = 0;
    /** Allgemeiner Fehler, {@code outError} ist gesetzt. */
    public static final int STATUS_ERROR = -1;
    /** Handle zeigt nicht auf den erwarteten Typ. */
    public static final int STATUS_BAD_HANDLE = -2;

    private SwiftRuntime() {}

    // -- Handle-Lebenszyklus ------------------------------------------------

    @Bridge(symbol = "rvm_swift_release")
    static native void release(@Pointer long handle);

    @Bridge(symbol = "rvm_swift_retain")
    static native @Pointer long retain(@Pointer long handle);

    /**
     * Anzahl der aktuell lebenden Swift-Handles. Nur fuer Tests gedacht --
     * die Grundlage der Leak-Erkennung.
     */
    @Bridge(symbol = "rvm_swift_live_handle_count")
    public static native long liveHandleCount();

    // -- Strings ------------------------------------------------------------

    @Bridge(symbol = "rvm_swift_free_string")
    static native void freeString(@Pointer long str);

    /**
     * Uebernimmt einen vom Shim mit {@code strdup} allokierten C-String,
     * kopiert ihn nach Java und gibt den nativen Speicher frei.
     *
     * @return {@code null}, wenn der Shim {@code NULL} geliefert hat
     */
    static String consumeString(BytePtr ptr) {
        if (ptr == null) {
            return null;
        }
        try {
            return ptr.toStringZ();
        } finally {
            freeString(ptr.getHandle());
        }
    }

    // -- Fehler -------------------------------------------------------------

    @Bridge(symbol = "rvm_swift_error_description")
    static native BytePtr errorDescription(@Pointer long errorHandle);

    /**
     * Uebersetzt einen Statuscode plus Fehler-Handle in eine Exception.
     * Gibt den Fehler-Handle dabei frei.
     *
     * @param status      Rueckgabe des Shim-Entrypoints
     * @param errorHandle geboxter Swift-Error oder {@code 0}, wenn der
     *                    Entrypoint keinen Fehler-Out-Parameter hat
     */
    public static void checkStatus(int status, long errorHandle) {
        if (status == STATUS_OK) {
            return;
        }
        if (status == STATUS_BAD_HANDLE) {
            throw new SwiftException("Swift rejected the handle (wrong type or already released)");
        }
        if (errorHandle == 0L) {
            throw new SwiftException("Swift call failed with status " + status);
        }
        try {
            String message = consumeString(errorDescription(errorHandle));
            throw new SwiftException(message != null ? message : "unknown Swift error");
        } finally {
            release(errorHandle);
        }
    }

    // -- Durchstich / Diagnose ----------------------------------------------

    @Bridge(symbol = "rvm_swift_echo")
    private static native BytePtr echo0(
            @Marshaler(StringMarshalers.AsUtf8ZMarshaler.class) String input);

    /** Schickt einen String nach Swift und zurueck. Ergebnis ist {@code "swift:" + input}. */
    public static String echo(String input) {
        return consumeString(echo0(input));
    }

    @Bridge(symbol = "rvm_swift_version")
    private static native BytePtr version0();

    /** Sprachmodus, gegen den der Shim gebaut wurde. */
    public static String swiftVersion() {
        return consumeString(version0());
    }

    @Bridge(symbol = "rvm_swift_double_or_fail")
    private static native int doubleOrFail0(long n, LongPtr outResult, LongPtr outError);

    /**
     * Referenzmuster fuer fehlbare Aufrufe: verdoppelt {@code n}.
     *
     * @throws SwiftException wenn {@code n} negativ ist
     */
    public static long doubleOrFail(long n) {
        LongPtr result = new LongPtr();
        LongPtr error = new LongPtr();
        int status = doubleOrFail0(n, result, error);
        checkStatus(status, error.get());
        return result.get();
    }

    /**
     * Loest absichtlich einen Swift-Trap aus (Array-Zugriff ausserhalb der
     * Grenzen). Dient ausschliesslich dazu, das Zusammenspiel mit RoboVMs
     * Signal-Handlern in {@code compiler/vm/core/src/signal.c} zu pruefen.
     * Der Aufruf beendet den Prozess.
     */
    @Bridge(symbol = "rvm_swift_debug_trap")
    public static native void debugTrap();
}
