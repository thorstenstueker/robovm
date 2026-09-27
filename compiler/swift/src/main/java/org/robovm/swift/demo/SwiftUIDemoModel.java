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
package org.robovm.swift.demo;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.uikit.UIViewController;
import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.StringMarshalers;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Callback;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;
import org.robovm.rt.bro.ptr.FunctionPtr;
import org.robovm.swift.SwiftObject;
import org.robovm.swift.SwiftRuntime;

/**
 * Referenzmuster fuer einen aus Java gesteuerten SwiftUI-Screen.
 *
 * <p>Der Zustand lebt in Swift (ein {@code @Observable}-Model), Java setzt
 * Felder ueber flache Setter und bekommt Aktionen ueber einen Callback zurueck.
 * {@link #createViewController()} liefert einen ganz normalen
 * {@link UIViewController}, den man mit den vorhandenen cocoatouch-Bindings
 * pushen, praesentieren oder einbetten kann -- die SwiftUI-Generik bleibt
 * vollstaendig in Swift.
 *
 * <p>Benutzung:
 * <pre>
 * SwiftUIDemoModel model = new SwiftUIDemoModel("Hallo aus Java");
 * model.setOnTap(new SwiftUIDemoModel.TapListener() {
 *     public void onTap(long counter) { System.out.println("tap " + counter); }
 * });
 * navigationController.pushViewController(model.createViewController(), true);
 * // ... spaeter, wenn der Screen weg ist:
 * model.close();
 * </pre>
 */
@Library(Library.INTERNAL)
public final class SwiftUIDemoModel extends SwiftObject {

    static { Bro.bind(SwiftUIDemoModel.class); }

    /** Wird bei jedem Button-Tap in SwiftUI gefeuert. */
    public interface TapListener {
        void onTap(long counter);
    }

    /*
     * Swift darf keine starke Referenz auf ein Java-Objekt halten -- sonst
     * entsteht ein Zyklus ueber die Sprachgrenze, den weder ARC noch der
     * Boehm-GC aufloest. Deshalb reist als Kontext nur eine long-Id, und die
     * eigentliche Referenz liegt hier in einer Java-Map, die wir in
     * detachCallbacks() wieder raeumen.
     */
    private static final Map<Long, TapListener> LISTENERS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final Method CB_ON_TAP;

    static {
        try {
            CB_ON_TAP = SwiftUIDemoModel.class.getDeclaredMethod(
                    "cbOnTap", long.class, long.class);
        } catch (NoSuchMethodException e) {
            throw new Error(e);
        }
    }

    private final long listenerId;

    public SwiftUIDemoModel(String title) {
        super(create0(title));
        this.listenerId = NEXT_ID.getAndIncrement();
    }

    // -- Bridges ------------------------------------------------------------

    /*
     * Die @Marshaler-Annotation muss hier voll qualifiziert stehen: diese
     * Klasse erbt ueber SwiftObject von NativeObject, und dessen innere Klasse
     * NativeObject.Marshaler ueberschattet einen einfachen Import. Der
     * generierte cocoatouch-Code macht es aus demselben Grund ueberall so.
     */
    @Bridge(symbol = "rvm_demo_model_create")
    private static native @Pointer long create0(
            @org.robovm.rt.bro.annotation.Marshaler(StringMarshalers.AsUtf8ZMarshaler.class) String title);

    @Bridge(symbol = "rvm_demo_model_set_counter")
    private static native int setCounter0(@Pointer long handle, long value);

    @Bridge(symbol = "rvm_demo_model_get_counter")
    private static native long getCounter0(@Pointer long handle);

    @Bridge(symbol = "rvm_demo_model_set_on_tap")
    private static native int setOnTap0(@Pointer long handle, @Pointer long context, FunctionPtr callback);

    @Bridge(symbol = "rvm_demo_model_clear_on_tap")
    private static native int clearOnTap0(@Pointer long handle);

    /*
     * Swift liefert den Controller +1 retained. NoRetainMarshaler verhindert,
     * dass RoboVM beim Marshalling ein zweites retain draufsetzt -- sonst
     * wuerde der Screen nie deallokiert.
     */
    @Bridge(symbol = "rvm_demo_make_screen")
    private static native @org.robovm.rt.bro.annotation.Marshaler(NSObject.NoRetainMarshaler.class) UIViewController makeScreen0(
            @Pointer long handle);

    // -- Callback aus Swift -------------------------------------------------

    @Callback
    private static void cbOnTap(@Pointer long context, long counter) {
        TapListener listener = LISTENERS.get(context);
        if (listener != null) {
            listener.onTap(counter);
        }
    }

    // -- API ----------------------------------------------------------------

    /** Setzt den Zaehler im SwiftUI-Model. */
    public void setCounter(long value) {
        SwiftRuntime.checkStatus(setCounter0(handle(), value), 0L);
    }

    /** Liest den aktuellen Zaehlerstand aus dem SwiftUI-Model. */
    public long getCounter() {
        return getCounter0(handle());
    }

    /**
     * Registriert einen Listener fuer Button-Taps. {@code null} haengt einen
     * bestehenden Listener wieder aus.
     */
    public void setOnTap(TapListener listener) {
        if (listener == null) {
            LISTENERS.remove(listenerId);
            SwiftRuntime.checkStatus(clearOnTap0(handle()), 0L);
            return;
        }
        LISTENERS.put(listenerId, listener);
        SwiftRuntime.checkStatus(
                setOnTap0(handle(), listenerId, new FunctionPtr(CB_ON_TAP)), 0L);
    }

    /**
     * Baut den SwiftUI-Screen. Mehrfachaufrufe liefern jeweils einen neuen
     * Controller auf demselben Model.
     */
    public UIViewController createViewController() {
        return makeScreen0(handle());
    }

    @Override
    protected void detachCallbacks() {
        LISTENERS.remove(listenerId);
        clearOnTap0(getHandle());
    }
}
