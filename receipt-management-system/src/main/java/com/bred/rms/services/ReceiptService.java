package com.bred.rms.services;

import com.bred.rms.models.Attachment;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.AttachmentException;
import com.bred.rms.repositories.AttachmentStore;
import com.bred.rms.repositories.ProjectRepository;
import com.bred.rms.repositories.ReceiptRepository;
import com.bred.rms.repositories.SupplierRepository;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Receipt rules: validation, duplicate check, search, delete (admin only). The database re-checks everything. */
public final class ReceiptService {
    private final ReceiptRepository receipts;
    private final SupplierRepository suppliers;
    private final ProjectRepository projects;
    private final AttachmentStore attachments;
    private final CurrentUser who;

    public ReceiptService(ReceiptRepository receipts, SupplierRepository suppliers, ProjectRepository projects,
                          AttachmentStore attachments, CurrentUser who) {
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.projects = projects;
        this.attachments = attachments;
        this.who = who;
    }

    /** Runs every check {@link #save} would run (rules, supplier exists, duplicate) without saving anything. */
    public void precheck(Receipt r) {
        Guard.requireSignedIn(who);
        validate(r);
        Supplier supplier = suppliers.findById(r.supplierId())
                .orElseThrow(() -> new ValidationException("That supplier no longer exists. Choose another one."));
        checkDuplicate(r, supplier);
    }

    /** Validates and saves a receipt (insert when id is null or unknown, otherwise update). */
    public Receipt save(Receipt r) {
        precheck(r);
        Optional<Receipt> previous = r.id() == null ? Optional.empty() : receipts.findById(r.id());
        Receipt saved = receipts.save(r);
        previous.ifPresent(old -> removeDetached(old.attachments(), saved.attachments()));
        return saved;
    }

    /** Saves a new import only after revalidating the administrator and company context. */
    public Receipt saveApprovedImport(Receipt r, UUID verifiedCompanyId) {
        User user = who.get();
        if (user == null || !user.active() || user.role() != Role.ADMIN
                || user.accountStatus() != com.bred.rms.models.AccountStatus.APPROVED) {
            throw new SecurityException("Only an active company administrator can approve imported receipts.");
        }
        if (user.companyId() == null || !user.companyId().equals(verifiedCompanyId)) {
            throw new SecurityException("Your administrator account has no valid company context. Sign in again or contact an administrator.");
        }
        if (r.id() != null) throw new ValidationException("AI receipt imports can only create new receipts.");
        precheck(r);
        return receipts.saveApprovedImport(r);
    }

    /** Links uploaded import attachments after a server-confirmed receipt insert. */
    public Receipt saveImportedAttachments(Receipt r, UUID verifiedCompanyId) {
        User user = who.get();
        if (user == null || !user.active() || user.role() != Role.ADMIN
                || user.accountStatus() != com.bred.rms.models.AccountStatus.APPROVED) {
            throw new SecurityException("Only an active company administrator can attach files to imported receipts.");
        }
        if (user.companyId() == null || !user.companyId().equals(verifiedCompanyId)) {
            throw new SecurityException("Your administrator account has no valid company context. Sign in again or contact an administrator.");
        }
        if (r.id() == null) throw new ValidationException("Save the imported receipt before linking attachments.");
        precheck(r);
        return receipts.saveApprovedImportAttachments(r);
    }

    /** Company-scoped duplicate candidates; the Supabase repository applies RLS to these reads. */
    public List<List<Receipt>> findPotentialDuplicates(List<Receipt> candidates) {
        Guard.requireSignedIn(who);
        Map<UUID, String> tinBySupplier = new HashMap<>();
        suppliers.findAll().forEach(s -> tinBySupplier.put(s.id(), Guard.norm(s.tin())));
        List<Receipt> existing = receipts.findAll();
        List<List<Receipt>> matches = new java.util.ArrayList<>();
        for (Receipt candidate : candidates) {
            validate(candidate);
            if (!tinBySupplier.containsKey(candidate.supplierId())) {
                throw new ValidationException("That supplier is no longer available. Choose another one.");
            }
            String tin = tinBySupplier.get(candidate.supplierId());
            String no = Guard.norm(candidate.receiptNo());
            matches.add(existing.stream()
                    .filter(other -> candidate.id() == null || !candidate.id().equals(other.id()))
                    .filter(other -> tin.equals(tinBySupplier.get(other.supplierId())))
                    .filter(other -> Guard.norm(other.receiptNo()).equals(no)
                            || (candidate.date().equals(other.date())
                            && candidate.grandTotal().compareTo(other.grandTotal()) == 0))
                    .toList());
        }
        return List.copyOf(matches);
    }

    /** Deletes a receipt and its stored files. Administrators only. */
    public void delete(UUID id) {
        Guard.requireAdmin(who, "Only administrators can delete receipts.");
        for (String path : receipts.delete(id)) {
            try {
                attachments.remove(new Attachment(path, path));
            } catch (AttachmentException ignored) { /* the receipt is gone; a leftover file is harmless */ }
        }
    }

    public Optional<Receipt> find(UUID id) { return receipts.findById(id); }

    /** Returns matching receipts, newest date first. */
    public List<Receipt> search(ReceiptQuery q) {
        Map<UUID, Supplier> sup = new HashMap<>();
        suppliers.findAll().forEach(s -> sup.put(s.id(), s));
        Map<UUID, Project> proj = new HashMap<>();
        projects.findAll().forEach(p -> proj.put(p.id(), p));
        String text = Guard.norm(q.text());
        return receipts.findAll().stream()
                .filter(r -> q.from() == null || !r.date().isBefore(q.from()))
                .filter(r -> q.to() == null || !r.date().isAfter(q.to()))
                .filter(r -> q.supplierId() == null || q.supplierId().equals(r.supplierId()))
                .filter(r -> q.projectId() == null || q.projectId().equals(r.projectId()))
                .filter(r -> text.isEmpty() || matches(text, r, sup.get(r.supplierId()), proj.get(r.projectId())))
                .sorted(Comparator.comparing(Receipt::date).reversed().thenComparing(Receipt::receiptNo))
                .toList();
    }

    private static boolean matches(String text, Receipt r, Supplier s, Project p) {
        if (Guard.norm(r.receiptNo()).contains(text)) return true;
        if (s != null && (Guard.norm(s.tin()).contains(text) || Guard.norm(s.businessName()).contains(text))) return true;
        if (p != null && (Guard.norm(p.name()).contains(text) || Guard.norm(p.code()).contains(text))) return true;
        return r.items().stream().anyMatch(i -> Guard.norm(i.description()).contains(text));
    }

    private void validate(Receipt r) {
        if (Guard.blank(r.receiptNo())) throw new ValidationException("Receipt number is required.");
        if (r.date() == null) throw new ValidationException("Receipt date is required.");
        if (r.supplierId() == null) throw new ValidationException("Choose a supplier.");
        if (r.items().isEmpty()) throw new ValidationException("Add at least one item.");
        for (ReceiptItem i : r.items()) {
            if (Guard.blank(i.description())) throw new ValidationException("Every item needs a description.");
            if (i.quantity() == null || i.quantity().signum() <= 0) throw new ValidationException("Item quantity must be greater than zero.");
            if (i.unitPrice() == null || i.unitPrice().signum() < 0) throw new ValidationException("Item unit price cannot be negative.");
        }
        if (r.discount().signum() < 0 || r.vat().signum() < 0 || r.additionalCharges().signum() < 0) {
            throw new ValidationException("Discount, VAT and additional charges cannot be negative.");
        }
        BigDecimal subtotal = r.subtotal();
        if (r.discount().compareTo(subtotal) > 0) throw new ValidationException("Discount cannot be more than the subtotal.");
    }

    private void checkDuplicate(Receipt r, Supplier supplier) {
        if (who.get().role() == com.bred.rms.models.Role.CREATOR) return;
        String tin = Guard.norm(supplier.tin());
        String no = Guard.norm(r.receiptNo());
        Map<UUID, String> tinBySupplier = new HashMap<>();
        suppliers.findAll().forEach(s -> tinBySupplier.put(s.id(), Guard.norm(s.tin())));
        for (Receipt other : receipts.findAll()) {
            if (r.id() != null && r.id().equals(other.id())) continue;
            if (Guard.norm(other.receiptNo()).equals(no) && tin.equals(tinBySupplier.get(other.supplierId()))) {
                throw new ValidationException("Duplicate receipt: " + r.receiptNo().trim() + " already exists for this supplier.");
            }
        }
    }

    private void removeDetached(List<Attachment> before, List<Attachment> after) {
        for (Attachment a : before) {
            if (after.stream().noneMatch(n -> n.storagePath().equals(a.storagePath()))) {
                try {
                    attachments.remove(a);
                } catch (AttachmentException ignored) { /* best effort: encoders may not be allowed to delete files */ }
            }
        }
    }
}
