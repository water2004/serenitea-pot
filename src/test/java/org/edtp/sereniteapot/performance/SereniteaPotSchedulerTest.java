package org.edtp.sereniteapot.performance;

import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SereniteaPotSchedulerTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void overrunIsRepaidBeforeWholePotRunsAgain() {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 2.0));
        var first = new SereniteaPotScheduler.TickPlan(List.of(FIRST), 20.0, ledger);
        assertTrue(first.admitSerial(FIRST));
        first.recordSerialCost(FIRST, 6);
        assertTrue(first.admitSerial(FIRST)); // remaining dimensions of the admitted pot
        assertEquals(-4.0, ledger.available(FIRST));

        ledger.refill(Map.of(FIRST, 2.0));
        assertFalse(new SereniteaPotScheduler.TickPlan(List.of(FIRST), 20.0, ledger).admitSerial(FIRST));
        ledger.refill(Map.of(FIRST, 2.0));
        assertEquals(0.0, ledger.available(FIRST));
        assertFalse(new SereniteaPotScheduler.TickPlan(List.of(FIRST), 20.0, ledger).admitSerial(FIRST));
        ledger.refill(Map.of(FIRST, 2.0));
        assertTrue(new SereniteaPotScheduler.TickPlan(List.of(FIRST), 20.0, ledger).admitSerial(FIRST));
    }

    @Test
    void unusedAllowanceDoesNotHoardAndOwnersAreIndependent() {
        var ledger = new OwnerBudgetLedger();
        for (int tick = 0; tick < 10; tick++) ledger.refill(Map.of(FIRST, 2.0, SECOND, 2.0));
        assertEquals(2.0, ledger.available(FIRST));
        ledger.debit(FIRST, 5.0);
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST, SECOND), 20.0, ledger);
        assertFalse(plan.admitSerial(FIRST));
        assertTrue(plan.admitSerial(SECOND));
    }

    @Test
    void zeroAllowanceDeniesNormalAndCreationWork() {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 0.0));
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST), 20_000_000.0, ledger);
        assertFalse(plan.admitSerial(FIRST));
        assertFalse(plan.admitThreaded(FIRST));
        assertEquals(0.0, plan.reserveCreation(FIRST, 1_000_000));
        ledger.debit(FIRST, 1_000_000.0);
        ledger.refill(Map.of(FIRST, 0.0));
        assertEquals(-1_000_000.0, ledger.available(FIRST));
    }

    @Test
    void loweringAllowanceClampsSavingsButRetainsDebt() {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 5.0));
        ledger.refill(Map.of(FIRST, 2.0));
        assertEquals(2.0, ledger.available(FIRST));
        ledger.debit(FIRST, 7.0);
        ledger.refill(Map.of(FIRST, 2.0));
        assertEquals(-3.0, ledger.available(FIRST));
    }

    @Test
    void metricsResetRetainsDebtButDeletionForgetsIt() {
        SereniteaPotScheduler.ownerBudgets.refill(Map.of(FIRST, 2.0));
        try {
            SereniteaPotScheduler.ownerBudgets.debit(FIRST, 5.0);
            SereniteaPotScheduler.reset(FIRST);
            assertEquals(-3.0, SereniteaPotScheduler.ownerBudgets.available(FIRST));
            SereniteaPotScheduler.forgetOwner(FIRST);
            assertEquals(0.0, SereniteaPotScheduler.ownerBudgets.available(FIRST));
        } finally {
            SereniteaPotScheduler.forgetOwner(FIRST);
        }
    }

    @Test
    void serialCostsAllThreeDimensionsAndCreationOverrun() {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 2_000_000.0, SECOND, 2_000_000.0));
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST, SECOND), 20_000_000.0, ledger);
        for (int dimension = 0; dimension < 3; dimension++) {
            assertTrue(plan.admitSerial(FIRST));
            plan.recordSerialCost(FIRST, 1_000_000);
        }
        assertEquals(-1_000_000.0, ledger.available(FIRST));
        assertTrue(plan.admitSerial(SECOND));
        assertEquals(1_000_000.0, plan.reserveCreation(SECOND, 1_000_000));
        plan.correctCreation(SECOND, 1_000_000.0, 3_000_000);
        assertEquals(-1_000_000.0, ledger.available(SECOND));
        ledger.refill(Map.of(FIRST, 2_000_000.0, SECOND, 2_000_000.0));
        assertEquals(1_000_000.0, ledger.available(FIRST));
        assertEquals(1_000_000.0, ledger.available(SECOND));
    }

    @Test
    void globalOverrunStillFinishesAdmittedPotButStopsNext() {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 2.0, SECOND, 2.0));
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST, SECOND), 2.0, ledger);
        assertTrue(plan.admitSerial(FIRST));
        plan.recordSerialCost(FIRST, 3);
        assertTrue(plan.admitSerial(FIRST));
        assertFalse(plan.admitSerial(SECOND));
        assertEquals(0.0, plan.reserveCreation(SECOND, 100_000));
    }

    @Test
    void zeroGlobalBudgetDeniesBothKindsOfWork() {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 1_000_000.0));
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST), 0.0, ledger);
        assertFalse(plan.admitSerial(FIRST));
        assertFalse(plan.admitThreaded(FIRST));
        assertEquals(0.0, plan.reserveCreation(FIRST, 1_000_000));
    }

    @Test
    void threadedChargesSlowestLaneAfterAllThreeFinish() throws Exception {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 2.0, SECOND, 2.0));
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST, SECOND), 10.0, ledger);
        try (var workers = Executors.newFixedThreadPool(3)) {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] results = new Future[3];
            for (var dimension : SereniteaPotDimension.values()) {
                results[dimension.ordinal()] = workers.submit(() -> {
                    boolean firstAdmitted = plan.admitThreaded(FIRST);
                    plan.finishThreadedOwner(FIRST, dimension, dimension.ordinal() + 3L, firstAdmitted);
                    boolean secondAdmitted = plan.admitThreaded(SECOND);
                    plan.finishThreadedOwner(SECOND, dimension, 1L, secondAdmitted);
                    return firstAdmitted && secondAdmitted;
                });
            }
            try {
                for (var result : results) assertTrue(result.get(5, TimeUnit.SECONDS));
            } finally {
                plan.abortThreadedTick();
            }
        }
        assertEquals(-3.0, ledger.available(FIRST)); // max(3, 4, 5), not their sum
        assertEquals(1.0, ledger.available(SECOND));
    }

    @Test
    void threadedSkipsDebtorAndAdmitsNextOwner() throws Exception {
        var ledger = new OwnerBudgetLedger();
        ledger.refill(Map.of(FIRST, 2.0, SECOND, 2.0));
        ledger.debit(FIRST, 3.0);
        var plan = new SereniteaPotScheduler.TickPlan(List.of(FIRST, SECOND), 10.0, ledger);
        try (var workers = Executors.newFixedThreadPool(3)) {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] results = new Future[3];
            for (var dimension : SereniteaPotDimension.values()) {
                results[dimension.ordinal()] = workers.submit(() -> {
                    boolean debtorAdmitted = plan.admitThreaded(FIRST);
                    plan.finishThreadedOwner(FIRST, dimension, 0L, debtorAdmitted);
                    boolean nextAdmitted = plan.admitThreaded(SECOND);
                    plan.finishThreadedOwner(SECOND, dimension, 1L, nextAdmitted);
                    return !debtorAdmitted && nextAdmitted;
                });
            }
            try {
                for (var result : results) assertTrue(result.get(5, TimeUnit.SECONDS));
            } finally {
                plan.abortThreadedTick();
            }
        }
        assertEquals(-1.0, ledger.available(FIRST));
        assertEquals(1.0, ledger.available(SECOND));
    }
}
