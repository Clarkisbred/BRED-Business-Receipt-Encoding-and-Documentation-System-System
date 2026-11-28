# BRED — Business Receipt Encoding and Documentation System

A desktop app for a small office to encode business receipts, keep them in one searchable directory, and open any
receipt in a layout that looks like the paper original.

The app stores data in **Supabase** so multiple computers share the same records. Configure your project before
starting the app (see [SETUP.md](SETUP.md)). Existing local data can be imported from **Settings** by an administrator.

## What it does
- **Receipts**: encode official receipt details with repeatable line items and SI units, supplier TIN/address, account type, personal notes, VAT, discount, charges, and configurable EWT (0–100%). Totals display in pesos (₱), including the EWT-adjusted net payable. Attach png, jpg, jpeg, and PDF scans.
- **Receipt detail**: click a receipt number to see it as a paper-style slip, with Edit, Delete (admins) and Open attachment.
- **Search and filter**: by receipt number, TIN, supplier, product description, date range, supplier or project.
- **Suppliers and Projects**: business name, TIN, address, contact; project name, code, fund source, with total spend per project.
- **Dashboard and Reports**: this month's receipts and spend, recent receipts, totals by project, supplier or month, CSV export.
- **Messaging**: a company-wide group chat with urgent message labels, plus private one-to-one chats.
- **Users and Activity Log** (admins): create accounts, change roles, deactivate people, see who changed what.
- **Creator tools**: view all accounts and companies, edit and delete accounts/companies, send password reset links,
  inspect cross-company chat history, and broadcast persistent messages with profile-picture toasts.

## Roles
| Role | Can do |
|---|---|
| Admin | Everything, including deleting receipts, managing users and reading the activity log |
| Encoder | Add and edit receipts, suppliers and projects; view the dashboard and reports. No deleting, no Users, no Activity Log |
| Creator | Global account and company management, password reset emails, all chat history and broadcasts |

Anyone may submit a **Create Account** request from the sign-in screen. New accounts remain pending and cannot access BRED
until an administrator approves them in **Users**. An administrator may also continue to create accounts directly.

## Getting started
1. Install Java 21 or newer and Maven.
2. From this directory, run `mvn -q generate-resources exec:java` (or `mvn -q package`, then `java -jar target/bred-2.0.0.jar`). In VS Code, use the Java extension's Run/Debug action for `com.bred.rms.Main` after Maven has built the project. Do not use Code Runner's **Run Code** action, which compiles only `Main.java` and omits the bundled web assets.
3. Sign in with the Supabase admin account created during setup.
4. Add a supplier, then a receipt. Create more accounts (Admin or Encoder) under **Users**.

**Importing old local data:** In the signed-in Supabase app, open **Settings → Move existing local data to Supabase**, select the folder containing `data.json`, and confirm. The import is retryable and does not delete local files. Local passwords are not portable to Supabase Auth; recreate accounts under **Users**. Imported activity is attributed to matching Supabase users by email, and receipts to the importing admin.

**Importing receipts from Excel:** In **Settings**, choose **Create Excel template**, fill in its `Receipts` sheet, then choose **Import receipts from Excel**. `.xlsx` and `.xls` workbooks are supported. Use one row per receipt line item and repeat receipt/supplier details across all rows for that receipt. Required columns are `receipt_no`, `date`, `supplier_name`, `supplier_tin`, `item_description`, `quantity`, and `unit_price`; `type`, supplier address/contact, project details, discount, VAT, and additional charges are optional. Dates may be Excel date cells or `yyyy-MM-dd` text. Unknown suppliers are created by TIN, and projects are created when both project name and code are provided. Existing receipts with the same supplier TIN and receipt number are skipped on re-import. Excel import transfers receipt details and line items, not receipt scans or attachments.

## Learn more
- [ARCHITECTURE.md](ARCHITECTURE.md): how the code and the database fit together.
- `docs/superpowers/`: the design and the build plan this app was made from.

## Tests
`mvn -q test` runs the unit and UI smoke tests. `bash database/validate.sh` checks the SQL migrations against a throwaway local PostgreSQL (needs PostgreSQL server binaries installed).
