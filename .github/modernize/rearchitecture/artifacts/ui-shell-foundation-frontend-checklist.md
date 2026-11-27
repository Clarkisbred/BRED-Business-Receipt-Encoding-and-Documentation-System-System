# BRED UI migration checklist

## Guardrails

- [x] Establish a separate React/Vite/TypeScript workspace; keep the existing Java/Swing application runnable.
- [x] Preserve the Java service and repository layers, Supabase configuration, local-data classes, and database migrations.
- [x] Preserve authentication boundaries: browser uses only the public Supabase anon/publishable key; active/approved profile validation is required; database RLS remains authoritative.
- [x] Keep the first web slice read-only. Do not expose a generic or unauthenticated localhost API to the browser.
- [x] Keep MSI identity and update configuration untouched until a packaged host has passed upgrade verification.
- [x] Derive the new sidebar width and navigation grouping from `UiConstants.SIDEBAR_WIDTH` and `Shell.navigationGroup`; no screenshot assets were provided.
- [x] Add an automated regression test for source-derived sidebar width/role filtering; preserve the pre-existing Swing viewport regression test.

## Current inventory

- **Desktop/runtime:** Java 21 Maven application; the UI is Swing and calls Java services, which call repository interfaces. Supabase repositories use the existing authenticated `RestClient`.
- **Authentication/authorization:** Supabase Auth email/password; profile role and account state are read from `profiles`. RLS scopes data to the active company (Creator has global access); important writes use existing RPCs/Edge Functions and server-side checks.
- **Data:** Supabase/PostgREST is the shared store. Local database/auth/attachment implementations remain in the Java source for compatibility and tests. The React slice does not add a local-data transport.
- **Existing web content:** `web/reset-password.html` and an animated product asset; no screenshot/mockup was present. Existing icon/logo assets are brand assets, not layout references.
- **Existing API surface:** no Java REST controller or HTTP API route was found. Java services are in-process and require the current authenticated context.
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
- [x] Add environment-only Supabase configuration; reject privileged service-role/secret keys.
- [x] Sign in through existing Supabase Auth, load the server-owned profile, reject missing/pending/rejected/inactive accounts, and retain tokens in memory only.
- [x] Add read-only Dashboard integration to real `projects`, `receipts`, and `receipt_items` data through the authenticated Supabase client; rely on database RLS and reproduce Java receipt rounding.
- [x] Provide loading, empty, retry/error, keyboard focus, and phone-width navigation states; no fake dashboard data is shipped.
- [x] Add focused frontend tests for receipt arithmetic and sidebar source width/role visibility.
- [ ] Run against an approved BRED account and verify company-scoped dashboard results against the Swing Dashboard. No account credentials were supplied in this task, so this live-data gate remains open.
- [ ] Add a secure local-mode bridge only after its trust boundary, authentication, origin restrictions, session handling, and runtime tests are designed. Until then, local data remains available through Swing.

### 2 — Feature inventory and contracts (not started)

- [ ] Capture each existing Swing feature, state, validation rule, permission, error path, import/export behavior, and attachment workflow as a behavior baseline.
- [ ] Map every screen to its existing Java service/repository and Supabase migration/RPC/Edge Function; do not duplicate money, role, or authorization rules in a frontend-only implementation.
- [ ] Agree on service/API contracts before implementing each mutating module.
- [ ] Cover role-specific visibility and backend denial cases for Encoder, Admin, and Creator; UI filtering is never the security boundary.

### 3 — Incremental modules (not started)

- [ ] Receipts: search/filter/detail, typed validation, service-compatible create/update/delete, line items, VAT/discount/charges, duplicate checks, attachments, and imports.
- [ ] Suppliers and Projects: CRUD behavior with same-company scoping and role restrictions.
- [ ] Reports/Dashboard: numeric parity, export formats, and construction figures/alerts.
- [ ] Messages, Ducky/AI, Construction, map, Users, Activity Log, Companies, History, and Settings: migrate only after their service contracts and per-role tests are approved.
- [ ] Keep unimplemented modules clearly marked and usable from Swing; never substitute fixtures or mock data in the application.

### 4 — Authorization, data, and behavior verification (not started)

- [ ] Test approved, pending, rejected, inactive, wrong-company, Admin, Encoder, and Creator flows against the same deployed RLS/RPC policies.
- [ ] Compare calculated totals, filtered lists, report exports, and mutation results with the existing Java service behavior.
- [ ] Test network loss, token refresh/expiry, no data, large datasets, long names, and narrow layouts.
- [ ] Verify every write remains on the existing RPC/Edge Function path or a separately reviewed Java service API; never expose the Supabase service-role key.
- [ ] Run the complete Java suite and the web build/tests after each shared contract change.

### 5 — Windows desktop host and release (not started)

- [ ] Prototype the selected browser host in a Windows Java/Swing window without changing the product launcher.
- [ ] Package its runtime/resources in a clean Java 21 `jpackage` app image; measure installer size, startup, native DLL loading, and update/patch cadence.
- [ ] Preserve product name `BRED`, MSI per-machine scope, existing upgrade code, install directory behavior, signing/update metadata, and the GitHub release artifact/filename contract.
- [ ] Install the current MSI and proposed MSI on isolated test machines; verify upgrade, repair, uninstall, data retention, and reinstall.
- [ ] Compare shell/API parity and accessibility with Swing, including all roles and high-DPI/mobile breakpoints.
- [ ] Keep Swing as the supported fallback until the MSI upgrade path, existing data, auth, and all migrated behaviors pass the release gates.

## Stop boundary for this increment

This change stops at the shell, authenticated read-only dashboard, checklist, browser-host decision, and focused sidebar regression coverage. It does not migrate the rest of the modules, add a Java HTTP server, change SQL/RLS, alter the MSI, or remove Swing.
