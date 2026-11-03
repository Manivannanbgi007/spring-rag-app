package com.example.ragapp.privacy;

import java.util.regex.Pattern;

/**
 * The kinds of sensitive data we look for. The ORDER matters: the first types are replaced first,
 * so that, for example, a card number is not mistaken for a phone number.
 *
 * "label" is the name inside the placeholder, for example [EMAIL_1].
 * These are patterns, so they find the usual formats but cannot find everything.
 */
enum PiiType {

    // "password: abc123", "api key = xyz", and well-known key formats (OpenAI, AWS, GitHub, JWT)
    SECRET("SECRET", false,
            "(?i)\\b(?:password|passwd|pwd|passcode|api[_ -]?key|secret|token)\\b\\s*(?:is\\s+|[:=]\\s*)\\S+"
                    + "|\\bsk-[A-Za-z0-9_-]{16,}\\b|\\bAKIA[0-9A-Z]{16}\\b|\\bghp_[A-Za-z0-9]{30,}\\b"
                    + "|\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),

    EMAIL("EMAIL", false,
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"),

    // 13 to 19 digits, optionally separated by spaces or dashes. Only real card numbers (Luhn check) are masked.
    CARD("CARD", true,
            "\\b(?:\\d[ -]?){12,18}\\d\\b"),

    // Aadhaar (India): 12 digits in groups of 4
    AADHAAR("ID", false,
            "\\b[2-9]\\d{3}\\s\\d{4}\\s\\d{4}\\b"),

    // US social security number
    SSN("ID", false,
            "\\b\\d{3}-\\d{2}-\\d{4}\\b"),

    // PAN (India): 5 letters, 4 digits, 1 letter
    PAN("ID", false,
            "\\b[A-Z]{5}\\d{4}[A-Z]\\b"),

    // international (+91 98765 43210), US (555-123-4567) and Indian mobile (9876543210) formats
    PHONE("PHONE", false,
            "(?<![\\w.+])\\+\\d{1,3}[\\s.-]?\\(?\\d{1,4}\\)?(?:[\\s.-]?\\d{2,5}){2,4}(?!\\w)"
                    + "|(?<![\\w.])\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}(?!\\w)"
                    + "|(?<![\\w.-])[6-9]\\d{9}(?!\\w)");

    private final String label;
    private final boolean checkLuhn;
    private final Pattern pattern;

    PiiType(String label, boolean checkLuhn, String regex) {
        this.label = label;
        this.checkLuhn = checkLuhn;
        this.pattern = Pattern.compile(regex);
    }

    String label() {
        return label;
    }

    Pattern pattern() {
        return pattern;
    }

    /** False for a match that only looks like this type (for example a long number that is not a card). */
    boolean accepts(String match) {
        return !checkLuhn || passesLuhnCheck(match);
    }

    /** The checksum that every real card number satisfies. It filters out most other long numbers. */
    private static boolean passesLuhnCheck(String text) {
        String digits = text.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
