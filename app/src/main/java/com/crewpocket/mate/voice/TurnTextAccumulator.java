package com.crewpocket.mate.voice;

/**
 * Collects streaming transcript/model text into one human-readable turn.
 * Handles true deltas, cumulative partial transcripts, and overlapping chunks.
 */
public final class TurnTextAccumulator {
    private final StringBuilder buffer = new StringBuilder();

    public synchronized void append(String chunk) {
        String value = chunk == null ? "" : chunk.trim();
        if (value.isEmpty()) return;
        if (buffer.length() == 0) {
            buffer.append(value);
            return;
        }

        String current = buffer.toString();
        if (value.equals(current) || current.endsWith(value)) return;
        if (value.startsWith(current)) {
            buffer.setLength(0);
            buffer.append(value);
            return;
        }

        int overlap = largestOverlap(current, value);
        String remainder = overlap > 0 ? value.substring(overlap) : value;
        if (remainder.isEmpty()) return;
        if (overlap == 0 && needsSpace(buffer.charAt(buffer.length() - 1), remainder.charAt(0))) {
            buffer.append(' ');
        }
        buffer.append(remainder);
    }

    public synchronized String value() {
        return buffer.toString().trim();
    }

    public synchronized String take() {
        String value = buffer.toString().trim();
        buffer.setLength(0);
        return value;
    }

    public synchronized void clear() {
        buffer.setLength(0);
    }

    public synchronized boolean isEmpty() {
        return buffer.length() == 0;
    }

    private static int largestOverlap(String current, String next) {
        int max = Math.min(current.length(), next.length());
        for (int size = max; size > 0; size--) {
            if (current.regionMatches(current.length() - size, next, 0, size)) return size;
        }
        return 0;
    }

    private static boolean needsSpace(char previous, char next) {
        if (Character.isWhitespace(previous) || Character.isWhitespace(next)) return false;
        if (isCjk(previous) || isCjk(next)) return false;
        if (isClosingPunctuation(next)) return false;
        if (isOpeningPunctuation(previous)) return false;
        return true;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static boolean isClosingPunctuation(char c) {
        return ".,!?;:%)]}>，。！？；：、」』）》】…\"'".indexOf(c) >= 0;
    }

    private static boolean isOpeningPunctuation(char c) {
        return "([{<（「『《【\"'".indexOf(c) >= 0;
    }
}
