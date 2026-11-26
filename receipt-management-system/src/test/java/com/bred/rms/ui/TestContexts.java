package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.services.ProjectService;
import com.bred.rms.services.ReceiptService;
import com.bred.rms.services.SupplierService;
import com.bred.rms.support.Fakes;

import java.util.UUID;

/** Builds an AppContext over in-memory fakes for UI tests. Test-only. */
final class TestContexts {
    private TestContexts() { }

    static AppContext withUser(Role role) {
        UUID companyId = role == Role.CREATOR ? null : UUID.randomUUID();
        User user = new User(UUID.randomUUID(), "Test User", "t@x.test", role, true,
                com.bred.rms.models.AccountStatus.APPROVED, java.time.Instant.EPOCH, "Test Company", companyId);
        var receipts = new Fakes.Receipts();
        var suppliers = new Fakes.Suppliers();
        var projects = new Fakes.Projects();
        var attachments = new Fakes.Attachments();
        return AppContext.forTesting(user, new ReceiptService(receipts, suppliers, projects, attachments, () -> user),
                new SupplierService(suppliers, receipts, () -> user), new ProjectService(projects, receipts, () -> user), attachments);
    }
}
