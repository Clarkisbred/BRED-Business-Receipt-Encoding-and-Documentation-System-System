package com.bred.rms.ui.construction;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Company-scoped CRUD workspace for construction and compliance records. */
public final class ConstructionPanel extends JPanel implements RefreshablePanel {
    private record Field(String label, String key, FieldType type) {}
    private enum FieldType { TEXT, NUMBER, DATE, BOOLEAN, JSON }
    private record TabSpec(String title, String table, List<Field> fields) {}

    private static final List<TabSpec> TABS = List.of(
            spec("Licenses", "company_licenses",
                    field("License type", "license_type"), field("License number", "license_number"),
                    field("Category", "category"), field("Classification", "classification"),
                    field("Valid from", "valid_from", FieldType.DATE), field("Valid to", "valid_to", FieldType.DATE)),
            spec("Permits", "company_permits",
                    field("Permit type", "permit_type"), field("Reference number", "reference_number"),
                    field("Valid from", "valid_from", FieldType.DATE), field("Valid to", "valid_to", FieldType.DATE)),
            spec("Bids", "bids",
                    field("Bid reference", "bid_reference"), field("Agency or client", "agency_or_client"),
                    field("Project description", "project_description"),
                    field("ABC amount", "abc_amount", FieldType.NUMBER), field("Bid amount", "bid_amount", FieldType.NUMBER),
                    field("Bid opening", "bid_opening_date", FieldType.DATE), field("Result", "result")),
            spec("Securities", "project_securities",
                    field("Project UUID", "project_id"), field("Security type", "security_type"),
                    field("Amount", "amount", FieldType.NUMBER), field("Issuer", "issuer"),
                    field("Reference", "reference_number"), field("Valid to", "valid_to", FieldType.DATE),
                    field("Status", "status")),
            spec("Insurance", "project_insurance",
                    field("Project UUID", "project_id"), field("Insurance type", "insurance_type"),
                    field("Amount", "amount", FieldType.NUMBER), field("Issuer", "issuer"),
                    field("Reference", "reference_number"), field("Valid to", "valid_to", FieldType.DATE),
                    field("Status", "status")),
            spec("Time extensions", "project_time_extensions",
                    field("Project UUID", "project_id"), field("Days requested", "days_requested", FieldType.NUMBER),
                    field("Days approved", "days_approved", FieldType.NUMBER),
                    field("Approval status", "approval_status"), field("Approval date", "approval_date", FieldType.DATE),
                    field("Original completion", "original_completion_date", FieldType.DATE),
                    field("Revised completion", "revised_completion_date", FieldType.DATE), field("Reason", "reason")),
            spec("Material tests", "project_material_tests",
                    field("Project UUID", "project_id"), field("Test type", "test_type"),
                    field("Sample reference", "sample_reference"), field("Test date", "test_date", FieldType.DATE),
                    field("Location", "location"), field("Result", "result"),
                    field("Pass/fail", "pass_fail"), field("Laboratory", "laboratory")),
            spec("Warranties", "project_warranties",
                    field("Project UUID", "project_id"), field("Warranty type", "warranty_type"),
                    field("Start date", "start_date", FieldType.DATE), field("End date", "end_date", FieldType.DATE)),
            spec("Subcontracts", "subcontracts",
                    field("Project UUID", "project_id"), field("Supplier UUID", "supplier_id"),
                    field("Scope", "scope"), field("Subcontract amount", "subcontract_amount", FieldType.NUMBER),
                    field("Start date", "start_date", FieldType.DATE), field("End date", "end_date", FieldType.DATE),
                    field("Status", "status")),
            spec("Equipment", "equipment",
                    field("Name", "name"), field("Type", "equipment_type"), field("Code", "code"),
                    field("Ownership", "ownership"), field("Owner supplier UUID", "owner_supplier_id"),
                    field("Rate", "rate", FieldType.NUMBER), field("Rate unit", "rate_unit"), field("Status", "status")),
            spec("Cash advances", "cash_advances",
                    field("Project UUID", "project_id"), field("Issued to", "issued_to"),
                    field("Amount", "amount", FieldType.NUMBER), field("Issued date", "issued_date", FieldType.DATE),
                    field("Purpose", "purpose")),
            spec("Safety", "safety_records",
                    field("Project UUID", "project_id"), field("Record date", "record_date", FieldType.DATE),
                    field("Location", "location"), field("Description", "description"),
                    field("Severity", "severity"), field("Corrective action", "corrective_action"),
                    field("Status", "status")),
            spec("Tax calendar", "tax_calendar_items",
                    field("Name", "name"), field("Frequency", "frequency"),
                    field("Due date", "due_date", FieldType.DATE), field("Status", "status")),
            spec("Retentions", "retention_ledger",
                    field("Project UUID", "project_id"), field("Billing UUID", "billing_id"),
                    field("Held amount", "held_amount", FieldType.NUMBER),
                    field("Released amount", "released_amount", FieldType.NUMBER),
                    field("Effective date", "effective_date", FieldType.DATE), field("Reason", "reason")),
            spec("Contracts", "project_contracts",
                    field("Project UUID", "project_id"), field("Contract number", "contract_number"),
                    field("Contract name", "contract_name"), field("Client name", "client_name"),
                    field("Contract amount", "contract_amount", FieldType.NUMBER),
                    field("Notice of Award", "notice_of_award_date", FieldType.DATE),
                    field("Contract signing", "contract_signing_date", FieldType.DATE),
                    field("Notice to Proceed", "notice_to_proceed_date", FieldType.DATE),
                    field("Original completion", "original_completion_date", FieldType.DATE),
                    field("Revised completion", "revised_completion_date", FieldType.DATE),
                    field("Duration days", "contract_duration_days", FieldType.NUMBER),
                    field("Status", "status"), field("Approved", "approved", FieldType.BOOLEAN)),
            spec("BOQ items", "project_boq_items",
                    field("Project UUID", "project_id"), field("Contract UUID", "contract_id"),
                    field("Item code", "item_code"), field("Description", "description"),
                    field("Unit", "unit"), field("Quantity", "quantity", FieldType.NUMBER),
                    field("Unit price", "unit_price", FieldType.NUMBER),
                    field("Estimated cost", "estimated_cost", FieldType.NUMBER),
                    field("Actual cost", "actual_cost", FieldType.NUMBER),
                    field("Parent item UUID", "parent_item_id"), field("Sort order", "sort_order", FieldType.NUMBER)),
            spec("Variation orders", "project_variation_orders",
                    field("Project UUID", "project_id"), field("Contract UUID", "contract_id"),
                    field("Variation number", "variation_number"), field("Description", "description"),
                    field("Reason", "reason"), field("Amount effect", "amount_effect", FieldType.NUMBER),
                    field("Duration effect", "duration_effect_days", FieldType.NUMBER),
                    field("Status", "status"), field("Requested date", "requested_date", FieldType.DATE),
                    field("Approval date", "approval_date", FieldType.DATE),
                    field("Approved by UUID", "approved_by")),
            spec("Variation items", "project_variation_items",
                    field("Variation UUID", "variation_id"), field("BOQ item UUID", "boq_item_id"),
                    field("Description", "description"), field("Quantity", "quantity", FieldType.NUMBER),
                    field("Unit", "unit"), field("Unit price", "unit_price", FieldType.NUMBER),
                    field("Amount", "amount", FieldType.NUMBER)),
            spec("Cost estimates", "cost_estimates",
                    field("Bid UUID", "bid_id"), field("Project UUID", "project_id"),
                    field("Estimate name", "name"), field("Original budget", "original_budget", FieldType.NUMBER),
                    field("Approved", "approved", FieldType.BOOLEAN)),
            spec("Estimate items", "cost_estimate_items",
                    field("Estimate UUID", "estimate_id"), field("BOQ code", "boq_code"),
                    field("Description", "description"), field("Quantity", "quantity", FieldType.NUMBER),
                    field("Unit", "unit"), field("Materials", "materials", FieldType.NUMBER),
                    field("Labor", "labor", FieldType.NUMBER), field("Equipment", "equipment", FieldType.NUMBER),
                    field("Overhead", "overhead", FieldType.NUMBER),
                    field("Contingency", "contingency", FieldType.NUMBER),
                    field("Profit", "profit", FieldType.NUMBER), field("Taxes", "taxes", FieldType.NUMBER)),
            spec("Contract amendments", "project_contract_amendments",
                    field("Project UUID", "project_id"), field("Amendment number", "amendment_number"),
                    field("Description", "description"), field("Amount effect", "amount_effect", FieldType.NUMBER),
                    field("Duration effect", "duration_effect_days", FieldType.NUMBER),
                    field("Approval status", "approval_status")),
            spec("Price adjustments", "project_price_adjustments",
                    field("Project UUID", "project_id"), field("Formula", "formula"),
                    field("Effective from", "effective_from", FieldType.DATE),
                    field("Effective to", "effective_to", FieldType.DATE),
                    field("Approval status", "approval_status")),
            spec("Activities", "project_activities",
                    field("Project UUID", "project_id"), field("Activity name", "activity_name"),
                    field("Planned start", "planned_start", FieldType.DATE),
                    field("Planned finish", "planned_finish", FieldType.DATE),
                    field("Planned progress", "planned_progress", FieldType.NUMBER),
                    field("Actual progress", "actual_progress", FieldType.NUMBER),
                    field("Approved accomplishment", "approved_accomplishment", FieldType.BOOLEAN)),
            spec("Accomplishments", "project_accomplishments",
                    field("Project UUID", "project_id"), field("Activity UUID", "activity_id"),
                    field("BOQ item UUID", "boq_item_id"), field("Accomplishment date", "accomplishment_date", FieldType.DATE),
                    field("Period start", "period_start", FieldType.DATE), field("Period end", "period_end", FieldType.DATE),
                    field("Quantity", "quantity", FieldType.NUMBER), field("Progress percent", "progress_percent", FieldType.NUMBER),
                    field("Approved", "approved", FieldType.BOOLEAN), field("Remarks", "remarks")),
            spec("Milestones", "project_milestones",
                    field("Project UUID", "project_id"), field("Milestone name", "milestone_name"),
                    field("Description", "description"), field("Due date", "due_date", FieldType.DATE),
                    field("Completed date", "completed_date", FieldType.DATE), field("Status", "status"),
                    field("Planned progress", "planned_progress", FieldType.NUMBER),
                    field("Actual progress", "actual_progress", FieldType.NUMBER),
                    field("Approved accomplishment", "approved_accomplishment", FieldType.BOOLEAN)),
            spec("Schedule snapshots", "project_schedule_snapshots",
                    field("Project UUID", "project_id"), field("Snapshot date", "snapshot_date", FieldType.DATE),
                    field("Planned progress", "planned_progress", FieldType.NUMBER),
                    field("Actual progress", "actual_progress", FieldType.NUMBER),
                    field("Approved accomplishment", "approved_accomplishment_percent", FieldType.NUMBER)),
            spec("Billings", "project_billings",
                    field("Project UUID", "project_id"), field("Contract UUID", "contract_id"),
                    field("Billing number", "billing_number"), field("Billing date", "billing_date", FieldType.DATE),
                    field("Period start", "period_start", FieldType.DATE), field("Period end", "period_end", FieldType.DATE),
                    field("Due date", "due_date", FieldType.DATE), field("Gross amount", "gross_amount", FieldType.NUMBER),
                    field("Previous billed", "previous_billed", FieldType.NUMBER),
                    field("Current accomplishment", "current_accomplishment", FieldType.NUMBER),
                    field("Retention rate", "retention_rate", FieldType.NUMBER),
                    field("Retention amount", "retention_amount", FieldType.NUMBER),
                    field("Advance recovery", "advance_recovery", FieldType.NUMBER),
                    field("Liquidated damages", "liquidated_damages", FieldType.NUMBER),
                    field("Withholding", "withholding_amount", FieldType.NUMBER),
                    field("Other deductions", "other_deductions", FieldType.NUMBER),
                    field("Net amount", "net_amount", FieldType.NUMBER),
                    field("Collected amount", "collected_amount", FieldType.NUMBER), field("Status", "status")),
            spec("Billing lines", "project_billing_lines",
                    field("Billing UUID", "billing_id"), field("BOQ item UUID", "boq_item_id"),
                    field("Description", "description"), field("Previous quantity", "previous_quantity", FieldType.NUMBER),
                    field("Current quantity", "current_quantity", FieldType.NUMBER),
                    field("Cumulative quantity", "cumulative_quantity", FieldType.NUMBER),
                    field("Unit", "unit"), field("Unit price", "unit_price", FieldType.NUMBER),
                    field("Amount", "amount", FieldType.NUMBER)),
            spec("Billing deductions", "project_billing_deductions",
                    field("Billing UUID", "billing_id"), field("Deduction type", "deduction_type"),
                    field("Description", "description"), field("Amount", "amount", FieldType.NUMBER),
                    field("Approved", "approved", FieldType.BOOLEAN), field("Approval reason", "approval_reason")),
            spec("Collections", "project_billing_collections",
                    field("Billing UUID", "billing_id"), field("Collection date", "collection_date", FieldType.DATE),
                    field("Amount", "amount", FieldType.NUMBER),
                    field("Withholding amount", "withholding_amount", FieldType.NUMBER),
                    field("Reference number", "reference_number"), field("Bank reference", "bank_reference"),
                    field("Remarks", "remarks")),
            spec("Billing attachments", "project_billing_attachments",
                    field("Billing UUID", "billing_id"), field("Attachment name", "attachment_name"),
                    field("Document path", "document_path"), field("Attachment type", "attachment_type"),
                    field("Required", "is_required", FieldType.BOOLEAN)),
            spec("Project expenses", "project_expense_ledger",
                    field("Project UUID", "project_id"), field("Receipt UUID", "receipt_id"),
                    field("Supplier UUID", "supplier_id"), field("Expense category", "expense_category"),
                    field("Expense date", "expense_date", FieldType.DATE), field("Description", "description"),
                    field("Amount", "amount", FieldType.NUMBER), field("Approved", "approved", FieldType.BOOLEAN)),
            spec("Payables", "project_payables",
                    field("Project UUID", "project_id"), field("Supplier UUID", "supplier_id"),
                    field("Invoice number", "invoice_number"), field("Invoice date", "invoice_date", FieldType.DATE),
                    field("Due date", "due_date", FieldType.DATE), field("Description", "description"),
                    field("Gross amount", "gross_amount", FieldType.NUMBER),
                    field("Withholding", "withholding_amount", FieldType.NUMBER),
                    field("Paid amount", "paid_amount", FieldType.NUMBER),
                    field("Status", "status"), field("Approved", "approved", FieldType.BOOLEAN)),
            spec("Payments", "project_payments",
                    field("Project UUID", "project_id"), field("Payable UUID", "payable_id"),
                    field("Supplier UUID", "supplier_id"), field("Payment date", "payment_date", FieldType.DATE),
                    field("Amount", "amount", FieldType.NUMBER),
                    field("Withholding", "withholding_amount", FieldType.NUMBER),
                    field("Payment method", "payment_method"), field("Reference number", "reference_number"),
                    field("Remarks", "remarks"), field("Approved", "approved", FieldType.BOOLEAN)),
            spec("Cash movements", "project_cash_movements",
                    field("Project UUID", "project_id"), field("Movement date", "movement_date", FieldType.DATE),
                    field("Movement type", "movement_type"), field("Category", "category"),
                    field("Amount", "amount", FieldType.NUMBER), field("Reference number", "reference_number"),
                    field("Remarks", "remarks"), field("Approved", "approved", FieldType.BOOLEAN)),
            spec("P&L snapshots", "project_pnl_snapshots",
                    field("Project UUID", "project_id"), field("Period start", "period_start", FieldType.DATE),
                    field("Period end", "period_end", FieldType.DATE),
                    field("Revenue", "revenue", FieldType.NUMBER),
                    field("Approved expenses", "approved_expenses", FieldType.NUMBER),
                    field("Profit", "profit", FieldType.NUMBER),
                    field("Margin percent", "margin_percent", FieldType.NUMBER),
                    field("Status", "status"), field("Approved", "approved", FieldType.BOOLEAN)),
            spec("Inspections", "project_inspections",
                    field("Project UUID", "project_id"), field("Request date", "request_date", FieldType.DATE),
                    field("Result", "result"), field("Deficiency notice", "deficiency_notice"),
                    field("Corrective action", "corrective_action"),
                    field("Closure date", "closure_date", FieldType.DATE)),
            spec("Project documents", "project_documents",
                    field("Project UUID", "project_id"), field("Category", "category"),
                    field("Document name", "document_name"), field("Version", "version"),
                    field("Document date", "document_date", FieldType.DATE)),
            spec("Closeout", "project_closeout_records",
                    field("Project UUID", "project_id"), field("Certificate type", "certificate_type"),
                    field("Certificate date", "certificate_date", FieldType.DATE),
                    field("Signatories", "signatories")),
            spec("Advance recoveries", "advance_payment_recoveries",
                    field("Project UUID", "project_id"), field("Advance amount", "advance_amount", FieldType.NUMBER),
                    field("Recovery method", "recovery_method"),
                    field("Recovered amount", "recovered_amount", FieldType.NUMBER)),
            spec("CWT received", "withholding_tax_received",
                    field("Billing UUID", "billing_id"), field("Client name", "client_name"),
                    field("Tax base", "tax_base", FieldType.NUMBER), field("Rate", "rate", FieldType.NUMBER),
                    field("Amount withheld", "amount_withheld", FieldType.NUMBER),
                    field("Period start", "period_start", FieldType.DATE),
                    field("Certificate reference", "certificate_reference"),
                    field("Status", "status"), field("Date received", "date_received", FieldType.DATE)),
            spec("CWT issued", "withholding_tax_issued",
                    field("Supplier UUID", "supplier_id"), field("Tax base", "tax_base", FieldType.NUMBER),
                    field("Rate", "rate", FieldType.NUMBER),
                    field("Amount withheld", "amount_withheld", FieldType.NUMBER),
                    field("Period start", "period_start", FieldType.DATE),
                    field("Certificate reference", "certificate_reference"), field("Status", "status")),
            spec("Petty cash funds", "petty_cash_funds",
                    field("Project UUID", "project_id"), field("Fund name", "fund_name"),
                    field("Opening balance", "opening_balance", FieldType.NUMBER)),
            spec("Petty cash transactions", "petty_cash_transactions",
                    field("Fund UUID", "fund_id"), field("Transaction type", "transaction_type"),
                    field("Amount", "amount", FieldType.NUMBER),
                    field("Transaction date", "transaction_date", FieldType.DATE),
                    field("Receipt UUID", "receipt_id"), field("Notes", "notes")),
            spec("Safety programs", "safety_programs",
                    field("Project UUID", "project_id"), field("Program reference", "program_reference"),
                    field("Approval date", "approval_date", FieldType.DATE),
                    field("Safety officer UUID", "safety_officer_id")),
            spec("Toolbox meetings", "safety_toolbox_meetings",
                    field("Project UUID", "project_id"), field("Meeting date", "meeting_date", FieldType.DATE),
                    field("Topic", "topic"), field("Attendees", "attendees")),
            spec("Offline queue", "offline_queue",
                    field("Idempotency key", "idempotency_key"), field("Entity type", "entity_type"),
                    field("Payload JSON", "payload", FieldType.JSON), field("Status", "status"),
                    field("Queued at", "queued_at", FieldType.DATE),
                    field("Synced at", "synced_at", FieldType.DATE))
    );

    private final AppContext ctx;
    private final JTabbedPane tabs = new JTabbedPane();
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "license_type", "license_number", "valid_to", "permit_type", "reference_number",
            "bid_reference", "agency_or_client", "project_id", "security_type", "insurance_type",
            "days_requested", "original_completion_date", "test_type", "test_date", "pass_fail",
            "warranty_type", "start_date", "end_date", "supplier_id", "scope",
            "subcontract_amount", "name", "issued_to", "amount", "issued_date", "record_date",
            "description", "severity", "due_date", "held_amount", "effective_date",
            "estimate_id", "activity_name", "request_date", "document_name",
            "category", "certificate_type", "recovery_method", "client_name", "tax_base",
            "rate", "amount_withheld", "period_start", "fund_id", "transaction_type",
            "transaction_date", "program_reference", "meeting_date", "topic",
            "idempotency_key", "entity_type", "payload");

    public ConstructionPanel(AppContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        for (TabSpec spec : TABS) tabs.addTab(spec.title(), new CrudTab(ctx, spec));
        add(tabs, BorderLayout.CENTER);
    }

    @Override
    public void refreshData() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof RefreshablePanel panel) panel.refreshData();
        }
    }

    private static TabSpec spec(String title, String table, Field... fields) {
        return new TabSpec(title, table, List.of(fields));
    }

    private static Field field(String label, String key) {
        return new Field(label, key, FieldType.TEXT);
    }

    private static Field field(String label, String key, FieldType type) {
        return new Field(label, key, type);
    }

    private static final class CrudTab extends JPanel implements RefreshablePanel {
        private final AppContext ctx;
        private final TabSpec spec;
        private final DefaultTableModel model;
        private final JTable table;
        private final JLabel status = new JLabel(" ");
        private final JButton edit = new JButton("Edit");
        private final JButton delete = new JButton("Delete");
        private final List<JsonNode> rows = new ArrayList<>();

        CrudTab(AppContext ctx, TabSpec spec) {
            super(new BorderLayout(0, 8));
            this.ctx = ctx;
            this.spec = spec;
            List<String> columns = new ArrayList<>();
            columns.add("ID");
            for (Field field : spec.fields()) columns.add(field.label());
            model = new DefaultTableModel(columns.toArray(String[]::new), 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            table = new JTable(model);
            table.setRowHeight(28);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.getTableHeader().setReorderingAllowed(false);

            JButton add = new JButton("Add");
            add.addActionListener(e -> openForm(null));
            edit.addActionListener(e -> {
                JsonNode row = selected();
                if (row != null) openForm(row);
            });
            delete.addActionListener(e -> {
                JsonNode row = selected();
                if (row != null) confirmDelete(row);
            });
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            bar.add(add);
            bar.add(edit);
            User user = ctx.currentUser.get();
            if (user != null && user.role().canManageCompany()) bar.add(delete);
            add(bar, BorderLayout.NORTH);
            add(new StyledScrollPane(table), BorderLayout.CENTER);
            status.setForeground(RmsColors.SECONDARY_TEXT);
            status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
            add(status, BorderLayout.SOUTH);
            table.getSelectionModel().addListSelectionListener(e -> updateButtons());
            updateButtons();
            refreshData();
        }

        @Override
        public void refreshData() {
            if (ctx.constructionData == null) {
                status.setText("Construction CRUD requires the Supabase-backed app.");
                return;
            }
            BackgroundTask.run(() -> ctx.constructionData.rows(spec.table()), this::fill,
                    e -> status.setText(UiErrors.friendly(e)));
        }

        private void fill(List<JsonNode> loaded) {
            rows.clear();
            rows.addAll(loaded);
            model.setRowCount(0);
            for (JsonNode row : rows) {
                List<Object> values = new ArrayList<>();
                values.add(text(row, "id"));
                for (Field field : spec.fields()) values.add(text(row, field.key()));
                model.addRow(values.toArray());
            }
            status.setText(rows.size() + " record(s)");
            updateButtons();
        }

        private JsonNode selected() {
            int row = table.getSelectedRow();
            return row < 0 || row >= rows.size() ? null : rows.get(row);
        }

        private void updateButtons() {
            boolean has = selected() != null;
            edit.setEnabled(has);
            delete.setEnabled(has);
        }

        private void openForm(JsonNode existing) {
            new CrudDialog(SwingUtilities.getWindowAncestor(this),
                    spec.title(), spec.fields(), existing,
                    (id, values) -> ctx.constructionData.saveRow(spec.table(), id, values),
                    this::refreshData).setVisible(true);
        }

        private void confirmDelete(JsonNode row) {
            UUID id = uuid(row, "id");
            int answer = JOptionPane.showConfirmDialog(this,
                    "Delete this " + spec.title().toLowerCase() + " record?",
                    "Delete record", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            BackgroundTask.run(() -> {
                ctx.constructionData.deleteRow(spec.table(), id);
                return true;
            }, ok -> refreshData(), e ->
                    JOptionPane.showMessageDialog(this, UiErrors.friendly(e),
                            "Cannot delete record", JOptionPane.WARNING_MESSAGE));
        }

        private static String text(JsonNode row, String key) {
            JsonNode value = row.get(key);
            return value == null || value.isNull() ? "" : value.asText();
        }

        private static UUID uuid(JsonNode row, String key) {
            String value = text(row, key);
            return value.isBlank() ? null : UUID.fromString(value);
        }
    }

    private static final class CrudDialog extends JDialog {
        interface Saver {
            void save(UUID id, Map<String, Object> values) throws Exception;
        }

        private final JLabel error = new JLabel(" ");
        private final JButton save = new JButton("Save");

        CrudDialog(java.awt.Window owner, String title, List<Field> fields, JsonNode existing,
                   Saver saver, Runnable onSaved) {
            super(owner, title, ModalityType.APPLICATION_MODAL);
            Map<String, JTextField> inputs = new LinkedHashMap<>();
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
            int row = 0;
            for (Field field : fields) {
                GridBagConstraints c = new GridBagConstraints();
                c.gridy = row++;
                c.insets = new Insets(4, 4, 4, 8);
                c.anchor = GridBagConstraints.WEST;
                form.add(new JLabel(field.label()), c);
                JTextField input = new JTextField(existingValue(existing, field.key()), 28);
                inputs.put(field.key(), input);
                c.gridx = 1;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                form.add(input, c);
            }
            error.setForeground(RmsColors.DANGER);
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());
            save.addActionListener(e -> submit(fields, inputs, existing, saver, onSaved));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            buttons.add(cancel);
            buttons.add(save);
            JPanel south = new JPanel(new BorderLayout());
            south.add(error, BorderLayout.NORTH);
            south.add(buttons, BorderLayout.SOUTH);
            add(form, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);
            pack();
            setLocationRelativeTo(owner);
        }

        private void submit(List<Field> fields, Map<String, JTextField> inputs, JsonNode existing,
                            Saver saver, Runnable onSaved) {
            Map<String, Object> values = new LinkedHashMap<>();
            try {
                for (Field field : fields) {
                    String raw = inputs.get(field.key()).getText().trim();
                    if (REQUIRED_FIELDS.contains(field.key()) && raw.isEmpty()) {
                        throw new IllegalArgumentException(field.label() + " is required.");
                    }
                    if (raw.isEmpty()) continue;
                    if (field.key().endsWith("_id") && !raw.isEmpty()) {
                        UUID.fromString(raw);
                    }
                    switch (field.type()) {
                        case NUMBER -> values.put(field.key(), new BigDecimal(raw));
                        case DATE -> values.put(field.key(), LocalDate.parse(raw).toString());
                        case BOOLEAN -> values.put(field.key(), Boolean.parseBoolean(raw));
                        case JSON -> values.put(field.key(), new ObjectMapper().readTree(raw));
                        case TEXT -> values.put(field.key(), raw);
                    }
                }
            } catch (IllegalArgumentException error) {
                this.error.setText(error.getMessage() == null
                        ? "Enter a valid UUID, number, or ISO date (YYYY-MM-DD)."
                        : error.getMessage());
                return;
            } catch (Exception error) {
                this.error.setText("Enter valid JSON, numeric, or ISO date values.");
                return;
            }
            UUID id = existing == null ? null : uuid(existing, "id");
            save.setEnabled(false);
            error.setText(" ");
            BackgroundTask.run(() -> {
                saver.save(id, values);
                return true;
            }, ok -> {
                onSaved.run();
                dispose();
            }, e -> {
                save.setEnabled(true);
                error.setText(UiErrors.friendly(e));
            });
        }

        private static String existingValue(JsonNode existing, String key) {
            if (existing == null) return "";
            JsonNode value = existing.get(key);
            return value == null || value.isNull() ? "" : value.asText();
        }

        private static UUID uuid(JsonNode row, String key) {
            String value = existingValue(row, key);
            return value.isBlank() ? null : UUID.fromString(value);
        }
    }
}
