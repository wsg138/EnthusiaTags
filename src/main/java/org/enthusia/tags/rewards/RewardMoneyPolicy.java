package org.enthusia.tags.rewards;

final class RewardMoneyPolicy {
    static final double MAX_AMOUNT = 5000D;

    private RewardMoneyPolicy() {
    }

    static String validationError(double amount) {
        if (!Double.isFinite(amount) || amount <= 0D) {
            return "money amount must be finite and greater than zero";
        }
        if (amount > MAX_AMOUNT) {
            return "money amount exceeds the 5000 raw-gold reward limit";
        }
        return null;
    }
}
