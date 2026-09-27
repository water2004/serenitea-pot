package org.edtp.sereniteapot.performance;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owner allowances carry overruns forward, but never save unused time beyond one tick. */
final class OwnerBudgetLedger {
    private final Map<UUID, Double> balances = new HashMap<>();

    synchronized void refill(Map<UUID, Double> allowances) {
        allowances.forEach((owner, allowance) -> balances.merge(
            owner, allowance, (balance, added) -> Math.min(added, balance + added)
        ));
    }

    synchronized double available(UUID owner) {
        return balances.getOrDefault(owner, 0.0);
    }

    synchronized void debit(UUID owner, double nanos) {
        balances.merge(owner, -nanos, Double::sum);
    }

    synchronized void remove(UUID owner) {
        balances.remove(owner);
    }

    synchronized void clear() {
        balances.clear();
    }
}
