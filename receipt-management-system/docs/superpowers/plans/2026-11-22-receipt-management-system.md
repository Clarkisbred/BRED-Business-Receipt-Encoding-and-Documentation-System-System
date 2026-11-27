# Receipt Management System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the GERMS emergency-response Swing app into the Receipt Management System (RMS), with all data, users and attachments in a new Supabase project so every device shares them.

**Architecture:** A new `com.bred.rms` source tree in `/home/claude/receipt-management-system/`. Swing panels depend on services; services depend on repository interfaces; the interfaces are implemented over Supabase (Auth + PostgREST + RPC + Storage) with RLS as the security boundary. Reusable GERMS infrastructure (theme, components, `supabase` HTTP/auth client package, Shell/MainWindow structure, `admin-create-user` Edge Function) is copied over and renamed; all emergency modules are left behind.

**Tech Stack:** Java 21, Swing + FlatLaf 3.5.4, Jackson 2.17.2 (+ `jackson-datatype-jsr310`), JUnit 5.10.3, Maven; Supabase (PostgreSQL, RLS, Auth, Storage, Edge Function in Deno/TypeScript).

**Spec:** `docs/superpowers/specs/2026-11-22-receipt-management-system-design.md`
**GERMS source (read-only reference):** `/tmp/claude-0/-home-claude/5731528b-9922-582f-ac23-5f4cdda89cd8/scratchpad/src/GERMS-ERS-main/GERMS/` — call it `$OLD`.

## Global Constraints

- Java 21 (`maven.compiler.release` 21 — my call: this environment has JDK 21 and the app then runs on any newer JDK, including the JDK 25 you used for GERMS), FlatLaf 3.5.4, Jackson 2.17.2, JUnit 5.10.3; no other new runtime dependency except `jackson-datatype-jsr310` 2.17.2.
- Package `com.bred.rms`, Maven artifact `rms`, window title "Receipt Management System". No remaining "GERMS", "bred", "Red Cross", incident/dispatch/responder wording anywhere in code, UI, docs or resources (Task 11 greps for it).
- Roles are exactly `ADMIN` and `ENCODER`. Admin: everything. Encoder: add/edit receipts, suppliers, projects; view dashboard and reports; no delete, no Users, no Activity Log. No self-registration.
- Currency PHP, shown as `₱1,234.50`. All money is `BigDecimal`, scale 2, `RoundingMode.HALF_UP`. Never `double`.
- Grand total = subtotal − discount + VAT + additional charges; VAT defaults to 12% of (subtotal − discount) and may be overridden by hand.
- Duplicate rule: same receipt no. (case-insensitive, trimmed) + same supplier TIN is rejected.
- Receipt types: Official Receipt, Sales Invoice, Cash Invoice, Delivery Receipt, Salary, Other.
- All data lives in Supabase (new project); no local JSON/database storage. Config from `application.properties` in the working folder or env `RMS_SUPABASE_URL` / `RMS_SUPABASE_ANON_KEY`; only the anon/publishable key is accepted (service-role or `sb_secret_` keys are rejected at startup, as in GERMS `AppConfig`). Missing config shows a setup screen, not a crash.
- Login is email + password via Supabase Auth; role comes only from `profiles.role` (`admin` | `encoder`).
- RLS is the real security boundary; any signed-in active user may read; only `admin` may delete receipts/suppliers/projects, read the activity log, or manage users.
- UI code talks only to services; services only to repository interfaces; all Supabase code lives in `com.bred.rms.supabase` and `com.bred.rms.repositories`.
- SQL migrations live in `database/migrations/NNN_name.sql`, are idempotent where practical, and run in numeric order.
- Commit after every task; run `mvn -q test` before each commit.

## Review Focus

1. Money precision and bad amounts: `0.1 + 0.2` items total exactly `0.30`; negative qty/price/discount, or discount greater than subtotal, are rejected with a clear message.
2. Deleting a supplier or project that receipts still reference is refused with a message naming how many receipts use it.
3. Network down, expired session, or an RLS denial (HTTP 401/403/5xx, timeout, malformed JSON): the user sees a short plain message, the app stays open, an expired token is refreshed once before failing, and no half-saved receipt exists (receipt + items save in one RPC).
4. Bad attachments: unsupported type (only png, jpg/jpeg, pdf), over 10 MB, missing source file, or a Storage object later missing each give a friendly error, never a stack trace; deleting a receipt also removes its Storage objects.
5. CSV export: commas, quotes and newlines in descriptions are escaped, and cells starting with `= + - @` are prefixed with `'` so spreadsheets don't run them as formulas.

---

### Task 1: Project skeleton and carried-over UI infrastructure

**Files:**
- Create: `pom.xml`, `.gitignore`, `src/main/java/com/bred/rms/Main.java`
- Create (copied from `$OLD/src/main/java/com/bred/germs/…`, package and names changed): `ui/theme/ThemeManager.java`, `ui/theme/RmsColors.java` (from `GermsColors`; primary colour changes from Red Cross red `#C8102E` to a blue `#1F5FBF`, dark/light surface constants kept), `ui/components/{StatCard,SurfacePanel,StatusBadge}.java`, `utils/BackgroundTask.java`, `ui/RefreshablePanel.java`
- Create (copied from `$OLD/.../supabase/`, renamed package `com.bred.rms.supabase`): `SupabaseHttp, RestClient, AuthClient, SessionManager, Session, SupabaseException`; and `config/AppConfig.java` (env vars renamed `RMS_SUPABASE_URL` / `RMS_SUPABASE_ANON_KEY`, privileged-key rejection kept)
- Create: `src/test/java/com/bred/rms/SmokeTest.java`

**Interfaces:**
- Produces (besides the theme/components below): the copied Supabase client API unchanged (`RestClient.select/rpc/insertMinimal/patch/delete/invokeFunction`, `AuthClient.signIn/refresh/signOut`, `SessionManager`); `AuthClient.signUp` is deleted (no self-registration).
- Produces: `RmsColors.{PRIMARY, SUCCESS, WARNING, DANGER, INFO, SECONDARY_TEXT, background(), surface(), text(), border()}`; `ThemeManager.{installSaved(), toggle(), isDark(), addThemeListener(Consumer<Boolean>)}`; `StatCard(String title, String value, Color accent)` with `setValue(String)`; `SurfacePanel(LayoutManager, Supplier<Color>)`; `BackgroundTask.run(Callable<T>, Consumer<T> onOk, Consumer<Throwable> onErr)`.

- [ ] **Step 1:** `git init` in the project folder; write `pom.xml` from `$OLD/pom.xml` with groupId `com.bred`, artifactId `rms`, main class `com.bred.rms.Main`, plus `jackson-datatype-jsr310`.
- [ ] **Step 2: Write failing test** `SmokeTest.themeColorsExist()` asserting `RmsColors.PRIMARY != null` and `ThemeManager.isDark()` returns without throwing.
- [ ] **Step 3:** Run `mvn -q test` → expected FAIL (classes missing).
- [ ] **Step 4:** Copy and rename the files listed above; delete `forSeverity`/`forIncidentStatus`/`StatusBadge.severity/incidentStatus`; add `StatusBadge.of(String text, Color fill)` usage only. Keep `ThemeManager` preference node name unchanged in behaviour.
- [ ] **Step 5:** Copy the Supabase client package and `AppConfig` per Files (delete `signUp`; leave their existing behaviour otherwise). Write `Main.main` calling `ThemeManager.installSaved()` then (placeholder until Task 4) showing an empty `JFrame` titled "Receipt Management System".
- [ ] **Step 6:** `mvn -q test` → PASS; `mvn -q compile` → no errors. Commit `chore: RMS skeleton with carried-over UI infrastructure`.

### Task 2: Domain models and money math

**Files:**
- Create: `models/{Role,User,ReceiptType,ReceiptItem,Receipt,Supplier,Project,Attachment,ActivityEntry}.java`, `models/ReceiptMath.java`
- Test: `src/test/java/com/bred/rms/models/ReceiptMathTest.java`

**Interfaces:**
- Produces:
  - `enum Role { ADMIN("Administrator"), ENCODER("Encoder"); String label() }`
  - `record User(UUID id, String fullName, String email, Role role, boolean active)`
  - `enum ReceiptType { OFFICIAL_RECEIPT, SALES_INVOICE, CASH_INVOICE, DELIVERY_RECEIPT, SALARY, OTHER; String label() }`
  - `record ReceiptItem(String description, BigDecimal quantity, BigDecimal unitPrice) { BigDecimal subtotal() }`
  - `record Supplier(UUID id, String businessName, String tin, String address, String contactNumber)`
  - `record Project(UUID id, String name, String code, String fundSource)`
  - `record Attachment(String fileName, String storagePath)`
  - `record ActivityEntry(Instant at, UUID userId, String userName, String action, String detail)`
  - `record Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId /*nullable*/, List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges, List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt) { BigDecimal subtotal(); BigDecimal grandTotal(); }`
  - `ReceiptMath.defaultVat(BigDecimal subtotal, BigDecimal discount): BigDecimal`; `ReceiptMath.money(BigDecimal): BigDecimal` (scale 2, HALF_UP); `ReceiptMath.format(BigDecimal): String` → `₱1,234.50`.

- [ ] **Step 1: Write failing tests:** `itemsOfPoint1AndPoint2TotalExactlyPoint30` (two items qty 1 at 0.10 and 0.20 → `subtotal()` equals `new BigDecimal("0.30")`); `grandTotalIsSubtotalMinusDiscountPlusVatPlusCharges` (subtotal 1000.00, discount 100.00, vat 108.00, charges 50.00 → 1058.00); `defaultVatIsTwelvePercentOfNet` (1000.00, 100.00 → 108.00); `formatShowsPesoAndGrouping` (1234.5 → `₱1,234.50`).
- [ ] **Step 2:** Run `mvn -q test -Dtest=ReceiptMathTest` → FAIL.
- [ ] **Step 3:** Implement the types above; `Receipt` canonical constructor copies lists with `List.copyOf`; `subtotal()` sums `ReceiptItem.subtotal()` (qty × price, scaled via `money`).
- [ ] **Step 4:** Run the test → PASS. Commit `feat: receipt domain models and money math`.

### Task 3: Supabase schema, repositories, Storage attachments, Edge Function

**Files:**
- Create: `database/migrations/001_schema.sql`, `002_rls.sql`, `003_rpc.sql`, `004_storage.sql`, `database/seed_data.sql` (first-admin bootstrap comments + optional demo suppliers/projects/receipts)
- Create: `supabase/functions/admin-create-user/index.ts` (from GERMS; role check becomes `admin`/`encoder`)
- Create: `repositories/{ReceiptRepository,SupplierRepository,ProjectRepository,UserRepository,ActivityLogRepository,AttachmentStore}.java`
- Create: `repositories/supabase/{SupabaseReceiptRepository,SupabaseSupplierRepository,SupabaseProjectRepository,SupabaseUserRepository,SupabaseActivityLogRepository,SupabaseAttachmentStore}.java`
- Modify: `supabase/SupabaseHttp.java` (add `byte[] sendBinary(method, path, byte[] body, contentType, token)` and `byte[] download(path, token)`; keep the text API)
- Test: `src/test/java/com/bred/rms/repositories/SupabaseRepositoriesTest.java` (using `com.sun.net.httpserver.HttpServer` as a fake Supabase), `src/test/java/com/bred/rms/repositories/AttachmentRulesTest.java`; SQL validated by `database/validate.sh`

**Interfaces:**
- Produces (Java):
  - `ReceiptRepository`: `List<Receipt> findAll(); Optional<Receipt> findById(UUID); Receipt save(Receipt) /*calls RPC save_receipt*/; void delete(UUID) /*RPC delete_receipt*/`.
  - `SupplierRepository` / `ProjectRepository`: `List<T> findAll(); Optional<T> findById(UUID); T save(T) /*insert or update by id*/; void delete(UUID)`.
  - `UserRepository`: `List<User> findAll(); Optional<User> findById(UUID); User create(String email, String fullName, Role, char[] password) /*Edge Function*/; void update(UUID, String fullName, Role, boolean active) /*RPC admin_update_profile*/`. `User` gains `email` (Task 2 record becomes `User(UUID id, String fullName, String email, Role role, boolean active)`; the `username` field is dropped).
  - `ActivityLogRepository`: `List<ActivityEntry> findAll()` (newest first, admin only via RLS). Logging itself is server-side (RPCs and triggers write rows), so there is no client `append`.
  - `AttachmentStore`: `Attachment upload(UUID receiptId, Path source) throws AttachmentException` (checks type/size client-side, Storage path `<receiptId>/<uuid>-<fileName>`); `byte[] download(Attachment) throws AttachmentException`; `void remove(Attachment)`. `Attachment` gains `String storagePath` (Task 2: `record Attachment(String fileName, String storagePath)`).
- Produces (SQL):
  - Tables: `profiles(id uuid pk → auth.users, full_name, email, role user_role, is_active, created_at)`; `suppliers(id, business_name, tin unique, address, contact_number, created_at, updated_at)`; `projects(id, name, code unique, fund_source, created_at)`; `receipts(id, receipt_no, receipt_date, receipt_type, supplier_id → suppliers, project_id → projects null, discount numeric(14,2), vat numeric(14,2), additional_charges numeric(14,2), encoded_by → profiles, created_at, modified_at)` with unique index on `(supplier_id, lower(btrim(receipt_no)))`; `receipt_items(id, receipt_id on delete cascade, line_no, description, quantity numeric(14,3), unit_price numeric(14,2))` with CHECK qty > 0 and price >= 0; `receipt_attachments(id, receipt_id on delete cascade, file_name, storage_path)`; `activity_log(id, at, user_id, user_name, action, detail)`. Enums: `user_role ('admin','encoder')`, `receipt_type ('official_receipt','sales_invoice','cash_invoice','delivery_receipt','other')`. `suppliers`/`projects` FKs are `on delete restrict`.
  - Helpers: `is_admin()`, `is_active_user()` (both `SECURITY DEFINER`, `search_path` pinned).
  - RLS on every table: select for `is_active_user()`; insert/update on receipts-related tables, suppliers, projects for `is_active_user()`; delete for `is_admin()` only; `activity_log` select `is_admin()` only and no direct insert/update/delete; `profiles` select for `is_active_user()`, update/insert only via RPC/Edge Function.
  - RPCs: `save_receipt(p jsonb) returns uuid` (upsert receipt, replace items and attachment rows atomically; validates ≥1 item, non-negative amounts, discount ≤ subtotal; sets `encoded_by` on insert and `modified_at` always; raises readable errors such as `Duplicate receipt: …`; writes `activity_log`), `delete_receipt(id uuid) returns text[]` (admin only; returns the Storage paths so the client can remove files; logs), `admin_update_profile(id, full_name, role, is_active)` (admin only; refuses to demote/deactivate the last active admin; logs), and triggers logging supplier/project insert/update/delete.
  - Storage: private bucket `receipts`; policies allow `authenticated` active users to insert/select objects, and only admins to delete.

- [ ] **Step 1: Write failing Java tests** with the fake HTTP server: `receiptRepositoryMapsRowsWithItemsAndAttachments` (PostgREST JSON with embedded `receipt_items` and `receipt_attachments` → `Receipt` with BigDecimal totals); `saveCallsSaveReceiptRpcWithItemsAndReturnsStoredReceipt`; `rpcErrorMessageSurfacesAsValidationException` (fake returns 400 `{"message":"Duplicate receipt: …"}`); `unauthorizedRefreshesTokenOnceThenFails`; `uploadRejectsExeAndOver10MBBeforeAnyRequest` (message mentions "png, jpg or pdf" / "10 MB", fake server saw zero requests); `downloadMissingObjectGivesFriendlyMessage`; `userCreateCallsEdgeFunction`.
- [ ] **Step 2:** `mvn -q test -Dtest='SupabaseRepositoriesTest,AttachmentRulesTest'` → FAIL.
- [ ] **Step 3:** Implement the Java side (one approach line: PostgREST embedding `select=*,receipt_items(*),receipt_attachments(*)`; money parsed with `new BigDecimal(node.asText())`; HTTP error bodies mapped through one helper into `ValidationException`/`AttachmentException`/`SupabaseException` messages).
- [ ] **Step 4:** Write the SQL migrations and `seed_data.sql` per the Produces list. Reuse patterns from `$OLD/database/migrations/{001_core_schema,002_rls_policies,003_rpc_functions,007_admin_management}.sql` (helpers, `SECURITY DEFINER` + pinned `search_path`, signup trigger is NOT carried over: profiles are created by the Edge Function and the first-admin bootstrap).
- [ ] **Step 5:** Write `database/validate.sh` that starts a throwaway local PostgreSQL (use `/usr/lib/postgresql/16/bin/initdb` and `pg_ctl` into a temp dir on a spare port), creates stubs for `auth.users`, `auth.uid()`, `auth.role()`, `storage.buckets`, `storage.objects`, and roles `authenticated`/`anon`, applies migrations 001-004 in order, then runs SQL assertions: encoder can insert a receipt via `save_receipt` but `delete_receipt` raises; duplicate receipt no. + supplier raises; negative price raises; supplier delete with receipts fails; last admin cannot be demoted. Expected output: every assertion prints `ok`.
- [ ] **Step 6:** Run `bash database/validate.sh` → all `ok`; run the Java tests → PASS. Commit `feat: Supabase schema, RPCs, storage, repositories and edge function`.

### Task 4: Services, auth, access policy, app shell and login

**Files:**
- Create: `config/AppContext.java`, `security/{Module,AccessPolicy}.java`
- Create: `services/{AuthService,AuthException,ReceiptService,SupplierService,ProjectService,UserService,ReportService}.java`
- Create: `ui/{MainWindow,Shell}.java`, `ui/auth/LoginPanel.java` (new, simple: email, password, "Sign in"; no registration, no `Background3D`), `ui/auth/SetupPanel.java` (shown when URL/anon key are missing: explains the two settings and where to put them)
- Modify: `Main.java`
- Test: `src/test/java/com/bred/rms/services/{ReceiptServiceTest,SupplierProjectServiceTest,AccessPolicyTest}.java`

**Interfaces:**
- Consumes: all repository interfaces (Task 3).
- Produces:
  - `AppContext.create(): AppContext` (loads `AppConfig`, builds `SupabaseHttp`/`SessionManager`/`RestClient`, wires repositories and services); public final fields `config, auth, receipts, suppliers, projects, users, reports, log`.
  - `enum Module { DASHBOARD, RECEIPTS, SUPPLIERS, PROJECTS, REPORTS, USERS, ACTIVITY_LOG, SETTINGS; String title(); boolean allows(Role) }`; `AccessPolicy.modulesFor(Role): List<Module>` (Encoder: no USERS, no ACTIVITY_LOG).
  - `AuthService.login(String email, char[] password): User` (sign in via `AuthClient`, load `profiles` row; throws `AuthException("Invalid email or password.")` / `AuthException("This account is inactive.")` / `AuthException("Can't reach the server. Check your internet connection.")`); `current(): User`; `logout()`.
  - `ReceiptService(ReceiptRepository, SupplierRepository, ProjectRepository, AttachmentStore, Supplier<User> currentUser)`: `Receipt save(Receipt) throws ValidationException` (client-side validation for fast feedback; the RPC re-validates); `void delete(UUID)` (throws `SecurityException` unless ADMIN; removes returned Storage paths); `List<Receipt> search(ReceiptQuery)` (loads once per refresh and filters in memory; fine for an office-sized data set); `Optional<Receipt> find(UUID)`.
  - `record ReceiptQuery(String text, LocalDate from, LocalDate to, UUID supplierId, UUID projectId)`; `text` is a case-insensitive contains-match against receipt no., supplier TIN, supplier name, project name, and any item description.
  - `SupplierService`/`ProjectService`: `findAll()`, `save(...)`, `delete(UUID)` — delete throws `ValidationException("Cannot delete: N receipt(s) use this supplier.")` when referenced.
  - `ValidationException extends RuntimeException` (message is user-facing).

- [ ] **Step 1: Write failing tests** with in-memory fake repositories (plus `AuthServiceTest.wrongPasswordAndInactiveAndOfflineGiveFriendlyMessages` using the fake HTTP server from Task 3): `saveRejectsEmptyItems`; `saveRejectsNegativeQuantityPriceOrDiscount`; `saveRejectsDiscountAboveSubtotal`; `saveRejectsDuplicateReceiptNoForSameSupplierTin` (also with different case/whitespace) and `allowsSameReceiptNoForDifferentTin`; `saveStampsModifiedAt`; `encoderCannotDelete` / `adminCanDelete`; `searchMatchesTinSupplierItemProjectAndDateRange`; `supplierDeleteBlockedWhileReferenced` (message contains the count); `encoderModulesExcludeUsersAndActivityLog`.
- [ ] **Step 2:** Run `mvn -q test` → FAIL.
- [ ] **Step 3:** Implement services (validation messages are plain sentences). `MainWindow` copies the GERMS card-layout swap (`$OLD/…/ui/MainWindow.java`) with `LoginPanel` → `Shell`. `Shell` copies the GERMS top bar + sidebar structure (`$OLD/…/ui/Shell.java`), builds panels lazily per `Module` via a `switch` returning a `JComponent` (panels not built yet show a `JLabel` "Coming in a later task"), shows user name, role label, theme toggle, sign-out.
- [ ] **Step 4:** Run `mvn -q test` → PASS. With no config the app shows the setup screen; start it under `xvfb-run` for 5 s → no exception. Commit `feat: services, auth, access policy, shell and login`.

### Task 5: Receipt directory (search, filter, sort)

**Files:**
- Create: `ui/receipts/ReceiptDirectoryPanel.java`, `ui/receipts/ReceiptTableModel.java`
- Modify: `ui/Shell.java` (wire `Module.RECEIPTS`)
- Test: `src/test/java/com/bred/rms/ui/ReceiptTableModelTest.java`

**Interfaces:**
- Consumes: `ReceiptService.search`, `SupplierService.findAll`, `ProjectService.findAll`.
- Produces: `ReceiptTableModel(List<Receipt>, Map<UUID,Supplier>, Map<UUID,Project>)` with columns Receipt No., Date, Supplier, Project, Total, Files (📎 count); `ReceiptDirectoryPanel(AppContext, Consumer<UUID> onOpen, Runnable onAdd)`; clicking the Receipt No. cell (rendered as a link) calls `onOpen(receiptId)`; "Add Receipt" button calls `onAdd`.

- [ ] **Step 1: Write failing tests:** `columnsAndValuesMatchReceipt` (row text for a known receipt: number, `₱` total, supplier name, "—" for no project); `sortsByDateDescendingByDefault`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement: search box (debounced 250 ms) + From/To date fields + Supplier and Project combo boxes with "All"; filtering via `ReceiptService.search` on a `BackgroundTask`; empty state text "No receipts match your search." **Step 4:** Run → PASS; launch app, sign in as `admin`, confirm seeded receipts list and search by TIN works. Commit `feat: receipt directory`.

### Task 6: Receipt form (add/edit) with line items and attachments

**Files:**
- Create: `ui/receipts/ReceiptFormDialog.java`, `ui/receipts/LineItemsTableModel.java`
- Modify: `ui/Shell.java` (open dialog from directory/detail)
- Test: `src/test/java/com/bred/rms/ui/LineItemsTableModelTest.java`

**Interfaces:**
- Consumes: `ReceiptService.save`, `AttachmentStore.store`, `ReceiptMath`.
- Produces: `ReceiptFormDialog(Window owner, AppContext ctx, Receipt existing /*nullable*/, Consumer<Receipt> onSaved)`; `LineItemsTableModel` with `addRow()`, `removeRow(int)`, `items(): List<ReceiptItem>`, `subtotal(): BigDecimal`, firing table events on change.

- [ ] **Step 1: Write failing tests:** `subtotalUpdatesWhenQuantityChanges`; `blankDescriptionRowsAreIgnoredByItems()`; `nonNumericQuantityIsRejectedAndKeepsOldValue`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement the dialog: header fields (Receipt No., Date picker via `JSpinner` date model, Type, Supplier combo with "New supplier…" shortcut, Project combo, Fund source shown read-only from the project); items table; Discount, VAT (auto-filled via `ReceiptMath.defaultVat` until the user edits it, with a "Recompute 12%" link), Additional charges; live Subtotal and Grand Total labels; attachment list with Add (JFileChooser) / Remove; Save shows `ValidationException`/`AttachmentException` messages in a red label, never a stack trace. **Step 4:** Run → PASS; manually add a receipt with a PDF and confirm it appears in the directory. Commit `feat: receipt add/edit form with attachments`.

### Task 7: Paper-style receipt detail view

**Files:**
- Create: `ui/receipts/ReceiptDetailPanel.java`, `ui/receipts/PaperReceiptView.java`
- Modify: `ui/Shell.java` (card for detail; directory `onOpen` shows it; Back returns to the directory)
- Test: `src/test/java/com/bred/rms/ui/PaperReceiptViewTest.java`

**Interfaces:**
- Produces: `PaperReceiptView(Receipt, Supplier, Project /*nullable*/)` — a white, fixed-width (~420 px) panel in a monospace-style font with: supplier name/address/TIN/contact at the top, receipt type and number, date, item rows (description, qty × unit price, subtotal), then Subtotal, Discount, VAT, Additional charges, **TOTAL**, and a footer "Encoded by … on …, last modified …"; `plainText(): String` returns the same content as text. `ReceiptDetailPanel(AppContext, UUID receiptId, Runnable onBack)` shows it plus Edit, Delete (visible to Admin only, with confirm dialog), Open attachment (preview PNG/JPG in a dialog, open PDF with `Desktop.open`; missing file → friendly message).

- [ ] **Step 1: Write failing test:** `plainTextContainsAllRequiredFields` (number, date, TIN, supplier name, each item line, VAT, grand total with `₱`). **Step 2:** Run → FAIL. **Step 3:** Implement; the paper view stays white with dark text in dark theme. **Step 4:** Run → PASS; click a seeded receipt and compare the layout to the spec's list. Commit `feat: paper-style receipt detail view`.

### Task 8: Suppliers and Projects panels

**Files:**
- Create: `ui/lookups/SupplierPanel.java`, `ui/lookups/ProjectPanel.java`, `ui/lookups/SimpleFormDialog.java`
- Modify: `ui/Shell.java`
- Test: covered by `SupplierProjectServiceTest` (Task 4); add `ProjectServiceTest.deleteBlockedWhileReferenced`

**Interfaces:**
- Consumes: `SupplierService`, `ProjectService`. Produces: each panel = table + Add/Edit buttons (+ Delete for Admin); Projects panel also shows each project's total spend; delete errors shown in a dialog using the service's message.

- [ ] **Step 1:** Write `ProjectServiceTest.deleteBlockedWhileReferenced` → run → FAIL (if not already covered). **Step 2:** Implement panels; TIN field trimmed, required for suppliers. **Step 3:** Run tests → PASS; try deleting a seeded supplier that has receipts → message appears. Commit `feat: suppliers and projects management`.

### Task 9: Dashboard and Reports with CSV export

**Files:**
- Create: `ui/DashboardPanel.java`, `ui/reports/ReportsPanel.java`
- Modify: `services/ReportService.java`, `ui/Shell.java`
- Test: `src/test/java/com/bred/rms/services/ReportServiceTest.java`

**Interfaces:**
- Produces: `record Total(String label, int receiptCount, BigDecimal amount)`; `ReportService.totalsByProject(LocalDate from, LocalDate to): List<Total>` (receipts without a project grouped as "Unassigned"); `totalsBySupplier(from, to)`; `totalsByMonth(from, to)`; `exportCsv(List<Receipt>, Path) throws IOException` (columns: Receipt No., Date, Type, Supplier, TIN, Project, Subtotal, Discount, VAT, Additional Charges, Grand Total).

- [ ] **Step 1: Write failing tests:** `totalsByProjectGroupsUnassigned`; `totalsRespectDateRangeInclusive`; `csvEscapesCommasQuotesAndNewlines`; `csvPrefixesFormulaCells` (supplier name `=HYPERLINK(...)` → `'=HYPERLINK(...)`). **Step 2:** Run → FAIL. **Step 3:** Implement. Dashboard: StatCards (receipts this month, spend this month, total receipts, projects with spend) + "Recent receipts" list (click opens detail). Reports: date range + group-by selector, table, Export CSV via `JFileChooser`. **Step 4:** Run → PASS. Commit `feat: dashboard and reports`.

### Task 10: Users, Activity Log and Settings

**Files:**
- Create: `ui/admin/UserManagementPanel.java`, `ui/admin/ActivityLogPanel.java`, `ui/SettingsPanel.java`
- Modify: `services/UserService.java`, `ui/Shell.java`
- Test: `src/test/java/com/bred/rms/services/UserServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `ActivityLogRepository`.
- Produces: `UserService.list(): List<User>`; `create(String fullName, String email, Role, char[] password): User` (password ≥ 6 chars, email shaped like an address; errors from the Edge Function shown as-is); `update(UUID, String fullName, Role, boolean active)`; all throw `SecurityException` unless the current user is ADMIN. `SettingsPanel` has the theme toggle and "Change my password" (calls `AuthClient` password update for the signed-in user via `PUT /auth/v1/user`; add `AuthClient.updatePassword(String accessToken, String newPassword)`).

- [ ] **Step 1: Write failing tests:** `encoderCannotManageUsers`; `createRejectsShortPasswordAndBadEmail`; `lastAdminDemoteErrorMessageIsShown` (fake repository throws the RPC's message; service passes it through); `changePasswordRejectsShortPassword`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement services and panels (Users: table, Add, Edit role/name, Deactivate/Activate; Activity Log: newest-first table with search box; Settings as above). **Step 4:** Run → PASS. Commit `feat: user management, activity log, settings`.

### Task 11: Documentation and GERMS cleanup

**Files:**
- Create: `README.md`, `SETUP.md`, `ARCHITECTURE.md`, `application.properties.example`. `SETUP.md` is step-by-step for a non-expert: create the new Supabase project; run migrations 001-004 in the SQL editor in order; deploy `admin-create-user` (CLI steps); create the first admin (bootstrap steps in `seed_data.sql`: create the Auth user in the dashboard, then insert the `profiles` row as `admin`); copy URL + anon key into `application.properties`; run/package commands; a **first live run checklist** (sign in, add supplier, add receipt with PDF, open it from a second device/session, encoder cannot delete, admin can).
- Test: `src/test/java/com/bred/rms/CleanupTest.java`

- [ ] **Step 1: Write failing test** `noGermsLeftovers`: walks `src/` and `*.md` in the project root and fails if any file contains (case-insensitive) `germs`, `bred emergency`, `red cross`, `incident`, `dispatch`, `responder`, `evacuation` (excluding `pom.xml` groupId `com.bred`, and this `docs/` folder).
- [ ] **Step 2:** Run → FAIL if anything remains; fix each hit. **Step 3:** Run full `mvn -q test`, `bash database/validate.sh`, and `mvn -q package`; start the jar once under `xvfb-run` with no config → setup screen appears. Commit `docs: RMS documentation and GERMS cleanup`.
- [ ] **Step 4:** Zip the project (excluding `target/`, `.git/`) and send it to the user with a short note: what was verified here (unit tests, fake-server tests, SQL validation) and what only they can verify (first live run against their Supabase project, using the checklist in `SETUP.md`).
