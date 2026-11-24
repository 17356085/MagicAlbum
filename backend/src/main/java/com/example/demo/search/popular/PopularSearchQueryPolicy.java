package com.example.demo.search.popular;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps the public popular list limited to technical-looking, non-personal terms.
 * The normalized value is the case-insensitive idempotency key; display is retained
 * only so the first accepted spelling can be returned to clients.
 */
public final class PopularSearchQueryPolicy {
    static final int MAX_CODE_POINTS = 96;
    private static final int MAX_INPUT_CODE_POINTS = 256;
    private static final Set<Integer> COMMON_PUNCTUATION = Set.of(
            (int) '.', (int) ',', (int) ':', (int) ';', (int) '?', (int) '!',
            (int) '+', (int) '-', (int) '#', (int) '/', (int) '_', (int) '=',
            (int) '%', (int) '&', (int) '*', (int) '@', (int) '(', (int) ')',
            (int) '[', (int) ']', (int) '{', (int) '}', (int) '<', (int) '>',
            (int) '\'', (int) '|');
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?:^|\\s)[^\\s@]+@[^\\s@]+\\.[^\\s@]+(?:$|\\s)");
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|ftp://|www\\.|\\blocalhost(?::\\d+|[/\\?#])"
                    + "|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b"
                    + "|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:[/\\?#]|\\b)"
                    + "|\\b[a-z]:/)");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\p{N}{7,}");

    private PopularSearchQueryPolicy() {
    }

    public static Optional<NormalizedQuery> normalize(String raw) {
        if (raw == null || raw.codePointCount(0, raw.length()) > MAX_INPUT_CODE_POINTS) {
            return Optional.empty();
        }

        String display = collapseWhitespace(Normalizer.normalize(raw, Normalizer.Form.NFC));
        if (display.isEmpty() || display.codePointCount(0, display.length()) > MAX_CODE_POINTS
                || !hasLetterOrDigit(display) || containsUnsupportedCharacters(display)
                || EMAIL.matcher(display).find() || URL.matcher(display).find()
                || LONG_DIGIT_RUN.matcher(display).find() || containsLongNumericToken(display)) {
            return Optional.empty();
        }

        String normalized = Normalizer.normalize(display.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        return Optional.of(new NormalizedQuery(display, normalized));
    }

    private static String collapseWhitespace(String input) {
        StringBuilder output = new StringBuilder(input.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = output.length() > 0;
                continue;
            }
            if (pendingSpace) {
                output.append(' ');
                pendingSpace = false;
            }
            output.appendCodePoint(codePoint);
        }
        return output.toString();
    }

    private static boolean hasLetterOrDigit(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isLetter(codePoint) || Character.isDigit(codePoint));
    }

    private static boolean containsUnsupportedCharacters(String value) {
        return value.codePoints().anyMatch(codePoint ->
                !Character.isLetterOrDigit(codePoint)
                        && !Character.isWhitespace(codePoint)
                        && !COMMON_PUNCTUATION.contains(codePoint));
    }

    private static boolean containsLongNumericToken(String value) {
        for (String token : value.split(" ")) {
            int digits = 0;
            int letters = 0;
            int alphaNumeric = 0;
            for (int codePoint : token.codePoints().toArray()) {
                if (Character.isDigit(codePoint)) {
                    digits++;
                    alphaNumeric++;
                } else if (Character.isLetter(codePoint)) {
                    letters++;
                    alphaNumeric++;
                }
            }
            if (digits >= 7 && digits * 2 >= alphaNumeric && letters <= 2) {
                return true;
            }
        }
        return false;
    }

    public record NormalizedQuery(String display, String normalized) {
    }
}
