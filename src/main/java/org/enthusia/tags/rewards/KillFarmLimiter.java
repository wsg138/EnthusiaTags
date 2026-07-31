package org.enthusia.tags.rewards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

final class KillFarmLimiter {
    static final String TRUSTED_KILL_COUNTER = "legitimate_player_kills";
    static final String INITIALIZED_STATE = "anti-farm-kills-initialized";
    static final String PAIR_STATE_PREFIX = "anti-farm-kill:";
    private static final String FORMAT_PREFIX = "v2|";

    private KillFarmLimiter() {
    }

    static Decision evaluate(String encoded, long now, long windowMillis, int maximumCredits) {
        long safeWindow = Math.max(1L, windowMillis);
        int safeMaximum = Math.max(1, maximumCredits);
        List<Long> recent = KillRecord.parse(encoded).recent(now, safeWindow);

        if (recent.size() >= safeMaximum) {
            return new Decision(false, KillRecord.serialize(recent), Reason.VICTIM_LIMIT);
        }

        recent.add(now);
        return new Decision(true, KillRecord.serialize(recent), Reason.ACCEPTED);
    }

    static boolean isExpired(String encoded, long now, long windowMillis) {
        return KillRecord.parse(encoded).recent(now, Math.max(1L, windowMillis)).isEmpty();
    }

    enum Reason {
        ACCEPTED,
        VICTIM_LIMIT
    }

    record Decision(boolean credited, String nextState, Reason reason) {
    }

    private record KillRecord(List<Long> creditedAt) {
        private static KillRecord parse(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return new KillRecord(List.of());
            }
            if (encoded.startsWith(FORMAT_PREFIX)) {
                return parseRolling(encoded.substring(FORMAT_PREFIX.length()));
            }
            return parseLegacy(encoded);
        }

        private static KillRecord parseRolling(String encoded) {
            if (encoded.isBlank()) {
                return new KillRecord(List.of());
            }
            List<Long> timestamps = new ArrayList<>();
            for (String part : encoded.split(",", -1)) {
                try {
                    long timestamp = Long.parseLong(part);
                    if (timestamp < 0L) {
                        return new KillRecord(List.of());
                    }
                    timestamps.add(timestamp);
                } catch (NumberFormatException ex) {
                    return new KillRecord(List.of());
                }
            }
            return new KillRecord(List.copyOf(timestamps));
        }

        private static KillRecord parseLegacy(String encoded) {
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 3) {
                return new KillRecord(List.of());
            }
            try {
                long windowStart = Long.parseLong(parts[0]);
                int count = Integer.parseInt(parts[1]);
                long last = Long.parseLong(parts[2]);
                if (windowStart < 0L || count < 1 || last < windowStart) {
                    return new KillRecord(List.of());
                }
                int boundedCount = Math.min(count, 100);
                List<Long> timestamps = new ArrayList<>(boundedCount);
                for (int index = 0; index < boundedCount; index++) {
                    timestamps.add(last);
                }
                return new KillRecord(List.copyOf(timestamps));
            } catch (NumberFormatException ex) {
                return new KillRecord(List.of());
            }
        }

        private List<Long> recent(long now, long windowMillis) {
            List<Long> recent = new ArrayList<>();
            for (long timestamp : creditedAt) {
                if (timestamp <= now && now - timestamp < windowMillis) {
                    recent.add(timestamp);
                }
            }
            Collections.sort(recent);
            return recent;
        }

        private static String serialize(List<Long> timestamps) {
            StringJoiner joiner = new StringJoiner(",", FORMAT_PREFIX, "");
            for (long timestamp : timestamps) {
                joiner.add(Long.toString(timestamp));
            }
            return joiner.toString();
        }
    }
}
