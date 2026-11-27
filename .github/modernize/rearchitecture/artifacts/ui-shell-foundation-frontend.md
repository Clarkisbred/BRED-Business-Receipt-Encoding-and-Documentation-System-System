# ui-shell-foundation — Frontend deliverables

## Summary

Created the first standalone React/Vite/TypeScript workspace in `receipt-management-system/web-app/`. It uses the public Supabase client with in-memory auth, validates the server-owned active/approved profile, and reads actual dashboard records through existing RLS. No Java API server, local-data shortcut, schema change, MSI change, or Swing replacement was added.

## Deliverables

- [Migration checklist](./ui-shell-foundation-frontend-checklist.md) — staged feature, authorization, parity, local-data, packaging, and release gates.
- [Windows browser-host decision](./ui-shell-foundation-frontend-runtime-decision.md) — WebView2 vs JCEF for the current Java 21/jpackage MSI, with a preliminary JCEF prototype recommendation.
- `receipt-management-system/web-app/` — React/Vite/TypeScript shell, Supabase Auth/read-only dashboard, accessible responsive sidebar, regression tests, and local setup notes.

## Upstream Artifacts Consumed

- none — no dependency artifacts provided; `.github/modernize/rearchitecture/` did not exist before this task.

## Evidence Mapping

- none — no dependency artifacts provided.

## Test Results

- Command: `mvn -B test` (final working tree)
  - Passed: 329
  - Failed: 0
  - Skipped: 0
  - Exit code: 0
- Command: `node -e "const pkg=require('./package.json'); const missing=['build','test'].filter(s=>!pkg.scripts||!pkg.scripts[s]); if(missing.length){console.error('MISSING scripts:',missing.join(', '));process.exit(1)} console.log('scripts OK: build='+pkg.scripts.build+', test='+pkg.scripts.test);"`
  - Passed: required `build` and `test` scripts present
  - Exit code: 0
- Command: `npm test`
  - Passed: 3 tests in 3 files
  - Failed: 0
  - Skipped: 0
  - Exit code: 0
- Command: `npm run build`
  - Passed: TypeScript project check and Vite production build
  - Failed: 0
  - Exit code: 0
- Command: Vite dev-server HTTP probe at `http://127.0.0.1:4173/`
  - Result: HTTP 200; BRED title marker present
- Command: `git diff --check`
  - Exit code: 0; only pre-existing LF/CRLF warnings for `ProjectPanel.java` and `SupplierPanel.java`

### Not verified in this environment

- No approved BRED account credentials were supplied, so a live authenticated Supabase dashboard read and comparison against Swing data were not performed.
- Browser visual/keyboard checks at 390 px and desktop width were not completed: the Playwright browser could not start because its Chrome distribution was absent. The web shell did pass DOM component tests and the local HTTP smoke probe.
- MSI packaging/upgrade testing was not run; packaging identity and build scripts were left unchanged.
- No Java REST API endpoints were added; direct Supabase access is the only implemented web data path. Local database access remains through Swing until a reviewed authenticated bridge exists.
