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

import java.io.Closeable;

import org.robovm.rt.bro.NativeObject;

/**
 * Java-Seite eines Swift-Handles.
 *
 * <p>Ein Handle zeigt auf eine {@code RvmBox}-Instanz im Shim, die einen
 * beliebigen Swift-Wert traegt -- Struct, Enum, Klasse oder einen opaken
 * {@code some View}. Java sieht nie ein Swift-Speicherlayout, nur den Zeiger.
 * Damit ist Library Evolution auf der Swift-Seite irrelevant: Apple darf
 * Struct-Layouts jederzeit aendern, ohne dass hier etwas bricht.
 *
 * <h3>Ownership</h3>
 * Swift-ARC und RoboVMs Boehm-GC wissen nichts voneinander. Deshalb gilt:
 * <ul>
 *   <li>Java besitzt die Lebenszeit. {@link #close()} ist der Normalfall.</li>
 *   <li>{@link #finalize()} ist nur das Netz, falls {@code close()} vergessen
 *       wurde -- kein Verlass darauf, wann der GC laeuft.</li>
 *   <li>Swift haelt Java <em>niemals</em> stark. Callback-Kontexte reisen als
 *       Bitmuster, nicht als Referenz. Sonst entstuenden Zyklen ueber die
 *       Sprachgrenze, die keine der beiden Seiten aufloesen kann.</li>
 * </ul>
 *
 * <p>Diese Klasse ist bewusst deutlich schlanker als
 * {@code org.robovm.objc.ObjCObject}: es gibt keine Custom-Klassen, keine
 * Selectors und keine Associated Objects, weil die Swift-Seite nur eine flache
 * C-Fassade ist.
 */
public abstract class SwiftObject extends NativeObject implements Closeable {

    private boolean released;

    protected SwiftObject(long handle) {
        if (handle == 0L) {
            throw new SwiftException("Swift returned a null handle");
        }
        setHandle(handle);
    }

    /**
     * Wirft, wenn der Handle bereits freigegeben wurde. Jede Methode, die den
     * Handle nach Swift reicht, muss das zuerst rufen -- ein Aufruf mit
     * freigegebenem Handle waere ein Use-after-free im Shim.
     */
    protected final long handle() {
        if (released) {
            throw new SwiftException(getClass().getSimpleName() + " has already been released");
        }
        return getHandle();
    }

    /** {@code true}, sobald {@link #close()} gelaufen ist. */
    public final boolean isReleased() {
        return released;
    }

    /**
     * Haengt sich aus allem aus, was Swift an Java-Callbacks haelt.
     * Unterklassen mit registrierten Callbacks muessen das ueberschreiben --
     * sonst ruft Swift nach der Freigabe in einen toten Kontext.
     */
    protected void detachCallbacks() {
    }

    @Override
    public final void close() {
        if (released) {
            return;
        }
        released = true;
        long h = getHandle();
        if (h != 0L) {
            detachCallbacks();
            SwiftRuntime.release(h);
            setHandle(0L);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected final void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
