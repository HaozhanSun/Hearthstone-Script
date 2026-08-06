# Architecture map

The root project is an aggregator. Runtime code is in the `hs-script-app` submodule; the SDK, base, plugin, and template modules are separate submodules assembled by the root Maven reactor.

## Perception and control seams

- `hs-script-app/src/main/java/.../utils/PowerLogUtil.kt` parses `Power.log`.
- `hs-script-app/src/main/java/.../utils/GameUtil.kt` locates and controls Battle.net/Hearthstone processes and windows.
- `hs-script-app/src/main/java/.../dll/CSystemDll.kt` is the JNA boundary for native calls.
- `hs-script-app/src/main/java/.../initializer/DriverInitializer.kt` installs, loads, or releases the Interception driver.
- `hs-script-app/src/main/java/.../utils/ConfigExUtil.kt` selects `MESSAGE`, `EVENT`, or `DRIVE` mouse control and is the safe configuration seam.
- `hs-script-app/src/main/java/.../starter/InjectedAfterStarter.kt` enables native hooks after injection; it must not be activated for a home-screen-only smoke test.

The fork keeps the native DLL layer as an external binary boundary. Initial validation deliberately stays above that boundary: build the JVM distribution, run with `MESSAGE` mode, observe the app and game windows, and stop at the Hearthstone home screen.
