package com.bred.rms.ui;

import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Company;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanyPanelTest {
    @Test
    void summarizesCompanyExpensesReceiptsAndActivePeopleByCompany() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Company first = new Company(firstId, "First company");
        Company second = new Company(secondId, "Second company");
        List<User> users = List.of(
                user(firstId, Role.ADMIN, true),
                user(firstId, Role.ENCODER, true),
                user(firstId, Role.ADMIN, false),
                user(secondId, Role.ENCODER, true),
                user(null, Role.CREATOR, true));

        List<CompanyPanel.CompanySummary> summaries = CompanyPanel.summarize(
                List.of(first, second), users,
                List.of(receipt(firstId, "10.25"), receipt(firstId, "20.00"), receipt(secondId, "7.50")));

        assertEquals(new CompanyPanel.CompanySummary(first, new BigDecimal("30.25"), 1, 1, 2), summaries.get(0));
        assertEquals(new CompanyPanel.CompanySummary(second, new BigDecimal("7.50"), 0, 1, 1), summaries.get(1));
    }

    private static User user(UUID companyId, Role role, boolean active) {
        return new User(UUID.randomUUID(), role.label(), role.name().toLowerCase() + "@example.test", role,
                active, AccountStatus.APPROVED, Instant.EPOCH, "", companyId);
    }

    private static Receipt receipt(UUID companyId, String amount) {
        return new Receipt(UUID.randomUUID(), UUID.randomUUID().toString(), LocalDate.of(2026, 11, 1),
                ReceiptType.OFFICIAL_RECEIPT, UUID.randomUUID(), null,
                List.of(new ReceiptItem("Item", BigDecimal.ONE, new BigDecimal(amount))),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null, Instant.EPOCH, Instant.EPOCH,
                companyId);
    }
}
