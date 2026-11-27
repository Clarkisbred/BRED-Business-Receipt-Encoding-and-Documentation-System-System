# BRED React desktop workspace

This existing React/Vite/TypeScript frontend is bundled with the Java desktop application. After sign-in, it is the primary workspace on Windows AMD64; the Swing interface remains available as a fallback and through the desktop View menu.

## Build

Run `npm ci`, then `npm run dev` for frontend development or `npm run build` for a production bundle. The standalone Vite page can render without credentials, but sign-in and data calls require the embedded desktop host.

After a successful sign-in on Windows AMD64, Java starts the loopback API on `127.0.0.1` and the bundled JCEF host loads the production React workspace. The asynchronous `window.bredDesktop` bridge is restricted to the same-origin top-level root document. Its per-launch token remains in memory and is never placed in a URL, static HTML, environment variable, or browser storage.

The host blocks non-root and cross-origin top-level navigation and disables JCEF's native download fallback. Its native files are bundled in the shaded JAR and extracted under `%LOCALAPPDATA%\BRED\jcef`. The MSI build includes the production React assets and JCEF runtime; verify installed-launch and upgrade behavior separately before release. The pinned JCEF Maven release currently lists Windows AMD64 as untested upstream.

`mvn test package` builds the React distribution and packages it with JCEF. Maven downloads pinned Node 24.21.0 for build-time use; installed BRED does not need Node. The Maven frontend cache is kept outside the workspace under the build user's `.bred-build` directory to avoid changing a source `node_modules` directory that may be active.

## Local API contract

API requests use `Authorization: Bearer <per-launch-token>`, the exact loopback `Host`, and the exact `Origin`. Chromium same-origin GET requests that omit `Origin` must carry `Sec-Fetch-Site: same-origin`. Static React assets are non-sensitive, served only to loopback peers with the exact host, and do not receive the bootstrap token.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/session` | Sign in with JSON `{ "email": "...", "password": "..." }`; returns `{ "user": { "id", "fullName", "email", "role", "companyName" } }`. |
| `GET` | `/api/v1/session` | Return the current Java-authenticated user in the same response shape; returns `401` when signed out. |
| `DELETE` | `/api/v1/session` | Sign out through the existing Java auth service. |
| `GET` | `/api/v1/dashboard` | Return Java-authorized totals and project summaries; requires an active Java session. |
| `GET` | `/api/v1/modules/{module}` | Return data for an authorized workspace module. |
| `POST` | `/api/v1/modules/{module}` | Perform an authorized workspace action with an `action`, optional `id`, and `values` request body. |
| `GET` | `/api/v1/map/search` | Search project locations through the Java service. |

Errors use `{ "code": "...", "message": "..." }`. Responses are not cacheable. The API applies existing Java service/repository authorization; it accepts no company or role supplied by the frontend.

The React workspace includes the dashboard, messages, Ducky assistant, and module workspaces for the remaining navigation sections. Java remains responsible for authentication, permissions, company restrictions, and data access. Supabase credentials and privileged keys are not shipped to the browser; React calls the authenticated loopback API, while the Java repositories/services use the existing configured Supabase integration. When Supabase is unavailable or unconfigured, the Java application's setup/error flow remains authoritative.
