package com.bred.rms.services;

import com.bred.rms.importer.ImportedLineItem;
import com.bred.rms.importer.ReceiptCandidate;
import com.bred.rms.importer.ReceiptCandidateValidator;
import com.bred.rms.importer.ReceiptAmountParser;
import com.bred.rms.importer.ReceiptField;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.AttachmentException;
import com.bred.rms.repositories.AttachmentRules;
import com.bred.rms.repositories.AttachmentStore;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.SavedReceiptReadbackException;
import com.bred.rms.repositories.UserRepository;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs administrator and company checks around AI-import approval and reuses the receipt service. */
public final class ReceiptImportApprovalService {
    public enum ResultType { SAVED, SAVED_WITHOUT_ATTACHMENT, ATTACHMENT_FAILED, FAILED }

    public record Draft(UUID candidateId, ReceiptCandidate candidate, UUID supplierId, Path attachmentSource) { }

    public record Prepared(UUID candidateId, Receipt receipt, Path attachmentSource, String attachmentNotice,
                           String supplierName, List<String> warnings, List<String> possibleDuplicates) {
        public Prepared {
            warnings = List.copyOf(warnings);
            possibleDuplicates = List.copyOf(possibleDuplicates);
        }
    }

    public record SaveResult(UUID candidateId, ResultType type, UUID receiptId, String message) { }

    private record SavedState(Receipt receipt, Attachment pendingAttachment, boolean complete) { }

    private static final Pattern SLASH_DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            dateFormatter("M/d/uuuu"),
            dateFormatter("d/M/uuuu"),
            dateFormatter("d-MMM-uuuu"),
            dateFormatter("d MMM uuuu"),
            dateFormatter("MM-dd-uuuu"));

    private final ReceiptService receipts;
    private final SupplierService suppliers;
    private final UserRepository users;
    private final AttachmentStore attachments;
    private final CurrentUser currentUser;
    private final ReceiptCandidateValidator candidateValidator = new ReceiptCandidateValidator();
    private final Map<UUID, SavedState> savedReceipts = new ConcurrentHashMap<>();
    private final AtomicBoolean approvalInProgress = new AtomicBoolean();

    public ReceiptImportApprovalService(ReceiptService receipts, SupplierService suppliers, UserRepository users,
                                        AttachmentStore attachments, CurrentUser currentUser) {
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.users = users;
        this.attachments = attachments;
        this.currentUser = currentUser;
    }

    /** Revalidates the signed-in profile and returns only suppliers visible under the current RLS context. */
    public List<Supplier> loadCompanySuppliers() {
        requireAdministrator();
        return suppliers.findAll();
    }

    /** Builds immutable save requests and company-scoped duplicate warnings before confirmation. */
    public List<Prepared> preview(List<Draft> drafts) {
        User administrator = requireAdministrator();
        List<Supplier> visibleSuppliers = suppliers.findAll();
        Map<UUID, Supplier> supplierById = new HashMap<>();
        visibleSuppliers.forEach(supplier -> supplierById.put(supplier.id(), supplier));

        List<Receipt> receiptDrafts = new ArrayList<>();
        List<Supplier> selectedSuppliers = new ArrayList<>();
        for (Draft draft : drafts) {
            Supplier supplier = supplierById.get(draft.supplierId());
            if (supplier == null) {
                throw new SecurityException("Choose a supplier available to your company for every receipt.");
            }
            Receipt receipt = toReceipt(draft.candidate(), supplier);
            SavedState prior = savedReceipts.get(draft.candidateId());
            receiptDrafts.add(prior == null ? receipt : withId(receipt, prior.receipt().id()));
            selectedSuppliers.add(supplier);
        }

        List<List<Receipt>> existingMatches = receipts.findPotentialDuplicates(receiptDrafts);
        List<Prepared> prepared = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            Receipt receipt = receiptDrafts.get(i);
            Supplier supplier = selectedSuppliers.get(i);
            List<String> warnings = new ArrayList<>(candidateValidator.validate(draft.candidate()));
            warnings.add("Receipt totals will be calculated using the existing Receipt and ReceiptItem rules.");
            String extractedName = Guard.norm(draft.candidate().value(ReceiptField.SUPPLIER_NAME));
            if (!extractedName.isEmpty() && !extractedName.equals(Guard.norm(supplier.businessName()))) {
                warnings.add("The extracted supplier name differs from the selected company supplier; verify the source document.");
            }
            String extractedTin = Guard.norm(draft.candidate().value(ReceiptField.TIN));
            if (!extractedTin.isEmpty() && !extractedTin.equals(Guard.norm(supplier.tin()))) {
                warnings.add("The extracted TIN differs from the selected company supplier; verify the source document.");
            }
            if (draft.candidate().lineItems().stream().anyMatch(item ->
                    !item.unit().isBlank() || !item.lineTotal().isBlank())) {
                warnings.add("The existing ReceiptItem model does not store units or a separate line total; only description, quantity, and unit price will be saved.");
            }

            String attachmentNotice = "";
            Path attachmentSource = draft.attachmentSource();
            if (attachmentSource == null) {
                attachmentNotice = "The source document is not eligible for attachment under the existing file rules.";
            } else {
                try {
                    AttachmentRules.check(attachmentSource);
                } catch (AttachmentException e) {
                    attachmentNotice = e.getMessage();
                    attachmentSource = null;
                }
            }
            if (!attachmentNotice.isBlank()) warnings.add("Attachment: " + attachmentNotice);

            List<String> duplicates = new ArrayList<>();
            for (Receipt existing : existingMatches.get(i)) {
                duplicates.add(duplicateDescription(existing, supplierById));
            }
            for (int previous = 0; previous < i; previous++) {
                Receipt other = receiptDrafts.get(previous);
                Supplier otherSupplier = selectedSuppliers.get(previous);
                if (samePotentialDuplicate(receipt, supplier, other, otherSupplier)) {
                    duplicates.add("Another receipt in this import session has the same supplier and receipt identifiers or date and total.");
                }
            }
            prepared.add(new Prepared(draft.candidateId(), receipt, attachmentSource, attachmentNotice,
                    supplier.businessName(), warnings, duplicates));
        }

        if (administrator.companyId() == null) {
            throw new SecurityException("Your administrator account is not associated with a company.");
        }
        return List.copyOf(prepared);
    }

    /** Saves each confirmed receipt independently and reports every result without discarding failed drafts. */
    public List<SaveResult> approve(List<Prepared> prepared, Consumer<SaveResult> progress) {
        if (!approvalInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("An AI receipt approval batch is already running.");
        }
        try {
            List<SaveResult> results = new ArrayList<>();
            for (Prepared item : prepared) {
                SavedState prior = savedReceipts.get(item.candidateId());
                SaveResult result;
                if (prior != null && prior.complete()) {
                    result = new SaveResult(item.candidateId(), ResultType.SAVED, prior.receipt().id(),
                            "Already saved; no duplicate submission was made.");
                } else {
                    result = saveOne(item, prior);
                }
                results.add(result);
                if (progress != null) progress.accept(result);
            }
            return List.copyOf(results);
        } finally {
            approvalInProgress.set(false);
        }
    }

    private SaveResult saveOne(Prepared item, SavedState prior) {
        try {
            User administrator = requireAdministrator();
            SavedState state = prior;
            if (state == null) {
                ensureSupplierStillVisible(item.receipt().supplierId());
                Receipt saved;
                try {
                    saved = receipts.saveApprovedImport(item.receipt(), administrator.companyId());
                } catch (SavedReceiptReadbackException e) {
                    Receipt confirmed = withId(item.receipt(), e.receiptId());
                    savedReceipts.put(item.candidateId(), new SavedState(confirmed, null, true));
                    return new SaveResult(item.candidateId(), ResultType.SAVED_WITHOUT_ATTACHMENT,
                            e.receiptId(), "The server saved the receipt, but its details could not be refreshed. "
                            + "No attachment was attempted; verify the receipt in the Receipts screen.");
                }
                state = new SavedState(saved, null, false);
                savedReceipts.put(item.candidateId(), state);
            }

            if (item.attachmentSource() == null) {
                savedReceipts.put(item.candidateId(), new SavedState(state.receipt(), null, true));
                String message = item.attachmentNotice().isBlank()
                        ? "Receipt saved; no source attachment was available."
                        : "Receipt saved, but its source was not attached: " + item.attachmentNotice();
                return new SaveResult(item.candidateId(), ResultType.SAVED_WITHOUT_ATTACHMENT,
                        state.receipt().id(), message);
            }

            Attachment pending = state.pendingAttachment();
            if (pending == null) {
                AttachmentRules.check(item.attachmentSource());
                pending = attachments.upload(state.receipt().id(), item.attachmentSource());
                state = new SavedState(state.receipt(), pending, false);
                savedReceipts.put(item.candidateId(), state);
            }

            User revalidatedAdministrator = requireAdministrator();
            Receipt linked;
            try {
                linked = receipts.saveImportedAttachments(
                        state.receipt().withAttachments(List.of(pending)), revalidatedAdministrator.companyId());
            } catch (SavedReceiptReadbackException e) {
                Receipt confirmed = state.receipt().withAttachments(List.of(pending));
                savedReceipts.put(item.candidateId(), new SavedState(confirmed, null, true));
                return new SaveResult(item.candidateId(), ResultType.SAVED, e.receiptId(),
                        "The server saved the receipt and attachment, but the updated details could not be refreshed.");
            }
            savedReceipts.put(item.candidateId(), new SavedState(linked, null, true));
            return new SaveResult(item.candidateId(), ResultType.SAVED, linked.id(),
                    "Receipt and attachment saved successfully.");
        } catch (ValidationException | RepositoryException | SecurityException | AttachmentException e) {
            SavedState current = savedReceipts.get(item.candidateId());
            if (current != null) {
                return new SaveResult(item.candidateId(), ResultType.ATTACHMENT_FAILED, current.receipt().id(),
                        "Receipt saved, but attachment handling needs attention: " + safeMessage(e));
            }
            return new SaveResult(item.candidateId(), ResultType.FAILED, null, safeMessage(e));
        }
    }

    private User requireAdministrator() {
        User signedIn = currentUser.get();
        if (signedIn == null || signedIn.role() != Role.ADMIN || !signedIn.active()
                || signedIn.accountStatus() != AccountStatus.APPROVED) {
            throw new SecurityException("Only an active administrator can approve imported receipts.");
        }
        if (users == null) {
            throw new SecurityException("Administrator authorization cannot be revalidated. Sign in again or contact support.");
        }
        User fresh = users.findById(signedIn.id())
                .orElseThrow(() -> new SecurityException("Your administrator profile could not be verified."));
        if (fresh.role() != Role.ADMIN || !fresh.active() || fresh.accountStatus() != AccountStatus.APPROVED) {
            throw new SecurityException("Your administrator authorization is no longer valid.");
        }
        if (fresh.companyId() == null) {
            throw new SecurityException("Your administrator account is not associated with a company.");
        }
        return fresh;
    }

    private void ensureSupplierStillVisible(UUID supplierId) {
        if (suppliers.findAll().stream().noneMatch(supplier -> supplier.id().equals(supplierId))) {
            throw new SecurityException("The selected supplier is no longer available to your company.");
        }
    }

    private static Receipt toReceipt(ReceiptCandidate candidate, Supplier supplier) {
        String number = candidate.value(ReceiptField.RECEIPT_NUMBER).trim();
        if (number.isEmpty()) throw new ValidationException("Receipt number is required before approval.");
        LocalDate date = parseDate(candidate.value(ReceiptField.DATE));
        List<ReceiptItem> items = new ArrayList<>();
        for (int i = 0; i < candidate.lineItems().size(); i++) {
            ImportedLineItem item = candidate.lineItems().get(i);
            BigDecimal quantity = parseNumber("Line item " + (i + 1) + " quantity", item.quantity(), false);
            BigDecimal unitPrice = parseNumber("Line item " + (i + 1) + " unit price", item.unitPrice(), false);
            items.add(new ReceiptItem(item.description().trim(), quantity, unitPrice));
        }

        BigDecimal discount = parseNumber("Discount", candidate.value(ReceiptField.DISCOUNT), true);
        BigDecimal vat = parseNumber("Tax", candidate.value(ReceiptField.TAX), true);
        return new Receipt(null, number, date, ReceiptType.OFFICIAL_RECEIPT, supplier.id(), null, items,
                discount, vat, BigDecimal.ZERO, List.of(), null, null, null);
    }

    private static Receipt withId(Receipt receipt, UUID id) {
        return new Receipt(id, receipt.receiptNo(), receipt.date(), receipt.type(), receipt.supplierId(),
                receipt.projectId(), receipt.items(), receipt.discount(), receipt.vat(), receipt.additionalCharges(),
                receipt.attachments(), receipt.encodedBy(), receipt.createdAt(), receipt.modifiedAt());
    }

    private static BigDecimal parseNumber(String label, String raw, boolean blankMeansZero) {
        if (raw == null || raw.isBlank()) {
            if (blankMeansZero) return BigDecimal.ZERO;
            throw new ValidationException(label + " is required.");
        }
        return ReceiptAmountParser.parse(raw).orElseThrow(() ->
                new ValidationException(label
                        + " must use a supported number format, such as 1,234.56. Check decimal and grouping separators."));
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) throw new ValidationException("Receipt date is required.");
        String value = raw.trim();
        Matcher slash = SLASH_DATE.matcher(value);
        if (slash.matches()) {
            int first = Integer.parseInt(slash.group(1));
            int second = Integer.parseInt(slash.group(2));
            if (first <= 12 && second <= 12 && first != second) {
                throw new ValidationException("Receipt date is ambiguous. Edit it to YYYY-MM-DD before approval.");
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try another documented input format.
            }
        }
        throw new ValidationException("Receipt date is invalid. Edit it to YYYY-MM-DD before approval.");
    }

    private static boolean samePotentialDuplicate(Receipt receipt, Supplier supplier,
                                                  Receipt other, Supplier otherSupplier) {
        if (!Guard.norm(supplier.tin()).equals(Guard.norm(otherSupplier.tin()))) return false;
        return Guard.norm(receipt.receiptNo()).equals(Guard.norm(other.receiptNo()))
                || (receipt.date().equals(other.date())
                && receipt.grandTotal().compareTo(other.grandTotal()) == 0);
    }

    private String duplicateDescription(Receipt existing, Map<UUID, Supplier> supplierById) {
        Supplier supplier = supplierById.get(existing.supplierId());
        String name = supplier == null ? "company supplier" : supplier.businessName();
        return "Possible match: " + name + ", receipt " + existing.receiptNo() + ", "
                + existing.date() + ", total " + existing.grandTotal().toPlainString() + ".";
    }

    private static DateTimeFormatter dateFormatter(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.US).withResolverStyle(ResolverStyle.STRICT);
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "The receipt could not be saved." : error.getMessage();
    }
}
