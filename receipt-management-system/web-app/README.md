# BRED React desktop shell

This is the first staged React/Vite/TypeScript shell. The existing Swing application remains the default and supported interface; the React shell is available as a preview during the migration.

## Build and preview

Run `npm ci`, then `npm run dev` for frontend development or `npm run build` for a production bundle. The standalone Vite page can render without credentials, but sign-in and data calls require the embedded desktop host.

On Windows AMD64, the Java application starts the loopback API on `127.0.0.1` and provides a **View → React dashboard preview** entry while signed out. The bundled JCEF host serves the production React assets and exposes an asynchronous `window.bredDesktop.getBootstrap()` bridge only to the same-origin top-level root document. The bridge obtains the per-launch token from `LocalApiServer.rendererBootstrap()` through a native JCEF message router. The token remains in memory and is never placed in a URL, static HTML, environment variable, or browser storage.

The host blocks non-root and cross-origin top-level navigation and disables JCEF's native download fallback. Its native files are bundled in the shaded JAR and extracted under `%LOCALAPPDATA%\BRED\jcef`. The browser host has only been compiled and packaged so far; a real Windows JCEF launch, clean app-image/MSI, and installed-runtime verification remain pending. The pinned JCEF Maven release currently lists Windows AMD64 as untested upstream.

`mvn test package` builds the React distribution and packages it with JCEF. Maven downloads pinned Node 24.21.0 for build-time use; installed BRED does not need Node. The Maven frontend cache is kept outside the workspace under the build user's `.bred-build` directory to avoid changing a source `node_modules` directory that may be active.

## Local API contract

API requests use `Authorization: Bearer <per-launch-token>`, the exact loopback `Host`, and the exact `Origin`. Chromium same-origin GET requests that omit `Origin` must carry `Sec-Fetch-Site: same-origin`. Static React assets are non-sensitive, served only to loopback peers with the exact host, and do not receive the bootstrap token.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/session` | Sign in with JSON `{ "email": "...", "password": "..." }`; returns `{ "user": { "id", "fullName", "email", "role", "companyName" } }`. |
| `GET` | `/api/v1/session` | Return the current Java-authenticated user in the same response shape; returns `401` when signed out. |
| `DELETE` | `/api/v1/session` | Sign out through the existing Java auth service. |
| `GET` | `/api/v1/dashboard` | Return Java-authorized totals and project summaries; requires an active Java session. |

Errors use `{ "code": "...", "message": "..." }`. Responses are not cacheable. The API applies existing Java service/repository authorization; it accepts no company or role supplied by the frontend.

No Supabase credentials are needed in the browser. The Java services continue to own authentication, permission checks, and data access. The React preview currently covers sign-in and a read-only dashboard only; all other modules remain in Swing until their service contracts and behavior parity are migrated.

Only the Dashboard is implemented here. Other entries are migration placeholders; the existing Swing modules remain available during the staged migration.
