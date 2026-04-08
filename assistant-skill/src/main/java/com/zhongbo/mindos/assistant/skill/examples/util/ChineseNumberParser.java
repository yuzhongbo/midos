package com.zhongbo.mindos.assistant.skill.examples.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight Chinese numeral parser for common cases used in skill inputs.
 * Supports simple Arabic numbers, single-character numerals (一二三...), 两 -> 2,
 * and unit forms up to 万 (e.g., 一万二千三百四十五).
 */
public final class ChineseNumberParser {

    private static final Map<Character, Integer> SIMPLE = new HashMap<>();

    static {
        SIMPLE.put('零', 0);
        SIMPLE.put('一', 1);
        SIMPLE.put('二', 2);
        SIMPLE.put('两', 2);
        SIMPLE.put('三', 3);
        SIMPLE.put('四', 4);
        SIMPLE.put('五', 5);
        SIMPLE.put('六', 6);
        SIMPLE.put('七', 7);
        SIMPLE.put('八', 8);
        SIMPLE.put('九', 9);
        // '十' is a unit (handled separately), do not include as a simple digit here
    }

    private ChineseNumberParser() {}

    /**
     * Parse a flexible numeric string which may contain Arabic digits or Chinese numerals.
     * Returns null if parsing not possible.
     */
    public static Integer parseFlexibleNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // strip common suffixes like 周, 周期, 周数, 小时, 小时/周
        s = s.replaceAll("(?i)周|周期|周数|小时|小时/周|小时/周|周/小时|week|weeks", "");
        s = s.trim();

        // Try direct integer parse
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
        }

        // If it's a single chinese digit or two-character like 十二
        int result = 0;
        try {
            result = parseChineseInteger(s);
            return result == Integer.MIN_VALUE ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    // parse up to 99999 (万位). returns Integer.MIN_VALUE if cannot parse
    private static int parseChineseInteger(String s) {
        if (s == null || s.isBlank()) return Integer.MIN_VALUE;
        // quick handle if contains only simple char like "十" or "两"
        if (s.length() == 1) {
            Character c = s.charAt(0);
            Integer v = SIMPLE.get(c);
            if (v != null) return v;
        }

        int total = 0;
        int section = 0; // accumulates within 万-section
        int number = 0;

        char[] chars = s.toCharArray();
        for (char ch : chars) {
            if (SIMPLE.containsKey(ch)) {
                number = SIMPLE.get(ch);
            } else if (ch == '万') {
                section = (section + (number == 0 ? 0 : number)) * 10000;
                total += section;
                section = 0;
                number = 0;
            } else if (ch == '千') {
                section += (number == 0 ? 1 : number) * 1000;
                number = 0;
            } else if (ch == '百') {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
            } else if (ch == '十') {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else {
                return Integer.MIN_VALUE;
            }
        }

        section += number;
        total += section;
        return total;
    }
}

