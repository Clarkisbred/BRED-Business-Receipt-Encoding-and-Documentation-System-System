package com.bred.rms.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bred.rms.models.ActivityEntry;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.models.Company;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.ActivityLogRepository;
import com.bred.rms.repositories.CompanyRepository;
import com.bred.rms.repositories.MessageRepository;
import com.bred.rms.repositories.ProjectRepository;
import com.bred.rms.repositories.ReceiptRepository;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.SupplierRepository;
import com.bred.rms.repositories.UserRepository;
import com.bred.rms.services.CurrentUser;
import com.bred.rms.supabase.SupabaseHttp;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Keeps all data in one JSON file ({@code data.json}) inside a folder on this computer. Every change is written
 * straight away (via a temporary file, with the previous version kept as {@code data.json.bak}). Passwords are stored
 * as salted PBKDF2 hashes, never as text.
 */
public final class LocalDatabase {
    /** Email and password of the account created on first run. */
    public static final String DEFAULT_ADMIN_EMAIL = "admin@rms.local";
    static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Account(User user, String salt, String hash) { }

    private static final class State {
        final List<Account> accounts = new ArrayList<>();
        final List<Company> companies = new ArrayList<>();
        final List<Supplier> suppliers = new ArrayList<>();
        final List<Project> projects = new ArrayList<>();
        final List<Receipt> receipts = new ArrayList<>();
        final List<ActivityEntry> activity = new ArrayList<>();
        final List<ChatMessage> messages = new ArrayList<>();
    }

    private final Path dir;
    private final Path file;
    private State state;
    private CurrentUser who = () -> null;
    private boolean companyAssignmentsMigrated;

    private LocalDatabase(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("data.json");
    }

    /** Opens (or creates) the database in {@code dir}. A damaged file raises an {@link IllegalStateException} and is left untouched. */
    public static LocalDatabase open(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Can't create the data folder " + dir + ": " + e.getMessage());
        }
        LocalDatabase db = new LocalDatabase(dir);
        db.state = db.load();
        boolean promotedCreator = false;
        for (int i = 0; i < db.state.accounts.size(); i++) {
            Account account = db.state.accounts.get(i);
            User user = account.user();
            if ("clarkhilario.bread@gmail.com".equalsIgnoreCase(norm(user.email()))
                    && user.role() != Role.CREATOR) {
                db.state.accounts.set(i, new Account(
                        new User(user.id(), user.fullName(), user.email(), Role.CREATOR, user.active(),
                                user.accountStatus(), user.createdAt(), "", null),
                        account.salt(), account.hash()));
                promotedCreator = true;
            }
        }
        if (promotedCreator || db.companyAssignmentsMigrated) db.write(s -> null);
        db.companyAssignmentsMigrated = false;
        if (db.state.accounts.isEmpty()) {
            db.write(s -> {
                Company company = s.companies.get(0);
                User admin = new User(UUID.randomUUID(), "Administrator", DEFAULT_ADMIN_EMAIL, Role.ADMIN, true,
                        AccountStatus.APPROVED, Instant.EPOCH, company.name(), company.id());
                s.accounts.add(hashed(admin, DEFAULT_ADMIN_PASSWORD));
                return null;
            });
        }
        return db;
    }

    /** The folder holding data.json and the attachment files. */
    public Path directory() { return dir; }

    /** Tells the database who is signed in (used to stamp receipts and the activity log). */
    public void bindCurrentUser(CurrentUser current) { this.who = current; }

    // ---------------------------------------------------------------- accounts

    /** Returns the user when email and password match (any active state); empty otherwise. */
    public synchronized Optional<User> authenticate(String email, char[] password) {
        String wanted = norm(email);
        for (Account a : state.accounts) {
            if (norm(a.user().email()).equals(wanted)) {
                return verify(a, password) ? Optional.of(a.user()) : Optional.empty();
            }
        }
        // Spend similar time for unknown emails so the answer does not reveal which emails exist.
        hash(password, new byte[16]);
        return Optional.empty();
    }

    /** Replaces a user's password. */
    public synchronized void setPassword(UUID userId, String newPassword) {
        write(s -> {
            for (int i = 0; i < s.accounts.size(); i++) {
                User u = s.accounts.get(i).user();
                if (u.id().equals(userId)) s.accounts.set(i, hashed(u, newPassword));
            }
            return null;
        });
    }

    /** True while the first-run administrator still has the default password. */
    public synchronized boolean defaultAdminPasswordInUse() {
        for (Account a : state.accounts) {
            if (norm(a.user().email()).equals(DEFAULT_ADMIN_EMAIL) && verify(a, DEFAULT_ADMIN_PASSWORD.toCharArray())) return true;
        }
        return false;
    }

    private static Account hashed(User user, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return new Account(user, Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash(password.toCharArray(), salt)));
    }

    private static boolean verify(Account a, char[] password) {
        byte[] expected = Base64.getDecoder().decode(a.hash());
        byte[] actual = hash(password, Base64.getDecoder().decode(a.salt()));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] hash(char[] password, byte[] salt) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, ITERATIONS, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is not available on this computer.", e);
        }
    }

    // ---------------------------------------------------------------- repositories

    /** User accounts. */
    public UserRepository users() {
        return new UserRepository() {
            @Override public List<User> findAll() { return read(s -> s.accounts.stream().map(Account::user).toList()); }

            @Override public List<User> findMessagingContacts() {
                return read(s -> s.accounts.stream().map(Account::user).filter(User::active)
                        .map(u -> new User(u.id(), u.fullName(), "", u.role(), u.active(),
                                u.accountStatus(), u.createdAt(), u.companyName(), u.companyId()))
                        .toList());
            }

            @Override public Optional<User> findById(UUID id) {
                return read(s -> s.accounts.stream().map(Account::user).filter(u -> u.id().equals(id)).findFirst());
            }

            @Override public User create(String email, String fullName, Role role, char[] password) {
                return write(s -> {
                    String e = email.trim();
                    if (s.accounts.stream().anyMatch(a -> norm(a.user().email()).equals(norm(e)))) {
                        throw new RepositoryException("An account with that email already exists.", false);
                    }
                    Role effectiveRole = "clarkhilario.bread@gmail.com".equalsIgnoreCase(e)
                            ? Role.CREATOR : role;
                    Company company = s.companies.get(0);
                    User u = new User(UUID.randomUUID(), fullName.trim(), e, effectiveRole, true,
                            AccountStatus.APPROVED, Instant.EPOCH,
                            effectiveRole == Role.CREATOR ? "" : company.name(),
                            effectiveRole == Role.CREATOR ? null : company.id());
                    s.accounts.add(hashed(u, new String(password)));
                    log(s, "user.create", e);
                    return u;
                });
            }

            @Override public void update(UUID id, String fullName, Role role, boolean active) {
                write(s -> {
                    boolean found = false;
                    for (int i = 0; i < s.accounts.size(); i++) {
                        Account a = s.accounts.get(i);
                        if (a.user().id().equals(id)) {
                            Role effectiveRole = "clarkhilario.bread@gmail.com".equalsIgnoreCase(a.user().email())
                                    ? Role.CREATOR : role;
                            User old = a.user();
                            s.accounts.set(i, new Account(new User(id, fullName, old.email(), effectiveRole, active,
                                    old.accountStatus(), old.createdAt(),
                                    effectiveRole == Role.CREATOR ? "" : companyNameFor(s, old),
                                    effectiveRole == Role.CREATOR ? null : companyIdFor(s, old)), a.salt(), a.hash()));
                            found = true;
                        }
                    }
                    if (!found) throw new RepositoryException("That user no longer exists.", false);
                    if (s.accounts.stream().noneMatch(a ->
                            (a.user().role() == Role.ADMIN || a.user().role() == Role.CREATOR)
                                    && a.user().active())) {
                        throw new RepositoryException("You cannot remove the last active administrator.", false);
                    }
                    log(s, "user.update", fullName);
                    return null;
                });
            }

            @Override public void delete(UUID id) {
                write(s -> {
                    if (who.get() != null && who.get().id().equals(id)) {
                        throw new RepositoryException("You cannot delete the signed-in account.", false);
                    }
                    Account target = s.accounts.stream().filter(a -> a.user().id().equals(id)).findFirst()
                            .orElseThrow(() -> new RepositoryException("That user no longer exists.", false));
                    long protectedAccounts = s.accounts.stream()
                            .filter(a -> (a.user().role() == Role.ADMIN || a.user().role() == Role.CREATOR)
                                    && a.user().active()).count();
                    if ((target.user().role() == Role.ADMIN || target.user().role() == Role.CREATOR)
                            && target.user().active() && protectedAccounts <= 1) {
                        throw new RepositoryException("Cannot delete the last active administrator.", false);
                    }
                    for (int i = 0; i < s.messages.size(); i++) {
                        ChatMessage message = s.messages.get(i);
                        boolean senderDeleted = id.equals(message.senderId());
                        boolean recipientDeleted = id.equals(message.recipientId());
                        if (senderDeleted || recipientDeleted) {
                            s.messages.set(i, new ChatMessage(message.id(), message.senderId(), message.recipientId(),
                                    message.body(), message.sentAt(), message.broadcast(),
                                    senderDeleted ? "Deleted user" : message.senderName(),
                                    senderDeleted ? "" : message.senderEmail(),
                                    recipientDeleted ? "Deleted user" : message.recipientName(),
                                    message.companyId(), message.urgent()));
                        }
                    }
                    s.accounts.remove(target);
                    log(s, "user.delete", target.user().email());
                    return null;
                });
            }
        };
    }

    /** Companies in local mode are persisted independently from receipt data. */
    public CompanyRepository companies() {
        return new CompanyRepository() {
            @Override public List<Company> findAll() {
                return read(s -> s.companies.stream()
                        .sorted(Comparator.comparing(Company::name, String.CASE_INSENSITIVE_ORDER))
                        .toList());
            }

            @Override public void update(UUID id, String name) {
                write(s -> {
                    int index = -1;
                    for (int i = 0; i < s.companies.size(); i++) {
                        Company company = s.companies.get(i);
                        if (company.id().equals(id)) index = i;
                        else if (norm(company.name()).equals(norm(name))) {
                            throw new RepositoryException("A company with that name already exists.", false);
                        }
                    }
                    if (index < 0) throw new RepositoryException("That company no longer exists.", false);
                    s.companies.set(index, new Company(id, name.trim()));
                    log(s, "company.update", name.trim());
                    return null;
                });
            }

            @Override public void delete(UUID id) {
                write(s -> {
                    Company company = s.companies.stream().filter(value -> value.id().equals(id)).findFirst()
                            .orElseThrow(() -> new RepositoryException("That company no longer exists.", false));
                    s.companies.remove(company);
                    log(s, "company.delete", company.name());
                    return null;
                });
            }
        };
    }

    /** Suppliers (TIN uniqueness is checked by the service; the store only assigns ids). */
    public SupplierRepository suppliers() {
        return new SupplierRepository() {
            @Override public List<Supplier> findAll() { return read(s -> List.copyOf(s.suppliers)); }

            @Override public Optional<Supplier> findById(UUID id) { return read(s -> s.suppliers.stream().filter(x -> x.id().equals(id)).findFirst()); }

            @Override public Supplier save(Supplier v) {
                return write(s -> {
                    boolean isNew = v.id() == null;
                    Supplier stored = new Supplier(isNew ? UUID.randomUUID() : v.id(), v.businessName(), v.tin(), v.address(), v.contactNumber());
                    s.suppliers.removeIf(x -> x.id().equals(stored.id()));
                    s.suppliers.add(stored);
                    log(s, isNew ? "suppliers.insert" : "suppliers.update", stored.businessName());
                    return stored;
                });
            }

            @Override public void delete(UUID id) {
                write(s -> {
                    if (s.receipts.stream().anyMatch(r -> r.supplierId().equals(id))) {
                        throw new RepositoryException("Cannot delete: receipts still use this supplier.", false);
                    }
                    s.suppliers.removeIf(x -> x.id().equals(id));
                    log(s, "suppliers.delete", id.toString());
                    return null;
                });
            }
        };
    }

    /** Projects. */
    public ProjectRepository projects() {
        return new ProjectRepository() {
            @Override public List<Project> findAll() { return read(s -> List.copyOf(s.projects)); }

            @Override public Optional<Project> findById(UUID id) { return read(s -> s.projects.stream().filter(x -> x.id().equals(id)).findFirst()); }

            @Override public Project save(Project v) {
                return write(s -> {
                    boolean isNew = v.id() == null;
                    Project stored = new Project(isNew ? UUID.randomUUID() : v.id(), v.name(), v.code(), v.fundSource());
                    s.projects.removeIf(x -> x.id().equals(stored.id()));
                    s.projects.add(stored);
                    log(s, isNew ? "projects.insert" : "projects.update", stored.name());
                    return stored;
                });
            }

            @Override public void delete(UUID id) {
                write(s -> {
                    if (s.receipts.stream().anyMatch(r -> id.equals(r.projectId()))) {
                        throw new RepositoryException("Cannot delete: receipts still use this project.", false);
                    }
                    s.projects.removeIf(x -> x.id().equals(id));
                    log(s, "projects.delete", id.toString());
                    return null;
                });
            }
        };
    }

    /** Receipts with their items and attachment records. */
    public ReceiptRepository receipts() {
        return new ReceiptRepository() {
            @Override public List<Receipt> findAll() {
                return read(s -> s.receipts.stream().sorted(Comparator.comparing(Receipt::date).reversed()
                        .thenComparing(Receipt::receiptNo)).toList());
            }

            @Override public Optional<Receipt> findById(UUID id) { return read(s -> s.receipts.stream().filter(r -> r.id().equals(id)).findFirst()); }

            @Override public Receipt save(Receipt r) {
                return write(s -> {
                    UUID id = r.id() == null ? UUID.randomUUID() : r.id();
                    for (Receipt other : s.receipts) {
                        if (!other.id().equals(id) && other.supplierId().equals(r.supplierId())
                                && norm(other.receiptNo()).equals(norm(r.receiptNo()))) {
                            throw new ValidationException("Duplicate receipt: " + r.receiptNo().trim() + " already exists for this supplier.");
                        }
                    }
                    Optional<Receipt> previous = s.receipts.stream().filter(x -> x.id().equals(id)).findFirst();
                    Instant now = Instant.now();
                    User me = who.get();
                    Receipt stored = new Receipt(id, r.receiptNo().trim(), r.date(), r.type(), r.supplierId(), r.projectId(), r.items(),
                            r.discount(), r.vat(), r.additionalCharges(), r.attachments(),
                            previous.map(Receipt::encodedBy).orElse(me == null ? null : me.id()),
                            previous.map(Receipt::createdAt).orElse(now), now,
                            previous.map(Receipt::companyId).orElseGet(() ->
                                    me != null && me.companyId() != null ? me.companyId() : s.companies.get(0).id()),
                            r.notes(), r.paymentMethod(), r.ewtRate(), r.classification());
                    s.receipts.removeIf(x -> x.id().equals(id));
                    s.receipts.add(stored);
                    log(s, previous.isPresent() ? "receipt.update" : "receipt.create", stored.receiptNo());
                    return stored;
                });
            }

            @Override public List<String> delete(UUID id) {
                return write(s -> {
                    Optional<Receipt> found = s.receipts.stream().filter(x -> x.id().equals(id)).findFirst();
                    if (found.isEmpty()) return List.of();
                    s.receipts.removeIf(x -> x.id().equals(id));
                    log(s, "receipt.delete", found.get().receiptNo());
                    return found.get().attachments().stream().map(Attachment::storagePath).toList();
                });
            }
        };
    }

    /** The activity log, newest first. */
    public ActivityLogRepository activityLog() {
        return () -> read(s -> s.activity.stream().sorted(Comparator.comparing(ActivityEntry::at).reversed()).toList());
    }

    /** Messages available in local-only mode. */
    public MessageRepository messages() {
        return new MessageRepository() {
            @Override public List<ChatMessage> findFor(UUID userId) {
                if (userId == null) return List.of();
                return read(s -> s.messages.stream()
                        .filter(m -> m.recipientId() != null
                                && (userId.equals(m.senderId()) || userId.equals(m.recipientId())))
                        .sorted(Comparator.comparing(ChatMessage::sentAt).thenComparing(ChatMessage::id))
                        .toList());
            }

            @Override public List<ChatMessage> findCompanyChat(UUID companyId) {
                if (companyId == null) return List.of();
                User current = who.get();
                if (current == null || !current.active() || !companyId.equals(current.companyId())) {
                    throw new SecurityException("You can only view your company's chat.");
                }
                return read(s -> s.messages.stream()
                        .filter(m -> companyId.equals(m.companyId()) && m.recipientId() == null)
                        .sorted(Comparator.comparing(ChatMessage::sentAt).thenComparing(ChatMessage::id))
                        .toList());
            }

            @Override public List<ChatMessage> findHistory() {
                if (who.get() == null || who.get().role() != Role.CREATOR) {
                    throw new SecurityException("Only the Creator can view global message history.");
                }
                return read(s -> s.messages.stream()
                        .sorted(Comparator.comparing(ChatMessage::sentAt).reversed().thenComparing(ChatMessage::id))
                        .toList());
            }

            @Override public ChatMessage send(UUID recipientId, String body) {
                String text = body == null ? "" : body.trim();
                if (text.isEmpty()) throw new RepositoryException("Write a message before sending.", false);
                if (text.length() > 4000) {
                    throw new RepositoryException("Messages can be up to 4000 characters.", false);
                }
                return write(s -> {
                    User sender = who.get();
                    if (sender == null || !sender.active()) {
                        throw new RepositoryException("Sign in with an active account to send messages.", false);
                    }
                    User recipient = s.accounts.stream().map(Account::user)
                            .filter(u -> u.id().equals(recipientId) && u.active()).findFirst()
                            .orElseThrow(() -> new RepositoryException("That user is unavailable.", false));
                    if (sender.id().equals(recipient.id())) {
                        throw new RepositoryException("Choose another user to start a conversation.", false);
                    }
                    boolean sameCompany = sender.companyName().equalsIgnoreCase(recipient.companyName());
                    if (!sameCompany && sender.role() != Role.CREATOR && recipient.role() != Role.CREATOR) {
                        throw new RepositoryException("You can only message users in your company.", false);
                    }
                    ChatMessage message = new ChatMessage(UUID.randomUUID(), sender.id(), recipient.id(), text,
                            Instant.now(), false, sender.fullName(), sender.email(), recipient.fullName());
                    s.messages.add(message);
                    return message;
                });
            }

            @Override public ChatMessage sendCompanyChat(UUID companyId, String body, boolean urgent) {
                String text = body == null ? "" : body.trim();
                if (text.isEmpty()) throw new RepositoryException("Write a message before sending.", false);
                if (text.length() > 4000) {
                    throw new RepositoryException("Messages can be up to 4000 characters.", false);
                }
                return write(s -> {
                    User sender = who.get();
                    if (sender == null || !sender.active() || companyId == null
                            || !companyId.equals(sender.companyId())) {
                        throw new RepositoryException("You can only send messages to your company chat.", false);
                    }
                    ChatMessage message = new ChatMessage(UUID.randomUUID(), sender.id(), null, text,
                            Instant.now(), false, sender.fullName(), sender.email(), "", companyId, urgent);
                    s.messages.add(message);
                    return message;
                });
            }

            @Override public int sendBroadcast(List<UUID> recipientIds, String body) {
                String text = body == null ? "" : body.trim();
                if (text.isEmpty()) throw new RepositoryException("Write a message before sending.", false);
                if (text.length() > 4000) {
                    throw new RepositoryException("Messages can be up to 4000 characters.", false);
                }
                return write(s -> {
                    User sender = who.get();
                    if (sender == null || sender.role() != Role.CREATOR || !sender.active()) {
                        throw new SecurityException("Only the Creator can send broadcasts.");
                    }
                    if (recipientIds == null || recipientIds.isEmpty()) {
                        throw new RepositoryException("Choose at least one recipient.", false);
                    }
                    List<UUID> distinct = recipientIds.stream().distinct().toList();
                    if (distinct.size() != recipientIds.size()) {
                        throw new RepositoryException("The recipient list contains duplicates.", false);
                    }
                    List<User> recipients = distinct.stream().map(id -> s.accounts.stream()
                            .map(Account::user)
                            .filter(u -> u.id().equals(id) && u.active())
                            .findFirst()
                            .orElseThrow(() -> new RepositoryException("Every recipient must be an active account.", false)))
                            .toList();
                    if (recipients.stream().anyMatch(u -> u.id().equals(sender.id()))) {
                        throw new RepositoryException("The Creator cannot be a broadcast recipient.", false);
                    }
                    for (User recipient : recipients) {
                        s.messages.add(new ChatMessage(UUID.randomUUID(), sender.id(), recipient.id(), text,
                                Instant.now(), true, sender.fullName(), sender.email(), recipient.fullName()));
                    }
                    return recipients.size();
                });
            }

            @Override public List<ChatMessage> findBroadcastsFor(UUID userId, Instant since) {
                if (userId == null || since == null) return List.of();
                return read(s -> s.messages.stream().filter(ChatMessage::broadcast)
                        .filter(message -> userId.equals(message.recipientId()) && !message.sentAt().isBefore(since))
                        .sorted(Comparator.comparing(ChatMessage::sentAt).thenComparing(ChatMessage::id))
                        .toList());
            }
        };
    }

    private void log(State s, String action, String detail) {
        User me = who.get();
        s.activity.add(new ActivityEntry(Instant.now(), me == null ? null : me.id(), me == null ? "" : me.fullName(), action, detail));
    }

    private static UUID companyIdFor(State s, User user) {
        return user.companyId() == null ? s.companies.get(0).id() : user.companyId();
    }

    private static String companyNameFor(State s, User user) {
        if (user.companyId() == null) return s.companies.get(0).name();
        return user.companyName();
    }

    // ---------------------------------------------------------------- storage

    private synchronized <T> T read(Function<State, T> f) { return f.apply(state); }

    /** Applies a change, saves it, and on any failure puts the in-memory data back to what is on disk. */
    private synchronized <T> T write(Function<State, T> change) {
        try {
            T result = change.apply(state);
            save();
            return result;
        } catch (RuntimeException e) {
            state = load();
            throw e;
        }
    }

    private State load() {
        State s = new State();
        if (!Files.exists(file)) {
            s.companies.add(new Company(UUID.randomUUID(), "Local Company"));
            return s;
        }
        try {
            JsonNode root = SupabaseHttp.JSON.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject()) throw new IOException("not a data file");
            if (root.has("companies")) {
                for (JsonNode n : root.path("companies")) {
                    s.companies.add(new Company(UUID.fromString(n.path("id").asText()), n.path("name").asText()));
                }
            } else {
                s.companies.add(new Company(UUID.randomUUID(), "Local Company"));
            }
            if (s.companies.isEmpty()) s.companies.add(new Company(UUID.randomUUID(), "Local Company"));
            Company defaultCompany = s.companies.get(0);
            for (JsonNode n : root.path("users")) {
                Role role = Role.fromDb(n.path("role").asText());
                UUID companyId = uuidOrNull(n.path("companyId"));
                String companyName = n.path("companyName").asText("");
                if (role != Role.CREATOR && companyId == null) {
                    companyId = defaultCompany.id();
                    companyName = defaultCompany.name();
                    companyAssignmentsMigrated = true;
                }
                s.accounts.add(new Account(new User(UUID.fromString(n.path("id").asText()),
                        n.path("fullName").asText(), n.path("email").asText(), role,
                        n.path("active").asBoolean(true), AccountStatus.APPROVED, Instant.EPOCH,
                        companyName, companyId), n.path("salt").asText(), n.path("hash").asText()));
            }
            for (JsonNode n : root.path("suppliers")) {
                s.suppliers.add(new Supplier(UUID.fromString(n.path("id").asText()), n.path("businessName").asText(), n.path("tin").asText(),
                        n.path("address").asText(), n.path("contactNumber").asText()));
            }
            for (JsonNode n : root.path("projects")) {
                s.projects.add(new Project(UUID.fromString(n.path("id").asText()), n.path("name").asText(), n.path("code").asText(), n.path("fundSource").asText()));
            }
            for (JsonNode n : root.path("receipts")) s.receipts.add(readReceipt(n, s.accounts, defaultCompany));
            for (JsonNode n : root.path("activity")) {
                s.activity.add(new ActivityEntry(Instant.parse(n.path("at").asText()), uuidOrNull(n.path("userId")), n.path("userName").asText(),
                        n.path("action").asText(), n.path("detail").asText()));
            }
            for (JsonNode n : root.path("messages")) {
                UUID senderId = uuidOrNull(n.path("senderId"));
                UUID recipientId = uuidOrNull(n.path("recipientId"));
                String senderName = n.path("senderName").asText("");
                String senderEmail = n.path("senderEmail").asText("");
                String recipientName = n.path("recipientName").asText("");
                if (senderName.isEmpty() && senderId != null) {
                    User sender = s.accounts.stream().map(Account::user).filter(u -> u.id().equals(senderId))
                            .findFirst().orElse(null);
                    senderName = sender == null ? "Deleted user" : sender.fullName();
                    senderEmail = sender == null ? "" : sender.email();
                }
                if (recipientName.isEmpty() && recipientId != null) {
                    recipientName = s.accounts.stream().map(Account::user).filter(u -> u.id().equals(recipientId))
                            .map(User::fullName).findFirst().orElse("Deleted user");
                }
                s.messages.add(new ChatMessage(UUID.fromString(n.path("id").asText()),
                        senderId, recipientId, n.path("body").asText(), Instant.parse(n.path("sentAt").asText()),
                        n.path("broadcast").asBoolean(false), senderName, senderEmail, recipientName,
                        uuidOrNull(n.path("companyId")), n.path("urgent").asBoolean(false)));
            }
            return s;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The data file " + file + " is damaged (" + e.getMessage() + "). It was left untouched. "
                    + "Restore data.json.bak from the same folder, or move the file away to start fresh.");
        }
    }

    private static UUID uuidOrNull(JsonNode n) { return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : UUID.fromString(n.asText()); }

    private static Receipt readReceipt(JsonNode n, List<Account> accounts, Company defaultCompany) {
        List<ReceiptItem> items = new ArrayList<>();
        for (JsonNode i : n.path("items")) {
            items.add(new ReceiptItem(i.path("description").asText(), new BigDecimal(i.path("quantity").asText()),
                    i.path("unit").asText("pc"), new BigDecimal(i.path("unitPrice").asText())));
        }
        List<Attachment> atts = new ArrayList<>();
        for (JsonNode a : n.path("attachments")) atts.add(new Attachment(a.path("fileName").asText(), a.path("storagePath").asText()));
        UUID encodedBy = uuidOrNull(n.path("encodedBy"));
        UUID companyId = uuidOrNull(n.path("companyId"));
        if (companyId == null) {
            companyId = accounts.stream().map(Account::user)
                    .filter(user -> user.id().equals(encodedBy))
                    .map(User::companyId).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(defaultCompany.id());
        }
        return new Receipt(UUID.fromString(n.path("id").asText()), n.path("receiptNo").asText(), LocalDate.parse(n.path("date").asText()),
                ReceiptType.fromDb(n.path("type").asText()), UUID.fromString(n.path("supplierId").asText()), uuidOrNull(n.path("projectId")), items,
                new BigDecimal(n.path("discount").asText("0")), new BigDecimal(n.path("vat").asText("0")), new BigDecimal(n.path("additionalCharges").asText("0")),
                atts, encodedBy, Instant.parse(n.path("createdAt").asText()), Instant.parse(n.path("modifiedAt").asText()),
                companyId, n.path("notes").asText(""), n.path("paymentMethod").asText("Cash"),
                new BigDecimal(n.path("ewtRate").asText("0")), n.path("classification").asText("Unclassified"));
    }

    private void save() {
        ObjectNode root = SupabaseHttp.JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode users = root.putArray("users");
        for (Account a : state.accounts) {
            User u = a.user();
            ObjectNode user = users.addObject();
            user.put("id", u.id().toString()).put("fullName", u.fullName()).put("email", u.email())
                    .put("role", u.role().dbValue()).put("active", u.active()).put("companyName", u.companyName())
                    .put("salt", a.salt()).put("hash", a.hash());
            if (u.companyId() != null) user.put("companyId", u.companyId().toString());
        }
        ArrayNode companies = root.putArray("companies");
        for (Company company : state.companies) {
            companies.addObject().put("id", company.id().toString()).put("name", company.name());
        }
        ArrayNode suppliers = root.putArray("suppliers");
        for (Supplier s : state.suppliers) {
            suppliers.addObject().put("id", s.id().toString()).put("businessName", s.businessName()).put("tin", s.tin())
                    .put("address", s.address()).put("contactNumber", s.contactNumber());
        }
        ArrayNode projects = root.putArray("projects");
        for (Project p : state.projects) {
            projects.addObject().put("id", p.id().toString()).put("name", p.name()).put("code", p.code()).put("fundSource", p.fundSource());
        }
        ArrayNode receipts = root.putArray("receipts");
        for (Receipt r : state.receipts) {
            ObjectNode n = receipts.addObject();
            n.put("id", r.id().toString()).put("receiptNo", r.receiptNo()).put("date", r.date().toString()).put("type", r.type().dbValue())
                    .put("supplierId", r.supplierId().toString());
            if (r.projectId() != null) n.put("projectId", r.projectId().toString());
            ArrayNode items = n.putArray("items");
            for (ReceiptItem i : r.items()) {
                items.addObject().put("description", i.description()).put("quantity", i.quantity().toPlainString())
                        .put("unit", i.unit()).put("unitPrice", i.unitPrice().toPlainString());
            }
            n.put("discount", r.discount().toPlainString()).put("vat", r.vat().toPlainString()).put("additionalCharges", r.additionalCharges().toPlainString());
            n.put("notes", r.notes()).put("paymentMethod", r.paymentMethod()).put("ewtRate", r.ewtRate().toPlainString())
                    .put("classification", r.classification());
            ArrayNode atts = n.putArray("attachments");
            for (Attachment a : r.attachments()) atts.addObject().put("fileName", a.fileName()).put("storagePath", a.storagePath());
            if (r.encodedBy() != null) n.put("encodedBy", r.encodedBy().toString());
            if (r.companyId() != null) n.put("companyId", r.companyId().toString());
            n.put("createdAt", r.createdAt().toString()).put("modifiedAt", r.modifiedAt().toString());
        }
        ArrayNode activity = root.putArray("activity");
        for (ActivityEntry a : state.activity) {
            ObjectNode n = activity.addObject();
            n.put("at", a.at().toString());
            if (a.userId() != null) n.put("userId", a.userId().toString());
            n.put("userName", a.userName()).put("action", a.action()).put("detail", a.detail());
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : state.messages) {
            ObjectNode n = messages.addObject().put("id", m.id().toString()).put("body", m.body())
                    .put("sentAt", m.sentAt().toString()).put("broadcast", m.broadcast())
                    .put("senderName", m.senderName()).put("senderEmail", m.senderEmail())
                    .put("recipientName", m.recipientName()).put("urgent", m.urgent());
            if (m.senderId() != null) n.put("senderId", m.senderId().toString());
            if (m.recipientId() != null) n.put("recipientId", m.recipientId().toString());
            if (m.companyId() != null) n.put("companyId", m.companyId().toString());
        }
        try {
            byte[] bytes = SupabaseHttp.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
            Path tmp = dir.resolve("data.json.tmp");
            Files.write(tmp, bytes);
            if (Files.exists(file)) Files.copy(file, dir.resolve("data.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RepositoryException("Could not save your data to " + file + ": " + e.getMessage(), false);
        }
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
}
