package com.crewpocket.mate.agent;

import java.util.Locale;

public class DecisionGate {
    public static class Result {
        public final boolean requiresApproval;
        public final String reason;

        Result(boolean requiresApproval, String reason) {
            this.requiresApproval = requiresApproval;
            this.reason = reason;
        }
    }

    public Result evaluate(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.US);
        if (containsAny(value, "$", "usd", "twd", "baht", "price", "cost", "fee", "pay", "payment", "charge")) {
            return new Result(true, "This message involves money or pricing.");
        }
        if (containsAny(value, "cancel", "refund", "sign", "contract", "agree to", "commit", "promise", "guarantee")) {
            return new Result(true, "This message creates or changes an important commitment.");
        }
        if (containsAny(value, "passport", "password", "credit card", "bank account", "otp", "verification code")) {
            return new Result(true, "This message may expose sensitive information.");
        }
        return new Result(false, "Low-risk communication.");
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }
}
