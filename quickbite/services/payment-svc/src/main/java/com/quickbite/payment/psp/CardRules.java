package com.quickbite.payment.psp;

public final class CardRules {
    private CardRules() {}

    public static String normalize(String raw) { return raw == null ? "" : raw.replaceAll("[\\s-]", ""); }

    /** Luhn mod-10: catches typos and every single-digit error, and most transpositions. */
    public static boolean luhnValid(String pan) {
        if (!pan.matches("\\d{13,19}")) return false;
        int sum = 0;
        for (int i = 0; i < pan.length(); i++) {
            int d = pan.charAt(pan.length() - 1 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        return sum % 10 == 0;
    }

    public static String brand(String pan) {
        if (pan.startsWith("4")) return "VISA";
        if (pan.matches("^(5[1-5]|2(2[2-9]|[3-6]\\d|7[01]|720)).*")) return "MASTERCARD";
        if (pan.matches("^3[47].*")) return "AMEX";
        if (pan.matches("^(60|65|81|82|508).*")) return "RUPAY";
        return "UNKNOWN";
    }

    public static String mask(String pan) { return pan.length() < 4 ? "****" : "****" + pan.substring(pan.length() - 4); }
}