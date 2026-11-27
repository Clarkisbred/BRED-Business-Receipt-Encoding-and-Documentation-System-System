# Receipt Management System: Design

Date: 2026-11-22. Status: revised 2026-11-22 (Supabase is the only data store); roles Admin + Encoder approved; revision awaiting approval.

## Intent and why
Clark's old project, GERMS (a Java 25 Swing + FlatLaf desktop app on Supabase for emergency response), becomes the
**Receipt Management System (RMS)**: a central place to encode business receipts, keep them in a searchable
directory, and open any receipt in a layout that resembles the paper original. The goal is to stop digging through
physical receipts and make transactions, supplier info and project expenses quick to find.

## Goals
- Receipts: add, view, edit, delete, with line items (description, qty, unit price, subtotal).
- Paper-style receipt detail view, opened by clicking a receipt number in the directory.
- Suppliers (business name, TIN, address, contact) and Projects (name, code, fund source).
- Search/filter by receipt no., TIN, supplier, product description, date range, project.
- Attachments (image or PDF) per receipt.
- Expense monitoring + reports (by date, project, supplier) with CSV export.
- User management with roles; activity log of encoding/changes.
- Currency is PHP (₱).

## Non-goals
- No local-only/offline data mode: Supabase is the single source of truth.
- No OCR or automatic reading of receipt images.
- No web or mobile client.

## Anti-goals
- Leaving GERMS concepts behind (incidents, dispatch, alarms, maps, "Red Cross" text) in code, UI, docs or package names.
- Any service-role/secret key in the desktop app (anon key + user JWT only, as in GERMS).

## Approaches considered
1. **Reuse the GERMS shell, swap the domain (chosen).** Keep Shell, theme, login, role-based module menu, user mgmt, audit log,
   reports/CSV, background-task helpers. Remove all emergency modules. Add receipt modules.
2. Rebuild as a web app. Rejected by Clark: close to a rewrite, and the Swing + Supabase stack is wanted.
3. Fresh Swing project borrowing snippets. Rejected: loses the tested auth/RBAC/audit plumbing for no gain.

### Backend (decided by Clark: everything on Supabase so other devices share the data)
A **new** Supabase project (Clark creates it; no connector is available here, so schema is delivered as SQL migrations Clark runs in order).
- **Auth:** Supabase Auth email + password. Login is by email (the old username idea is dropped). Role lives in `profiles.role` (`admin` | `encoder`), never trusted from the client.
- **Data:** PostgreSQL tables (`profiles`, `suppliers`, `projects`, `receipts`, `receipt_items`, `receipt_attachments`, `activity_log`) with Row Level Security as the real security boundary; the Swing UI role filter is only convenience.
- **Writes:** receipt save/delete go through `SECURITY DEFINER` RPCs (atomic receipt + items, duplicate check, activity log entry).
- **Attachments:** private Supabase Storage bucket `receipts`; files are downloaded through the user's JWT.
- **Users:** Admin creates accounts through the Edge Function carried over from GERMS (service-role key stays server-side); no self-registration.
- The UI still talks only to services, and services to repository interfaces, so Supabase code stays in one package.
- **Verification limit:** this sandbox cannot reach Clark's Supabase project. Java code is tested against a fake HTTP server; SQL is validated in a throwaway local Postgres; the first live run happens on Clark's machine after running the migrations, and `SETUP.md` ends with a checklist for it.

## Design
**Config:** `application.properties` or env `RMS_SUPABASE_URL` / `RMS_SUPABASE_ANON_KEY`; if missing, the app shows a setup screen instead of crashing.

**Package/artifact:** `com.bred.rms`, artifact `rms`, window title "Receipt Management System". Keep the Maven/FlatLaf setup but target Java 21 (runs on newer JDKs too; this sandbox has JDK 21).

**Roles (decided by Clark):** only two, since a few people in the office will use it. Admin (everything, including
user management, delete, activity log) and Encoder (add/edit receipts, suppliers, projects; view reports; no delete,
no user management). No Viewer/Guest role and no public self-registration; Admin creates accounts.

**Modules:** Dashboard · Receipts (directory + add/edit dialog) · Receipt Detail (paper-style) · Suppliers · Projects ·
Reports · Users (Admin) · Activity Log (Admin) · Settings.

**Receipt record:** receipt no., date, type (Official Receipt, Sales Invoice, Cash Invoice, Delivery Receipt, Salary, Other),
supplier, project (optional), line items, VAT, discount, additional charges, grand total, attachment(s),
encoded by, created, last modified.

**Totals (my call):** subtotal = Σ qty × unit price. Grand total = subtotal − discount + VAT + additional charges.
VAT defaults to 12% of (subtotal − discount) and can be overridden by hand, since real receipts vary.
Duplicate check: same receipt no. + supplier TIN is blocked (supplier TIN is unique, so the database enforces it with a unique index on supplier + lower(receipt no.)).

**Directory:** sortable table (receipt no., date, supplier, project, total, attachment icon) with a search box plus
date/project/supplier filters; clicking the receipt number opens the detail view with Edit/Delete/Open attachment.

**Reports:** totals by project, supplier, and date range; CSV export.

## Left to the builder
Exact layouts and styling (reusing the existing theme), JSON file format details, seed data content, test list
(totals math, duplicate rule, access policy, repository round-trips, search/filter).

## Build order
1. Rename/strip GERMS: skeleton, theme/components, Supabase client package.
2. Domain models + money math.
3. SQL migrations (+ local Postgres validation), Supabase repositories, Storage attachments, Edge Function.
4. Services, auth, shell, login, setup screen.
5. Receipts directory, form, paper-style detail.
6. Suppliers, Projects, Dashboard, Reports, Users, Activity Log, Settings.
7. Docs (SETUP.md with Supabase steps) and cleanup.
