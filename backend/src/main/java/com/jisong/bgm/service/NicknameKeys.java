package com.jisong.bgm.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NicknameKeys {
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NicknameKeys() {
    }

    public static String of(String nickname) {
        if (nickname == null) {
            return "";
        }
        String normalized = Normalizer.normalize(nickname, Normalizer.Form.NFKC);
        normalized = ZERO_WIDTH.matcher(normalized).replaceAll("");
        normalized = WHITESPACE.matcher(normalized.trim()).replaceAll(" ");
        return normalized.toLowerCase(Locale.ROOT);
    }
}
