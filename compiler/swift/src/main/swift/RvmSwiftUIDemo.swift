//
// Referenzmuster fuer einen SwiftUI-Screen, der aus Java gesteuert wird.
//
// Das Muster hat immer drei Teile:
//
//   1. ein @Observable Model, das den Zustand haelt und in Swift lebt,
//   2. eine SwiftUI-View, die es rendert,
//   3. @_cdecl-Entrypoints: Model erzeugen, Felder setzen, Screen bauen.
//
// Der Screen verlaesst die Fassade als type-erased UIViewController.
// UIHostingController<Content> ist generisch und aus ObjC nicht
// instanziierbar -- hier wird er *innerhalb* von Swift gebaut und nur als
// UIViewController* herausgereicht. Damit greift auf der Java-Seite der
// bestehende ObjCObject.Marshaler ohne eine Zeile neuen Code.
//

import Foundation
import UIKit
import SwiftUI
import Observation

// MARK: - Model

@Observable
@MainActor
final class RvmDemoModel {
    var title: String = ""
    var counter: Int64 = 0

    /// Wird bei Button-Tap gefeuert. Java haengt sich hier ein.
    var onTap: ((Int64) -> Void)?
}

// MARK: - View

@MainActor
struct RvmDemoView: View {
    @Bindable var model: RvmDemoModel

    var body: some View {
        VStack(spacing: 24) {
            Text(model.title)
                .font(.largeTitle)
                .multilineTextAlignment(.center)

            Text("\(model.counter)")
                .font(.system(size: 64, weight: .bold, design: .rounded))
                .contentTransition(.numericText())

            Button {
                model.counter += 1
                model.onTap?(model.counter)
            } label: {
                Label("Hochzaehlen", systemImage: "plus.circle.fill")
                    .font(.title2)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .animation(.snappy, value: model.counter)
    }
}

// MARK: - C-Fassade

@_cdecl("rvm_demo_model_create")
public func rvm_demo_model_create(_ cTitle: UnsafePointer<CChar>) -> UnsafeMutableRawPointer {
    let title = String(cString: cTitle)
    let bits: UInt = MainActor.assumeIsolated {
        let model = RvmDemoModel()
        model.title = title
        return UInt(bitPattern: rvmBox(model))
    }
    return UnsafeMutableRawPointer(bitPattern: bits).unsafelyUnwrapped
}

@_cdecl("rvm_demo_model_set_counter")
public func rvm_demo_model_set_counter(_ handle: UnsafeMutableRawPointer, _ value: Int64) -> Int32 {
    rvmOnMain(handle, as: RvmDemoModel.self) { model in
        model.counter = value
        return RvmStatus.ok.rawValue
    } ?? RvmStatus.badHandle.rawValue
}

@_cdecl("rvm_demo_model_get_counter")
public func rvm_demo_model_get_counter(_ handle: UnsafeMutableRawPointer) -> Int64 {
    rvmOnMain(handle, as: RvmDemoModel.self) { $0.counter } ?? -1
}

/// Registriert einen Java-Callback fuer Button-Taps.
///
/// Wichtig fuer die Ownership-Regel: Swift haelt den Java-Kontext nur als
/// `UInt`-Bitmuster, niemals als starke Referenz. Java besitzt die Lebenszeit
/// und muss vor dem Freigeben `rvm_demo_model_clear_on_tap` rufen.
@_cdecl("rvm_demo_model_set_on_tap")
public func rvm_demo_model_set_on_tap(
    _ handle: UnsafeMutableRawPointer,
    _ context: UnsafeMutableRawPointer?,
    _ callback: RvmIntCallback
) -> Int32 {
    let ctx = UInt(bitPattern: context)
    return rvmOnMain(handle, as: RvmDemoModel.self) { model in
        model.onTap = { value in
            callback(UnsafeMutableRawPointer(bitPattern: ctx), value)
        }
        return RvmStatus.ok.rawValue
    } ?? RvmStatus.badHandle.rawValue
}

@_cdecl("rvm_demo_model_clear_on_tap")
public func rvm_demo_model_clear_on_tap(_ handle: UnsafeMutableRawPointer) -> Int32 {
    rvmOnMain(handle, as: RvmDemoModel.self) { model in
        model.onTap = nil
        return RvmStatus.ok.rawValue
    } ?? RvmStatus.badHandle.rawValue
}

/// Baut den SwiftUI-Screen und gibt ihn als retainten UIViewController zurueck.
///
/// Rueckgabe ist ein ObjC-Objekt (+1 retained), kein RvmBox-Handle --
/// auf der Java-Seite uebernimmt `NSObject.NoRetainMarshaler` bzw.
/// ein `@Pointer long`, der an `UIViewController` gemarshallt wird.
@_cdecl("rvm_demo_make_screen")
public func rvm_demo_make_screen(_ modelHandle: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer? {
    let bits = rvmOnMain(modelHandle, as: RvmDemoModel.self) { model -> UInt in
        let controller: UIViewController = UIHostingController(rootView: RvmDemoView(model: model))
        return UInt(bitPattern: Unmanaged.passRetained(controller).toOpaque())
    }
    return bits.flatMap { UnsafeMutableRawPointer(bitPattern: $0) }
}
