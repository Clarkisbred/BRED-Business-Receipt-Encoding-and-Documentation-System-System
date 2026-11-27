# BRED UI migration checklist

## Guardrails

- [x] Establish a separate React/Vite/TypeScript workspace; keep the existing Java/Swing application runnable.
- [x] Preserve the Java service and repository layers, Supabase configuration, local-data classes, and database migrations.
- [x] Preserve authentication boundaries: React calls the Java loopback API; Java AuthApi and existing repositories remain authoritative; database RLS remains in effect for Supabase mode.
- [x] Keep the first web slice read-only. The loopback API binds to `127.0.0.1`, requires a per-launch bearer token and exact `Origin`/`Host`, and sends no permissive CORS headers.
- [x] Add a JCEF message-router bridge that supplies the bootstrap only to the same-origin top-level root document; do not expose the token through an HTTP endpoint, URL, static HTML, environment variable, or browser storage. Native startup still needs runtime verification.
- [x] Keep MSI identity and update configuration untouched until a packaged host has passed upgrade verification.
- [x] Derive the sidebar width and navigation grouping from `UiConstants.SIDEBAR_WIDTH` and `Shell.navigationGroup`; use the user-provided 250x709 sidebar screenshot as visual evidence.
- [x] Fix the Swing sidebar's internal horizontal offset and add measured positioning assertions while preserving role filtering and scroll behavior.

## Current inventory

- **Desktop/runtime:** Java 21 Maven application; the UI is Swing and calls Java services, which call repository interfaces. Supabase repositories use the existing authenticated `RestClient`.
- **Authentication/authorization:** Supabase Auth email/password; profile role and account state are read from `profiles`. RLS scopes data to the active company (Creator has global access); important writes use existing RPCs/Edge Functions and server-side checks.
- **Data:** Supabase/PostgREST is the shared store. Local database/auth/attachment implementations remain in the Java source for compatibility and tests. The React slice does not add a local-data transport.
- **Existing web content:** `web/reset-password.html` and an animated product asset; no screenshot/mockup was present. Existing icon/logo assets are brand assets, not layout references.
- **Local API:** `LocalApiServer` is a JDK `HttpServer` bound to `127.0.0.1`, serving the packaged React assets and authenticated session/dashboard routes from one origin. API requests validate the loopback peer, exact Host, per-launch token, and exact Origin or Chromium's same-origin fetch metadata when GET omits Origin; no CORS allow-origin response is sent.
- **Browser host:** `ReactDesktopPanel` lazily initializes JCEF only on Windows AMD64, serves the bundled React shell, limits main-frame navigation, and provides a per-query native bootstrap bridge for the trusted root page. `MainWindow` exposes it as a signed-out-only preview and preserves the Swing interface as the default. Runtime startup has not yet been visually verified.
- **Packaging:** `scripts/build-windows-msi.ps1` uses Java 21 `jpackage`, produces a per-machine `BRED` MSI, uses upgrade code `7B6F2C1E-4A8D-4F3B-9C1A-5E2D8F0A1B34`, and expects a directory chooser. Maven project version is `1.3.0`; the checked-in release snapshot is `BRED-Setup-1.0.8.msi`. The existing script warns that upgrade installation must be tested separately.
- **Working-tree preservation:** before implementation, `git status` showed uncommitted changes in `Shell.java`, its tests, theme and feature panels. Those files were not edited by this task.

## Phases and acceptance gates

### 0 — Baseline and safeguards

- [x] Capture the working-tree state; do not reset, stage, or overwrite existing edits.
- [x] Run the existing Maven test suite before changes (329 tests, 0 failures).
- [x] Record source modules, RLS/auth, local mode, MSI identity, updater, and available visual assets.
- [ ] Keep baseline test/build results available for each later feature parity comparison.

### 1 — Web shell and first real data read (started)

- [x] Add a standalone React/Vite/TypeScript workspace and local run instructions.
- [x] Keep Supabase credentials out of browser configuration and remove direct Supabase browser authentication/data access.
- [x] Add the React desktop-bridge API client and typed responses for Java sign-in, session, sign-out, and dashboard operations.
- [x] Keep the Dashboard read-only and have Java calculate totals through existing services/repositories.
- [x] Provide loading, empty, retry/error, keyboard focus, and phone-width navigation states; no fake dashboard data is shipped.
- [x] Add focused frontend tests for receipt arithmetic, loopback bootstrap validation, and sidebar role visibility.
- [ ] Verify approved-account sign-in and dashboard values against the existing Swing Dashboard through the embedded renderer.
- [ ] Complete local-mode startup and verify local-auth/dashboard parity. The current `Main` startup still creates a configured Supabase context only.

### 2 — Java loopback API (started)

- [x] Implement `POST`, `GET`, and `DELETE /api/v1/session` using the existing Java `AuthApi`.
- [x] Implement `GET /api/v1/dashboard` using the existing report and project services; no frontend-supplied company or role is trusted.
- [x] Validate loopback peer, exact origin/host, bearer token, request size, JSON content type, and input bounds; return consistent errors and `no-store` responses.
- [x] Start the API from `Main` when an `AppContext` is configured and close it on JVM shutdown.
- [x] Add HTTP-level tests for sign-in/session/dashboard, token/origin rejection, error shapes, and password-array clearing.
- [x] Connect `rendererBootstrap()` to JCEF only through the native trusted-host bridge; end-to-end rendering and runtime validation remain pending.

### 3 — Feature inventory and contracts (not started)

- [ ] Capture each existing Swing feature, state, validation rule, permission, error path, import/export behavior, and attachment workflow as a behavior baseline.
- [ ] Map every screen to its existing Java service/repository and Supabase migration/RPC/Edge Function; do not duplicate money, role, or authorization rules in a frontend-only implementation.
- [ ] Agree on service/API contracts before implementing each mutating module.
- [ ] Cover role-specific visibility and backend denial cases for Encoder, Admin, and Creator; UI filtering is never the security boundary.

### 4 — Incremental modules (not started)

- [ ] Receipts: search/filter/detail, typed validation, service-compatible create/update/delete, line items, VAT/discount/charges, duplicate checks, attachments, and imports.
- [ ] Suppliers and Projects: CRUD behavior with same-company scoping and role restrictions.
- [ ] Reports/Dashboard: numeric parity, export formats, and construction figures/alerts.
- [ ] Messages, Ducky/AI, Construction, map, Users, Activity Log, Companies, History, and Settings: migrate only after their service contracts and per-role tests are approved.
- [ ] Keep unimplemented modules clearly marked and usable from Swing; never substitute fixtures or mock data in the application.

### 5 — Authorization, data, and behavior verification (not started)

- [ ] Test approved, pending, rejected, inactive, wrong-company, Admin, Encoder, and Creator flows against the same deployed RLS/RPC policies.
- [ ] Compare calculated totals, filtered lists, report exports, and mutation results with the existing Java service behavior.
- [ ] Test network loss, token refresh/expiry, no data, large datasets, long names, and narrow layouts.
- [ ] Verify every write remains on the existing RPC/Edge Function path or a separately reviewed Java service API; never expose the Supabase service-role key.
- [ ] Run the complete Java suite and the web build/tests after each shared contract change.

### 6 — Windows desktop host and release (not started)

- [ ] Launch and visually verify the selected JCEF host in a Windows Java/Swing window; the source prototype does not yet prove native startup.
- [x] Build the React bundle during Maven and package the JCEF Windows AMD64 native archive and React assets in the shaded JAR. The JAR measured 208,816,698 bytes (including a 171,413,552-byte compressed native archive); app-image/MSI size and startup time are not measured.
- [x] Verify Maven package/tests (335 Java tests), frontend tests (6), standalone frontend build, and the MSI validation-only preflight. The preflight did not invoke `jpackage` or produce an MSI.
- [ ] Build a clean Java 21 `jpackage` app image; confirm native extraction/loading and browser behavior from the installed image.
- [ ] Preserve product name `BRED`, MSI per-machine scope, existing upgrade code, install directory behavior, signing/update metadata, and the GitHub release artifact/filename contract.
- [ ] Install the current MSI and proposed MSI on isolated test machines; verify upgrade, repair, uninstall, data retention, and reinstall.
- [ ] Compare shell/API parity and accessibility with Swing, including all roles and high-DPI/mobile breakpoints.
- [ ] Keep Swing as the supported fallback until the MSI upgrade path, existing data, auth, and all migrated behaviors pass the release gates.

## Current stop boundary

This increment adds the JCEF source host and trusted bootstrap bridge, serves the packaged read-only React shell from the loopback API, and adds a signed-out preview entry while keeping Swing primary. The build bundles JCEF and React assets in the shaded JAR, but an actual Windows native launch, screenshot, app-image/MSI, upgrade path, and end-to-end authentication/dashboard comparison remain unverified. Remaining modules, local-mode API startup, SQL/RLS, MSI identity, and updater behavior are unchanged.
