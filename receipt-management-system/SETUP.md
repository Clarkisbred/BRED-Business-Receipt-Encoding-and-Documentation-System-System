# Setup guide: run BRED with Supabase

The app requires Supabase; it does not silently create or use a local database. You need a Supabase account, Java 21+
and Maven on the computer that runs the app.

## 1. Create the Supabase project
1. Go to <https://supabase.com/dashboard> and click **New project**. This must be a **new** project, not the one from any older app.
2. Pick a name, a strong database password (save it somewhere) and the region closest to you. Wait until it finishes setting up.

## 2. Create the tables, rules and storage
1. In the project, open **SQL Editor**.
2. Open each file below from the `database/migrations/` folder, paste it into a new query and press **Run**. Do them **in this order** and wait for "Success" each time:
   1. `001_schema.sql`: tables
   2. `002_rls.sql`: who may read and write what
   3. `003_rpc.sql`: the save/delete/update functions
   4. `004_storage.sql`: the private `receipts` bucket for attachments
   5. `005_local_import.sql`: idempotency key for imported activity history
   6. `006_account_approval.sql`: pending account status, review RPCs and protected profile access
   7. `007_creator_role.sql`: adds the global Creator role
   8. `008_company_scope_and_contacts.sql`: company-scoped data, private bug reports, and their Storage bucket
   9. `009_creator_account.sql`: assigns `clarkhilario.bread@gmail.com` to the protected Creator role
   10. `010_custom_messages.sql`: private direct messages and company-aware teammate contacts
   11. `011_creator_management_and_broadcasts.sql`: Creator company controls, deleted-account message retention, global history and broadcasts
   12. `012_company_group_chat.sql`: company-wide group chat and urgent message labels
   13. `013_app_releases.sql`: shared current-version display and Creator-only version publishing
   14. `014_ai_receipt_import_approval.sql`: server-validated administrator approval for imported receipts and audit events

   Migration 008 creates a `Legacy Company` only when needed and assigns previously unscoped records and profiles to it.
   Existing companies are retained. The Creator can see all companies; Admins and Encoders are restricted to their own.
   Migration 011 permits company edits/deletion only for the Creator. A company with linked profiles or business records
   cannot be deleted. Deleting an account keeps its message text in History and replaces its profile references with a
   deleted-user label.
   Migration 012 adds a shared chat for each company; apply it to enable the group room in the desktop app.
   Migration 013 enables the public version label and the Creator-only version editor in Settings. Publishing changes
   the shared version number shown to users; it does not download or replace the installed application.
   Migration 014 adds no table or RLS changes. Its approval RPC requires an active company Admin, derives company
   ownership from the authenticated profile and selected in-company supplier, and delegates receipt writes to the
   existing `save_receipt` RPC. Apply it before using AI Receipt Import approval or attachment linking.

   The updater expects a stable GitHub release asset named `BRED-Setup-<version>.msi` (for example, tag `v1.2.3`
   uses `BRED-Setup-1.2.3.msi`). BRED stages downloads in a per-user update directory. If GitHub supplies an asset
   SHA-256 digest in release metadata, BRED verifies it; this is an integrity check, not a Windows publisher signature.
   The Windows MSI build script is documented below. Creating an MSI does not install or publish it.

## Windows MSI update handoff prerequisites

The current development machine has a registered BRED 1.0.5 installation at `D:\BRED\`. Windows records it under
HKLM with `WindowsInstaller=1` and an `MsiExec /X` uninstall command, indicating a per-machine MSI installation.
This is machine-specific evidence, not a package definition for other computers.

The repository does not contain the original MSI or its original build inputs. The MSI's original install source is
no longer available and its cached MSI could not be located. A repeatable Java 21 jpackage build script is provided
below; it preserves the required update identity supplied for future MSI builds:
`7B6F2C1E-4A8D-4F3B-9C1A-5E2D8F0A1B34`; the handoff rejects packages whose MSI metadata does not match it or include
the installed version in the MSI Upgrade table. The installed package version (1.0.5) also differs from this source
tree's Maven version (`0.1.0-SNAPSHOT`). Upgrade compatibility is not verified until an actual MSI is inspected and
tested against the existing installation in an isolated environment.

### Build a Windows MSI

Run this from the `receipt-management-system` project directory on Windows with a Java 21 JDK, Maven, and WiX
Toolset 3 (`candle.exe` and `light.exe`) available. The script runs `mvn -DskipTests package`, validates the shaded
JAR's entry point and bundled libraries, builds a jpackage app image, and checks that its launcher and Java runtime
are present before creating an MSI.

Validate the build inputs and arguments without generating an MSI:

```powershell
.\scripts\build-windows-msi.ps1 -Version 1.0.6 -ValidateOnly
```

The eventual command to build a versioned MSI is:

```powershell
.\scripts\build-windows-msi.ps1 -Version 1.0.6
```

The version must have three numeric components, each from 0 through 65535. The output is
`target\installers\BRED-Setup-1.0.6.msi`. Existing output is not overwritten unless `-Force` is explicitly supplied.
The MSI remains per-machine (the script does not use `--win-per-user-install`) and includes the required UpgradeCode.
It offers a directory chooser; to target the existing installation, the user must select `D:\BRED`. The build script
does not install the package, and the directory selection or shared UpgradeCode alone does not establish upgrade
compatibility.

To inspect a candidate MSI, open it in Orca (from the Windows SDK) and check:

1. In the `Property` table, confirm `ProductVersion`, the generated `ProductCode`, and the required `UpgradeCode`.
2. In the `Upgrade` table, confirm the `UpgradeCode` and that the version range/action properties include the
   installed BRED 1.0.5 package as an upgrade target.
3. Confirm the installer is per-machine and that its interactive directory selection can target `D:\BRED`.

The ProductCode is generated for the package and is not the stable upgrade identity. Preserve the UpgradeCode above.
Do not install or test a candidate against the production BRED installation. Use an isolated Windows VM with BRED
1.0.5 installed, choose `D:\BRED` in the interactive MSI, and verify upgrade, cancellation/rollback, app launch,
version, and user-data preservation before publishing any release.

Before creating or executing an in-place update, recover and verify:

1. The existing MSI's architecture, manufacturer, product naming/version rules, `ALLUSERS` / `MSIINSTALLPERUSER`
   scope, install directory rules, and Upgrade table behavior. The original package is unavailable, so the new
   script cannot prove those properties match. Preserve the specified upgrade identity, per-machine scope, and
   `D:\BRED` selection during isolated testing.
2. The interactive MSI behavior, including elevation and cancellation. The updater uses the normal interactive
   `msiexec.exe /i` flow with administrator approval; it does not use silent-install switches.
3. An isolated Windows test environment with BRED 1.0.5 installed. Verify that the candidate MSI upgrades in place
   and that cancel, rollback, and failure leave the prior installation usable. Do not test by modifying the production
   installation.

### User data and configuration preservation

The installed `application.properties` is under `D:\BRED\app\`, inside the installation tree, so an MSI upgrade could
replace it. The application migrates filesystem-based configuration to `%APPDATA%\BRED\application.properties` only
when the destination does not exist, verifies that the copy can be read, prefers the external file on later launches,
and retains the original. Do not log configuration values.

In local-data mode, BRED stores `data.json` in the selected data directory (by default `%USERPROFILE%\RMS-Data`, or
the directory selected by `RMS_DATA_DIR`); receipt attachments are stored in that directory's `attachments` folder.
These paths are outside `D:\BRED` and must remain untouched. Java Preferences are also stored outside the install
tree. Supabase-backed records remain remote; no database migration or production data change is part of installer
work.

No installer launch, installation, restart, or change to the installed BRED application has been made. Proceed only
after the MSI upgrade identity and compatibility have been recovered and verified, and after explicit approval.

## 3. Create a Creator account
1. **Authentication → Users → Add user → Create new user.** Enter your email and a password, and tick **Auto Confirm User**.
2. Click the new user and copy its **UUID**.
3. In **SQL Editor** run (replace the values; use an existing company UUID, or the `Legacy Company` UUID):
   ```sql
   insert into profiles (id, full_name, email, role, is_active, account_status, company_id)
   values ('PASTE-USER-UUID-HERE', 'Creator Name', 'creator@example.com', 'creator', true, 'APPROVED',
           'PASTE-COMPANY-UUID-HERE');
   ```
4. Sign in as Creator. Admins can then register themselves from the sign-in screen; that flow creates their company.

## 4. Deploy account functions
These server-side functions let admins create accounts and let the Creator delete accounts or request password reset
emails. They keep the powerful service key on the server. Password reset links are generated by Supabase Auth; the
branded reset email is sent through Resend. Supabase Auth's own email delivery remains responsible for other Auth
messages. Reset links open the hosted `web/reset-password.html` page (see "Reset page" below), so set
`PASSWORD_RESET_URL` or the email falls back to Supabase's default link.
1. Install Node.js/npm if needed. You can run the Supabase CLI through `npx` without installing a global `supabase` command.
2. Open PowerShell in the BRED project folder (the folder containing `supabase\config.toml` and `supabase\functions\`), then run:
   ```
   npx supabase login
   npx supabase link --project-ref YOUR-PROJECT-REF
   npx supabase functions deploy admin-create-user
   npx supabase functions deploy admin-delete-user
   npx supabase functions deploy admin-send-password-reset
   npx supabase functions deploy admin-import-activity
   npx supabase functions deploy register-account
   npx supabase functions deploy review-account
   ```
   Create a Resend API key and verify the sender domain in Resend, then set the Edge Function secrets (replace the
   placeholders; never commit or share the API key):
   ```powershell
   npx supabase secrets set --project-ref YOUR-PROJECT-REF RESEND_API_KEY=YOUR_RESEND_API_KEY 'EMAIL_FROM=BRED <reset@your-verified-domain.com>'
   ```
      **Reset page (works in any browser and any mailbox).** Reset emails link to a small static page,
   `web/reset-password.html`, instead of Supabase's one-time link (which Outlook/Yahoo/Gmail link scanners can use up).
   1. Open `web/reset-password.html` and paste your Supabase anon key into `SUPABASE_ANON_KEY`.
   2. Host the `web/` folder anywhere public over HTTPS (GitHub Pages: repo Settings > Pages > deploy from branch).
   3. In Supabase > Authentication > URL Configuration, add the page URL to **Redirect URLs**.
   4. Tell the function where it is, then redeploy it:
      ```powershell
      npx supabase secrets set --project-ref YOUR-PROJECT-REF PASSWORD_RESET_URL=https://YOUR-HOST/reset-password.html
      npx supabase functions deploy admin-send-password-reset
      ```
   `EMAIL_FROM` must use a sender/domain verified with Resend. Reset emails can be delivered to Gmail, Yahoo, and other
   recipient providers. The email uses the BreDuck mascot from this repository by default; set the optional
   `BRED_LOGO_URL` secret to another public HTTPS logo URL if needed. The design uses the warm gold/cream BreDuck
   Signature palette and displays **BRED**, the recipient's company name, then **Business Receipt Encoding and
   Documentation System**.
   `YOUR-PROJECT-REF` is the part before `.supabase.co` in your project URL.
   Registration requires the current `register-account` function to be deployed. In the registration form, select
   **Admin** (not Encoder) and enter a new company name; Admin registration creates the company automatically.
   If Admin registration still reports “Choose an available company,” first rebuild and launch the latest app JAR
   (`mvn -q package`, then `java -jar target/bred-0.1.0-SNAPSHOT.jar`): the request is reaching Supabase with the
   Encoder role. If the latest JAR still gives that error, redeploy the function above. The company-creation RPC also
   requires migration `008_company_scope_and_contacts.sql`. If `npx` cannot find Node.js, install Node.js first and
   reopen PowerShell.
   The functions receive `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` automatically; you do not set
   anything.    `supabase/config.toml` disables gateway JWT verification only for `register-account`; that function uses the
   service role for listing active company names and for creating either a pending Encoder profile or a company plus
   immediately active Admin profile. Keep the service-role key in Supabase's function secrets only; never put it in
   `application.properties`.

## 5. Point the app at your project
1. In Supabase open **Project Settings → API**. Copy the **Project URL** and the **anon / publishable** key.
2. Copy `application.properties.example` to `application.properties` next to the app and fill in both values.
   (Alternatively set the environment variables `RMS_SUPABASE_URL` and `RMS_SUPABASE_ANON_KEY`.)
3. **Never** put the `service_role` or secret key in the app. The app refuses to start with one.

## 6. Run
```
mvn -q exec:java                  # run from source
mvn -q package                    # or build a single jar…
java -jar target/bred-0.1.0-SNAPSHOT.jar
```
Copy the jar and the same `application.properties` (project URL and public anon/publishable key) to the other computers.
Create a separate Supabase Auth account for each employee. In the Receipts directory, use **Refresh** to load records
added by other users while the screen is open.

## Import existing local data
If you have a previous `RMS-Data` folder, sign in as an admin, open **Settings**, and choose **Import local data into
Supabase**. Select the folder containing `data.json`. The importer copies suppliers, projects, receipts, attachments and
activity history. Matching local activity users are linked to Supabase accounts by email; unmatched activity keeps its
display name without a user link. Local user accounts and password hashes cannot be transferred to Supabase Auth, so
recreate those users from **Users**. Imported receipts are attributed to the admin performing the import. The source
folder is left unchanged; imports can be retried after a partial failure.

## First live run checklist
This app was built and tested without access to a live Supabase project (unit tests, a fake server and a throwaway PostgreSQL were used). Please walk through this once on your real project:

- [ ] The app starts and shows the sign-in screen (not the setup screen).
- [ ] Sign in with the admin account from step 3.
- [ ] **Suppliers → Add supplier** (name and TIN required) saves and appears in the list.
- [ ] **Projects → Add project** saves.
- [ ] **Reports → Export Excel** opens as an .xlsx in Excel, and **Export CSV** still opens correctly.
- [ ] As Creator, **Users → Send reset link** sends the email, and the link opens the reset page in any browser.attachment saves; the paper-style view opens afterwards.
- [ ] Open the attachment from the receipt view.
- [ ] Adding the same receipt number for the same supplier again is refused.
- [ ] On a second computer (or a second run) sign in and see the same receipt.
- [ ] **Users → Add user** creates an Encoder account (tests the Edge Function).
- [ ] From the sign-in screen, **Create Account** submits a request and displays the approval message.
- [ ] Select **Admin** in registration and enter a new company name: the company is created automatically, then sign in immediately with the created account.
- [ ] The request appears in **Users** with **PENDING** status; its password cannot sign in before approval.
- [ ] Approving enables sign-in; rejecting keeps the account blocked.
- [ ] Sign in as the Encoder: no Users or Activity Log in the menu, no Delete button on receipts, Add/Edit works.
- [ ] Back as Admin: deleting a receipt works, and the Activity Log shows the actions.
- [ ] **Reports → Export CSV** opens correctly in Excel.

If something fails, note the message shown on screen and which step it happened on.
