# BRED web shell

This is the first staged React/Vite/TypeScript shell. It is intentionally separate from the existing Swing application and does not replace or repackage it.

## Run locally

1. Copy `.env.example` to `.env.local`.
2. Set `VITE_SUPABASE_URL` and the existing project's **public anon/publishable key**. A service-role/secret key is rejected.
3. Run `npm ci`, then `npm run dev`.
4. Sign in with an approved BRED account. The Dashboard reads the existing `profiles`, `projects`, `receipts`, and `receipt_items` tables through the Supabase client and current user's RLS-protected session.

Auth tokens are held in memory only, matching the desktop app's non-persistent session model. Refreshing this page requires another sign-in. The shell performs read-only queries; Java services, RPC write rules, Supabase/local storage and the current MSI remain unchanged.

Only the Dashboard is implemented here. Other entries are navigation placeholders and continue to use the Swing app. Do not expose a new unauthenticated localhost API to bridge the web shell to local data.
