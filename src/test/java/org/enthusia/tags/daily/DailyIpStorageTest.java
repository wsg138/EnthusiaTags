package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyIpStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void blocksUnrelatedAccountsButAllowsConfiguredSiblings() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 31);
        try (DailyIpStorage storage = new DailyIpStorage(
            temporaryDirectory.resolve("daily-ip.db").toFile())) {
            assertTrue(storage.reserve(first, date, "203.0.113.10"));
            assertFalse(storage.reserve(second, date, "203.0.113.10"));
            assertTrue(storage.addSibling(first, second, "test"));
            assertTrue(storage.reserve(second, date, "203.0.113.10"));
        }
    }

    @Test
    void reminderEligibilityReadsNeverCreateIpReservations() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 10, 5);
        String address = "203.0.113.10";
        try (DailyIpStorage storage = new DailyIpStorage(
            temporaryDirectory.resolve("reminder-ip.db").toFile())) {
            assertTrue(storage.canReserve(first, date, address));
            assertTrue(storage.owners(date, address).isEmpty());
            assertTrue(storage.reserve(first, date, address));
            assertTrue(storage.canReserve(first, date, address));
            assertFalse(storage.canReserve(second, date, address));
            assertTrue(storage.addSibling(first, second, "test"));
            assertTrue(storage.canReserve(second, date, address));
            assertFalse(storage.owners(date, address).contains(second));
        }
    }

    @Test
    void siblingGroupsAreTransitiveForHouseholds() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 31);
        try (DailyIpStorage storage = new DailyIpStorage(
            temporaryDirectory.resolve("daily-ip-group.db").toFile())) {
            storage.addSibling(first, second, "test");
            storage.addSibling(second, third, "test");
            assertTrue(storage.reserve(first, date, "2001:db8::1"));
            assertTrue(storage.reserve(third, date, "2001:db8::1"));
        }
    }

    @Test
    void safeFailureReleaseLetsAnotherAccountClaim() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 31);
        try (DailyIpStorage storage = new DailyIpStorage(
            temporaryDirectory.resolve("daily-ip-release.db").toFile())) {
            assertTrue(storage.reserve(first, date, "198.51.100.4"));
            storage.release(first, date, "198.51.100.4");
            assertTrue(storage.reserve(second, date, "198.51.100.4"));
        }
    }
}
