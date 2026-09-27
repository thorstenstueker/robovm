//
// RvmSwiftBridge -- flache C-Fassade vor Swift-only Frameworks.
//
// Regeln fuer alles, was hier drin landet:
//
//  * Nach aussen nur @_cdecl-Funktionen mit C-darstellbaren Signaturen.
//    Keine Generics, keine opaken Typen, kein throws ueber die Grenze.
//  * Jeder Swift-Wert verlaesst die Fassade als opaker Handle (siehe RvmBox).
//    Java sieht nie ein Swift-Layout -- damit ist Library Evolution egal.
//  * Keine Force-Unwraps, kein fatalError, keine Praeconditions auf
//    Java-Eingaben. Ein Swift-Trap ist aus Java heraus nicht diagnostizierbar
//    (siehe rvm_debug_trap und den zugehoerigen Test).
//  * Alles, was SwiftUI beruehrt, laeuft auf dem MainActor.
//

import Foundation
import UIKit
import SwiftUI

// MARK: - Handles

/// Universeller Container: boxt jeden Swift-Wert -- Struct, Enum, Klasse,
/// opaken `some View` -- in eine Klasseninstanz, deren Adresse als `void*`
/// nach Java geht. Lebenszeit haengt an ARC via `Unmanaged`.
public final class RvmBox {
    let value: Any
    init(_ value: Any) { self.value = value; rvmLiveBoxes.inc() }
    deinit { rvmLiveBoxes.dec() }
}

@inline(__always)
func rvmBox(_ value: Any) -> UnsafeMutableRawPointer {
    Unmanaged.passRetained(RvmBox(value)).toOpaque()
}

@inline(__always)
func rvmUnbox<T>(_ handle: UnsafeMutableRawPointer, as _: T.Type) -> T? {
    Unmanaged<RvmBox>.fromOpaque(handle).takeUnretainedValue().value as? T
}

/// Gibt einen Handle frei. Java ruft das aus `SwiftObject.close()` bzw.
/// aus dem Finalizer.
@_cdecl("rvm_swift_release")
public func rvm_swift_release(_ handle: UnsafeMutableRawPointer) {
    Unmanaged<RvmBox>.fromOpaque(handle).release()
}

/// Zusaetzliche starke Referenz auf einen bestehenden Handle.
@_cdecl("rvm_swift_retain")
public func rvm_swift_retain(_ handle: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer {
    _ = Unmanaged<RvmBox>.fromOpaque(handle).retain()
    return handle
}

final class RvmCounter: @unchecked Sendable {
    private var n: Int64 = 0
    private let lock = NSLock()
    func inc() { lock.lock(); n += 1; lock.unlock() }
    func dec() { lock.lock(); n -= 1; lock.unlock() }
    var current: Int64 { lock.lock(); defer { lock.unlock() }; return n }
}

/// Anzahl lebender Boxen -- Grundlage der Leak-Tests auf der Java-Seite.
let rvmLiveBoxes = RvmCounter()

@_cdecl("rvm_swift_live_handle_count")
public func rvm_swift_live_handle_count() -> Int64 {
    rvmLiveBoxes.current
}

// MARK: - Strings

/// Gibt einen mit `strdup` allokierten C-String zurueck. Java muss ihn mit
/// `rvm_swift_free_string` freigeben.
@inline(__always)
func rvmCString(_ s: String) -> UnsafeMutablePointer<CChar>? {
    s.withCString { strdup($0) }
}

@_cdecl("rvm_swift_free_string")
public func rvm_swift_free_string(_ p: UnsafeMutablePointer<CChar>?) {
    free(p)
}

// MARK: - Fehler

/// Status-Codes fuer fehlbare Entrypoints.
public enum RvmStatus: Int32 {
    case ok = 0
    case error = -1
    case badHandle = -2
}

enum RvmBridgeError: Error, CustomStringConvertible {
    case invalidArgument(String)

    var description: String {
        switch self {
        case .invalidArgument(let detail): return "invalid argument: \(detail)"
        }
    }
}

/// Beschreibung eines geboxten `Error` als C-String.
@_cdecl("rvm_swift_error_description")
public func rvm_swift_error_description(_ handle: UnsafeMutableRawPointer) -> UnsafeMutablePointer<CChar>? {
    guard let error = rvmUnbox(handle, as: Error.self) else { return nil }
    if let localized = error as? LocalizedError, let text = localized.errorDescription {
        return rvmCString(text)
    }
    return rvmCString("\(error)")
}

// MARK: - Callbacks nach Java

/// C-Funktionszeiger, wie ihn RoboVMs `@Callback` liefert.
/// Erster Parameter ist der Java-seitige Kontext, den Java bei der
/// Registrierung mitgibt.
///
/// `@Sendable` ist noetig, damit die Zeiger im Swift-6-Sprachmodus ueber
/// Aktor-Grenzen gereicht werden duerfen. Die Zusicherung traegt die
/// Java-Seite: ein @Callback-Impl ist reentrant und threadsicher aufrufbar.
public typealias RvmVoidCallback = @convention(c) @Sendable (UnsafeMutableRawPointer?) -> Void
public typealias RvmIntCallback  = @convention(c) @Sendable (UnsafeMutableRawPointer?, Int64) -> Void

// MARK: - MainActor-Hop

/// Fuehrt `body` mit dem entboxten Wert auf dem MainActor aus.
///
/// Zeiger sind nicht `Sendable`, duerfen also nicht in eine Aktor-Closure
/// hinein- oder herausgereicht werden. Deshalb reist der Handle als
/// `UInt`-Bitmuster durch die Grenze und wird erst drinnen rekonstruiert.
/// Ohne diesen Umweg sind es im Swift-6-Sprachmodus Fehler.
///
/// Gibt `nil` zurueck, wenn der Handle nicht auf einen Wert vom Typ `T` zeigt
/// -- der Aufrufer uebersetzt das in `RvmStatus.badHandle`.
@inline(__always)
func rvmOnMain<T, R: Sendable>(
    _ handle: UnsafeMutableRawPointer,
    as _: T.Type,
    _ body: @MainActor @Sendable (T) -> R
) -> R? {
    let bits = UInt(bitPattern: handle)
    return MainActor.assumeIsolated {
        guard let pointer = UnsafeMutableRawPointer(bitPattern: bits),
              let value = rvmUnbox(pointer, as: T.self) else { return nil }
        return body(value)
    }
}

// MARK: - Diagnose / Durchstich

/// Echo -- prueft String-Marshalling in beide Richtungen.
@_cdecl("rvm_swift_echo")
public func rvm_swift_echo(_ input: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    rvmCString("swift:" + String(cString: input))
}

/// Version der Swift-Runtime, gegen die gebaut wurde.
@_cdecl("rvm_swift_version")
public func rvm_swift_version() -> UnsafeMutablePointer<CChar>? {
#if compiler(>=6.3)
    rvmCString("6.3+")
#else
    rvmCString("<6.3")
#endif
}

/// Fehlbarer Entrypoint -- Referenzmuster fuer alle weiteren.
/// Liefert bei Erfolg `n * 2`, sonst `RvmStatus.error` und setzt `outError`.
@_cdecl("rvm_swift_double_or_fail")
public func rvm_swift_double_or_fail(
    _ n: Int64,
    _ outResult: UnsafeMutablePointer<Int64>,
    _ outError: UnsafeMutablePointer<UnsafeMutableRawPointer?>
) -> Int32 {
    guard n >= 0 else {
        outError.pointee = rvmBox(RvmBridgeError.invalidArgument("n muss >= 0 sein, war \(n)"))
        return RvmStatus.error.rawValue
    }
    outResult.pointee = n &* 2
    return RvmStatus.ok.rawValue
}

/// Ruft `callback` asynchron auf dem Main-Thread auf. Prueft die
/// Swift -> Java Callback-Strecke inklusive Thread-Anbindung an den GC.
@_cdecl("rvm_swift_fire_on_main")
public func rvm_swift_fire_on_main(
    _ context: UnsafeMutableRawPointer?,
    _ value: Int64,
    _ callback: RvmIntCallback
) {
    let ctx = UInt(bitPattern: context)
    DispatchQueue.main.async {
        callback(UnsafeMutableRawPointer(bitPattern: ctx), value)
    }
}

/// Loest absichtlich einen Swift-Trap aus. Dient ausschliesslich dazu, das
/// Zusammenspiel mit RoboVMs Signal-Handlern (compiler/vm/core/src/signal.c)
/// zu pruefen. Nicht aus Produktivcode aufrufen.
@_cdecl("rvm_swift_debug_trap")
public func rvm_swift_debug_trap() {
    let empty: [Int] = []
    // Absichtlicher Out-of-bounds-Zugriff -> Swift-Trap (brk).
    print(empty[1])
}
