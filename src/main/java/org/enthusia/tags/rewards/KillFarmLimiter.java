package org.enthusia.tags.rewards;

final class KillFarmLimiter {
    static final String TRUSTED_KILL_COUNTER = "legitimate_player_kills";
    static final String INITIALIZED_STATE = "anti-farm-kills-initialized";
    static final String PAIR_STATE_PREFIX = "anti-farm-kill:";

    private KillFarmLimiter() {
    }

    static Decision evaluate(String encoded, long now, long cooldownMillis,
                             long windowMillis, int maximumCredits) {
        KillRecord record = KillRecord.parse(encoded);
        long safeWindow = Math.max(1L, windowMillis);
        long safeCooldown = Math.max(0L, cooldownMillis);
        int safeMaximum = Math.max(1, maximumCredits);

        if (record == null || now < record.windowStartedAt()
            || now - record.windowStartedAt() >= safeWindow) {
            KillRecord next = new KillRecord(now, 1, now);
            return new Decision(true, next.serialize(), Reason.ACCEPTED);
        }
        if (record.creditedKills() >= safeMaximum) {
            return new Decision(false, encoded, Reason.VICTIM_LIMIT);
        }
        if (now - record.lastCreditedAt() < safeCooldown) {
            return new Decision(false, encoded, Reason.COOLDOWN);
        }
        KillRecord next = new KillRecord(record.windowStartedAt(),
            record.creditedKills() + 1, now);
        return new Decision(true, next.serialize(), Reason.ACCEPTED);
    }

    static boolean isExpired(String encoded, long now, long windowMillis) {
        KillRecord record = KillRecord.parse(encoded);
        long safeWindow = Math.max(1L, windowMillis);
        return record == null || now < record.windowStartedAt()
            || now - record.windowStartedAt() >= safeWindow;
    }

    enum Reason {
        ACCEPTED,
        COOLDOWN,
        VICTIM_LIMIT
    }

    record Decision(boolean credited, String nextState, Reason reason) {
    }

    private record KillRecord(long windowStartedAt, int creditedKills, long lastCreditedAt) {
        private static KillRecord parse(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 3) {
                return null;
            }
            try {
                long windowStart = Long.parseLong(parts[0]);
                int count = Integer.parseInt(parts[1]);
                long last = Long.parseLong(parts[2]);
                if (windowStart < 0L || count < 1 || last < windowStart) {
                    return null;
                }
                return new KillRecord(windowStart, count, last);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private String serialize() {
            return windowStartedAt + "|" + creditedKills + "|" + lastCreditedAt;
        }
    }
}
