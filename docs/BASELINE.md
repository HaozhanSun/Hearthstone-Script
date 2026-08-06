# Baseline run

Build baseline passed on 2026-08-06 with Java 25. The assembled JVM distribution contains the expected launcher scripts, native libraries, `hs-script.exe`, and `hs-script_v4.16.3-GA.jar`.

The safe launch baseline uses the JVM jar directly from the extracted distribution. This avoids the bundled `hs-script.bat` elevation wrapper while validating the application UI as the current user.

Before starting any automation, verify:

- `mouse.MOUSE_CONTROL_MODE` is `MESSAGE` (non-driver mode; this is the application default).
- Interception is not installed or loaded.
- Hearthstone and Battle.net are already logged in and can be observed without entering credentials.
- The application is only taken to the Hearthstone home screen; no automated match is started.

Evidence and the exact launch outcome will be recorded after the first clean UI run.
