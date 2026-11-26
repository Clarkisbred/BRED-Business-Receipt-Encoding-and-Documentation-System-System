# Architecture

## Layers
```
Swing UI (com.bred.rms.ui)         screens, dialogs, no network code
   ↓
Services (…services)               rules: validation, permissions, totals, reports
   ↓
Repository interfaces (…repositories)
   ↓
Supabase implementations (…repositories.supabase) → RestClient / AuthClient (…supabase)
```
The UI only talks to services, services only to repository interfaces, so all Supabase code lives in two packages and
the rest can be tested with in-memory fakes.

## Storage mode
The desktop app requires a configured Supabase project. Local storage classes remain for compatibility and automated tests,
but startup does not fall back to local data if configuration is missing. Administrators can import a previous local
`RMS-Data` folder from Settings.

## Data and security (Supabase mode)
- **Supabase is the app's data store.**
- **Auth:** Supabase Auth (email + password). The role (`creator`, `admin` or `encoder`) comes from the `profiles` table, never from the client.
- **Row Level Security is the real security boundary.** Admins and Encoders are scoped to their company; the Creator can see and manage data across companies. Receipts and their items are written only through RPCs. Deleting needs Admin or Creator. The activity log is company-scoped for Admins and global for the Creator.
- **RPCs** (`save_receipt`, `delete_receipt`, `admin_update_profile`) run as `SECURITY DEFINER`: a receipt and its items are saved atomically, duplicates (same receipt number + same supplier, ignoring case and spaces) are refused, the last active admin cannot be demoted or deactivated, and every change is written to `activity_log`.
- **AI receipt approval** uses `approve_ai_import_receipt`, which requires an active approved company Admin, derives company context from the authenticated profile, verifies the selected supplier and saved receipt server-side, and delegates data writes to `save_receipt`. Imported source files are uploaded only after the receipt save and linked through this same gate.
- **Attachments** go to the private Storage bucket `receipts` under `<receipt id>/<uuid>-<file name>` and are downloaded with the user's own token. Files are uploaded when Save is pressed. Encoders cannot delete Storage objects, so an attachment removed during an edit may stay behind as an unreferenced file; they can be cleaned up by hand in the Supabase dashboard (Storage → receipts).
- **Creating users** goes through the `admin-create-user` Edge Function. The service-role key exists only there; the desktop app holds just the public anon key and refuses a secret key at start-up.
- **Public registration** uses `register-account`: Encoders apply to an existing company and wait for approval; Admins create a company and an immediately active account. `review-account` approves or rejects Encoder applications.
- **Account access** is limited by RLS to approved, active users. Users can read their own profile to receive the pending/rejected sign-in message; only approved admins can read other profiles. Profile writes and status changes are never granted to desktop clients.
- **Local import** upserts suppliers and projects by ID, saves receipts through `save_receipt`, uploads attachments to the private bucket, and imports activity through the admin-only `admin-import-activity` Edge Function. The source data is not changed. Auth accounts/password hashes cannot be transferred; recreate accounts in Supabase. Imported receipts are attributed to the importing administrator.

## Money
Amounts are `BigDecimal` with two decimals, rounded half-up. Subtotal = Σ quantity × unit price. Grand total = subtotal − discount + VAT + additional charges. VAT defaults to 12% of (subtotal − discount) and can be overridden by hand.

## Layout
| Path | What is there |
|---|---|
| `src/main/java/com/bred/rms/models` | Plain records (Receipt, Supplier, Project, User…) and money maths |
| `…/services` | ReceiptService, SupplierService, ProjectService, UserService, ReportService, AuthService |
| `…/repositories` | Interfaces plus `supabase/` implementations |
| `…/supabase` | HTTP client, session handling, Auth client, config |
| `…/ui` | Shell (sidebar + header), panels per module, theme |
| `database/migrations` | `001`–`014` SQL, run in order |
| `database/test`, `database/validate.sh` | SQL assertions run on a throwaway PostgreSQL |
| `supabase/functions/` | Registration, account review, admin account creation and activity import Edge Functions |

Creator bug reports are submitted from Settings → Contacts. Optional images are kept in the private `bug-reports` Storage bucket. The Creator profile image can be added at `src/main/resources/com/bred/rms/ui/logo/breducklogo.png`.
