package com.softino.notifier.kavenegar.utils;

import java.util.List;

/** Kavenegar-compatible string join helper. */
public final class StringUtils {

    private StringUtils() {
    }

    public static String join(CharSequence delimiter, List<?> elements) {
        if (elements == null || elements.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(elements.get(i));
        }
        return sb.toString();
    }
}
