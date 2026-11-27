# Windows MSI browser-host comparison

**Decision date:** 2026-11-27  
**Scope:** BRED's current Java 21 Swing application and the existing per-machine `jpackage` MSI.

## Deployment facts

- The application is a Java 21 Swing desktop program, not a web server. The UI calls in-process Java services and repository interfaces.
- The Windows installer is built by `scripts/build-windows-msi.ps1` with `jpackage --type msi`. It keeps product name `BRED`, a fixed upgrade UUID (`7B6F2C1E-4A8D-4F3B-9C1A-5E2D8F0A1B34`), a per-machine install, and a directory chooser.
- The script requires a Java 21 `jpackage`, a shaded Java JAR, and WiX. The checked-in `BRED-Setup-1.0.8.msi` is 78,467,996 bytes; it is a release snapshot, not a measured React/browser build.
- The React-enabled shaded JAR currently measures 208,816,698 bytes, including a 171,413,552-byte compressed Windows AMD64 JCEF native archive. This is a JAR measurement, not an app-image or MSI measurement.
- The current script explicitly says upgrade installation has not been tested. The existing GitHub-release updater and MSI identity must not be changed as part of the frontend scaffold.

## Comparison for this application

| Concern | Microsoft WebView2 | JCEF (Java Chromium Embedded Framework) |
|---|---|---|
| Java/Swing integration | Windows WebView2 is a native Windows API. This repository has no Java WebView2 binding or host. A Java-to-COM integration (or a separate native/.NET host and bridge) would need to be designed, implemented, and maintained. The existing JNA dependency alone is not a ready-made WebView2 integration. | JCEF is specifically a Java wrapper for CEF and can be embedded in Java applications, making it the more direct fit for a Swing host. The Java-CEF project identifies its focus as embedding Chromium in Java applications. |
| Runtime distribution | Requires the WebView2 Runtime. Microsoft's recommended Evergreen mode shares the runtime across applications and updates it automatically; Windows 11 includes it, but the app should still handle machines where it is missing. Microsoft documents an approximately 2 MB online bootstrapper and an offline standalone installer. Runtime provisioning/repair must be coordinated with this MSI's per-machine setup. | Ship a matching Windows JCEF/CEF native runtime with the application image; no separate system WebView runtime prerequisite. The exact binary footprint and MSI delta have not been built or measured for BRED. |
| Updating/security maintenance | Evergreen reduces app-bundled browser bytes and gets browser security updates independently. The app must tolerate runtime upgrades and detect/obtain the runtime when absent. | Browser engine/native runtime updates must be selected, tested, and released with BRED. This adds ongoing Chromium/CEF/JCEF patch and packaging work. |
| MSI identity and update flow | Could keep the existing `jpackage` output, but a custom runtime bootstrapper or installer prerequisite is needed; test elevation, offline installation, repair and updates. A Fixed Version runtime increases the app package and adds servicing responsibility. | The React assets and native runtime must be carried in the existing Java app image/MSI and verified through the existing GitHub MSI-update flow. Preserve the current MSI UpgradeCode and app name. |
| Risk in this repository | Lower browser-runtime footprint, but highest host-bridge/installer integration uncertainty until a supported Java binding is selected and proven. | Lower Java-host integration uncertainty, but higher package-size and browser-servicing burden. The JCEF project describes itself as a Java wrapper and is still a work in progress; pin a compatible binary build and plan its updates. |

## Recommendation

**Continue with the JCEF prototype for the first embedded React host**, because the existing product is Java/Swing and there is no WebView2 Java integration in the repository. This is still a prototype recommendation, not approval to replace Swing or change MSI identity. JCEF avoids inventing a COM bridge inside the current Java launcher; keep the Swing interface as the primary, supported path until native startup, packaging, parity, and upgrade checks pass.

Before final adoption:

1. The prototype pins `me.friwi:jcefmaven:152.0.6`, CEF `152.0.6`, Chromium `152.0.7977.83`, and the matching `jcef-natives-windows-amd64` archive. The Maven release page currently marks Windows AMD64 as untested; a successful compile/package does not establish runtime compatibility.
2. Measure the generated app image/MSI size and startup time against the 78,467,996-byte release snapshot; confirm native extraction and loading after install, not only from the development classpath.
3. Scan and record bundled Chromium/CEF/JCEF licenses and security-update ownership.
4. Verify the React asset origin, navigation allow-list, browser-to-Java bridge surface, and separation of public Supabase credentials from privileged keys.
5. Test install/upgrade/uninstall on an isolated machine while retaining `BRED`, the existing per-machine scope, the same MSI upgrade code, update filename/release metadata, and Swing fallback.

Reconsider WebView2 if a supported Java binding or an approved native host is introduced and can be packaged without an unsafe local HTTP bridge. Its Evergreen shared runtime is attractive for footprint and servicing, but BRED must account for missing-runtime deployment and the lack of a Java host API.

## Current implementation boundary

- Maven now stages and builds `web-app` with pinned Node 24.21.0 during the build, writing directly to `target/classes/web-app` so stale Vite bundles cannot remain in the shaded JAR. Node is build-time only; the installed application does not need Node. The npm dependency cache is isolated under the build user's `.bred-build` directory so Maven does not run `npm ci` over a potentially active source `node_modules`.
- `LocalApiServer` serves the packaged static bundle and the existing Java API from the same loopback origin. Static content has a restrictive CSP and no bootstrap secret. API requests require the loopback host, per-launch bearer token, and exact Origin or Chromium's `Sec-Fetch-Site: same-origin` signal for same-origin GET fetches.
- `ReactDesktopPanel` initializes JCEF lazily from a native archive bundled in the JAR. It disables fallback downloads, extracts runtime files under `%LOCALAPPDATA%\BRED\jcef`, rejects non-Windows/non-AMD64 hosts, blocks non-root/cross-origin top-level navigation, and exposes the token only through a per-query native bridge for the trusted top-level root document.
- In the existing `MainWindow`, `View > React dashboard preview` opens the React shell only when signed out; `Return to Swing interface` serializes sign-out before returning to the legacy login screen. Swing remains the default and supported interface. The React feature set is still limited to sign-in and a read-only dashboard.
- Maven unit tests (335), web tests (6), and the standalone web build pass. MSI validation-only preflight passes without invoking `jpackage`; the shaded JAR contains one current JavaScript bundle, CSS, JCEF classes, and the native archive. The actual JCEF window/native startup, browser request headers, React preview screenshots, clean app-image/MSI, installation, upgrade, and updater flow have not yet been runtime-verified. No MSI identity or updater changes have been made.

## Sources

- Microsoft Learn, [Distribute your app and the WebView2 Runtime](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution) — Evergreen/Fixed Version distribution, runtime presence checks, and bootstrapper guidance (reviewed 2026-11-27).
- Microsoft Learn, [Evergreen vs. fixed version of the WebView2 Runtime](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/evergreen-vs-fixed-version) — shared automatic updates versus app-managed fixed runtime.
- Chromium Embedded Framework, [java-cef](https://github.com/chromiumembedded/java-cef) — Java wrapper, supported embedding use case, and project status.
- JCEF Maven, [152.0.6 release](https://github.com/jcefmaven/jcefmaven/releases/tag/152.0.6) — artifact/native coordinates, Chromium build, and platform test status (reviewed 2026-11-27).
- Oracle Java 21, [jpackage Packaging Overview](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html) — target-platform packaging and Windows MSI/WiX prerequisites.
