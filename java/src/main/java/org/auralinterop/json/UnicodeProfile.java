package org.auralinterop.json;

final class UnicodeProfile {
    private UnicodeProfile() {
    }

    static void validateString(String value) {
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("isolated high surrogate at UTF-16 index " + index);
                }
            } else if (Character.isLowSurrogate(first)) {
                throw new IllegalArgumentException("isolated low surrogate at UTF-16 index " + index);
            }

            int codePoint = value.codePointAt(index);
            if (isNoncharacter(codePoint)) {
                throw new IllegalArgumentException("Unicode noncharacter U+" + Integer.toHexString(codePoint));
            }
            index += Character.charCount(codePoint);
        }
    }

    static boolean isNoncharacter(int codePoint) {
        return (codePoint >= 0xFDD0 && codePoint <= 0xFDEF)
                || (codePoint >= 0 && codePoint <= Character.MAX_CODE_POINT
                && (codePoint & 0xFFFF) >= 0xFFFE);
    }
}
