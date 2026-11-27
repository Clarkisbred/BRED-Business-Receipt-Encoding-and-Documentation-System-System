# Supabase Browser First Slice

Use the public Supabase client with RLS and an in-memory auth session for the first read-only React slice; do not bridge local data through an unauthenticated localhost API.

## What Happened

For BRED task `ui-shell-foundation`, the Java application had no HTTP route layer: Swing called service classes backed by authenticated repositories. A standalone web shell could safely read Supabase data directly with the existing public anon/publishable key and the signed-in user's session, relying on the database's company-scoped RLS. The browser slice loads the profile from `profiles`, validates active/approved status, and does not add writes. Local database access remains in Swing.

## Takeaway

Keep React API calls behind a small service module; never place service-role/secret keys in `VITE_*` values. Keep role-based navigation informational only—RLS and existing server-side service/RPC checks remain the security boundary. Do not expose local storage or Java services through a loopback HTTP endpoint until origin, session, CSRF, host-header, and authorization controls have been designed and tested.

## History

- 2026-11-27 (BRED/ui-shell-foundation): initial
