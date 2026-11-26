package com.bred.rms.support;

import com.bred.rms.models.Attachment;
import com.bred.rms.models.Project;
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
import com.bred.rms.repositories.ProjectRepository;
import com.bred.rms.repositories.ReceiptRepository;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.SupplierRepository;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory stand-ins for the repositories. Test-only. */
public final class Fakes {
    private Fakes() { }

    public static User admin() { return new User(UUID.randomUUID(), "Ada Admin", "ada@x.test", Role.ADMIN, true); }
    public static User encoder() { return new User(UUID.randomUUID(), "Eli Encoder", "eli@x.test", Role.ENCODER, true); }

    public static Receipt receipt(String no, Supplier supplier, Project project, LocalDate date, String itemDesc, String price) {
        return new Receipt(UUID.randomUUID(), no, date, ReceiptType.OFFICIAL_RECEIPT, supplier.id(),
                project == null ? null : project.id(),
                List.of(new ReceiptItem(itemDesc, BigDecimal.ONE, new BigDecimal(price))),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null, Instant.EPOCH, Instant.EPOCH);
    }

    public static final class Receipts implements ReceiptRepository {
        public final Map<UUID, Receipt> data = new LinkedHashMap<>();
        public String failApprovedNo;
        public int approvedWrites;
        public int approvedAttachmentWrites;
        public boolean failApprovedAttachmentSave;
        @Override public List<Receipt> findAll() { return new ArrayList<>(data.values()); }
        @Override public Optional<Receipt> findById(UUID id) { return Optional.ofNullable(data.get(id)); }
        @Override public Receipt save(Receipt r) {
            Receipt stored = r.id() == null ? new Receipt(UUID.randomUUID(), r.receiptNo(), r.date(), r.type(), r.supplierId(),
                    r.projectId(), r.items(), r.discount(), r.vat(), r.additionalCharges(), r.attachments(), r.encodedBy(),
                    r.createdAt(), r.modifiedAt()) : r;
            data.put(stored.id(), stored);
            return stored;
        }
        @Override public Receipt saveApprovedImport(Receipt r) {
            if (r.receiptNo().equals(failApprovedNo)) {
                throw new ValidationException("Simulated receipt save failure.");
            }
            approvedWrites++;
            return save(r);
        }
        @Override public Receipt saveApprovedImportAttachments(Receipt r) {
            if (failApprovedAttachmentSave) throw new RepositoryException("Simulated attachment metadata failure.", false);
            approvedAttachmentWrites++;
            return save(r);
        }
        @Override public List<String> delete(UUID id) {
            Receipt r = data.remove(id);
            return r == null ? List.of() : r.attachments().stream().map(Attachment::storagePath).toList();
        }
    }

    public static final class Suppliers implements SupplierRepository {
        public final Map<UUID, Supplier> data = new LinkedHashMap<>();
        public Supplier add(String name, String tin) {
            Supplier s = new Supplier(UUID.randomUUID(), name, tin, "", "");
            data.put(s.id(), s);
            return s;
        }
        @Override public List<Supplier> findAll() { return new ArrayList<>(data.values()); }
        @Override public Optional<Supplier> findById(UUID id) { return Optional.ofNullable(data.get(id)); }
        @Override public Supplier save(Supplier s) {
            Supplier stored = s.id() == null ? new Supplier(UUID.randomUUID(), s.businessName(), s.tin(), s.address(), s.contactNumber()) : s;
            data.put(stored.id(), stored);
            return stored;
        }
        @Override public void delete(UUID id) { data.remove(id); }
    }

    public static final class Projects implements ProjectRepository {
        public final Map<UUID, Project> data = new LinkedHashMap<>();
        public Project add(String name, String code) {
            Project p = new Project(UUID.randomUUID(), name, code, "");
            data.put(p.id(), p);
            return p;
        }
        @Override public List<Project> findAll() { return new ArrayList<>(data.values()); }
        @Override public Optional<Project> findById(UUID id) { return Optional.ofNullable(data.get(id)); }
        @Override public Project save(Project p) {
            Project stored = p.id() == null ? new Project(UUID.randomUUID(), p.name(), p.code(), p.fundSource()) : p;
            data.put(stored.id(), stored);
            return stored;
        }
        @Override public void delete(UUID id) { data.remove(id); }
    }

    public static final class Attachments implements AttachmentStore {
        public final List<Attachment> removed = new ArrayList<>();
        public boolean failRemoval;
        public boolean failUpload;
        public int uploadCalls;
        @Override public Attachment upload(UUID receiptId, Path source) throws AttachmentException {
            AttachmentRules.check(source);
            uploadCalls++;
            if (failUpload) throw new AttachmentException("Simulated attachment upload failure.");
            String filename = source.getFileName().toString();
            return new Attachment(filename, receiptId + "/" + UUID.randomUUID() + "-" + filename);
        }
        @Override public byte[] download(Attachment a) { return new byte[0]; }
        @Override public void remove(Attachment a) throws AttachmentException {
            if (failRemoval) throw new AttachmentException("nope");
            removed.add(a);
        }
    }
}
