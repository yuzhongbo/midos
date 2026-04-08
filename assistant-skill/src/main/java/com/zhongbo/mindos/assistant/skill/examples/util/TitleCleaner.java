package com.zhongbo.mindos.assistant.skill.examples.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Conservative title cleaner: remove common site suffixes, leading noise like "主题:" and
 * pick the most meaningful segment separated by common delimiters.
 *
 * This class is a Spring component and also exposes a static facade so it can be used
 * in static contexts or tests without a Spring container. The static instance contains
 * conservative defaults; when the Spring context initializes this bean it replaces the
 * static instance with the configured one.
 */
@Component
public class TitleCleaner {

    private static TitleCleaner INSTANCE = new TitleCleaner();

    private String[] suffixKeywords = new String[]{"新闻网", "日报", "大洋网", "中国新闻网", "百度百科", "外交部", "频道"};
    private String[] delimiters = new String[]{" — ", " - ", " | ", " : ", "：", "_", "——", "—"};

    public TitleCleaner() {
        // default constructor keeps conservative defaults
    }

    public TitleCleaner(@Value("${mindos.skill.news-search.title-cleaner.site-suffixes:新闻网,日报,大洋网,中国新闻网,百度百科,外交部,频道}") String suffixCsv,
                        @Value("${mindos.skill.news-search.title-cleaner.split-delimiters: — | - | | : |：|_|——|—}") String delimCsv) {
        if (suffixCsv != null && !suffixCsv.isBlank()) {
            this.suffixKeywords = Stream.of(suffixCsv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        if (delimCsv != null && !delimCsv.isBlank()) {
            this.delimiters = Stream.of(delimCsv.split("\\|"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        INSTANCE = this;
    }

    /** Static facade for convenience in static contexts/tests. */
    public static String cleanTitle(String raw) {
        return INSTANCE.cleanTitleImpl(raw);
    }

    private String cleanTitleImpl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // remove leading markers like "主题:" or "主题: " or "主题："
        s = s.replaceFirst("(?i)^(主题|重要新闻|新闻频道)[:：\\s]+", "");

        // trim trailing site suffixes after common separators or directly at end
        for (String kw : suffixKeywords) {
            if (kw == null || kw.isBlank()) continue;
            if (s.endsWith(kw)) {
                s = s.substring(0, s.length() - kw.length()).trim();
            }
            String underscored = "_" + kw;
            if (s.endsWith(underscored)) {
                s = s.substring(0, s.length() - underscored.length()).trim();
            }
            String hyphenated = "-" + kw;
            if (s.endsWith(hyphenated)) {
                s = s.substring(0, s.length() - hyphenated.length()).trim();
            }
        }

        // split by delimiters and pick best segment
        String best = pickBestSegment(s);
        // final cleanup: collapse whitespace
        return best.replaceAll("\\s+", " ").trim();
    }

    private String pickBestSegment(String s) {
        String candidate = s;
        for (String delim : delimiters) {
            if (candidate.contains(delim)) {
                // For underscore/hyphen style separators, prefer the leading segment if it looks like a headline
                if ("_".equals(delim) || " - ".equals(delim) || "-".equals(delim)) {
                    String[] parts = candidate.split(java.util.regex.Pattern.quote(delim));
                    String first = parts[0].trim();
                    if (!first.isBlank() && first.length() >= 2 && !containsSiteKeyword(first)) {
                        return first;
                    }
                }
                String[] parts = candidate.split(java.util.regex.Pattern.quote(delim));
                String chosen = chooseMeaningful(parts);
                if (chosen != null && !chosen.isBlank()) return chosen.trim();
                candidate = parts[0];
            }
        }
        return candidate;
    }

    private String chooseMeaningful(String[] parts) {
        String longest = "";
        String longestNonSite = "";
        for (String p : parts) {
            String t = p.trim();
            if (t.length() > longest.length()) longest = t;
            boolean containsSiteKeyword = containsSiteKeyword(t);
            if (!containsSiteKeyword && t.length() > longestNonSite.length()) {
                longestNonSite = t;
            }
            if (!containsSiteKeyword && t.length() >= 6) {
                if (t.codePoints().anyMatch(Character::isLetter) || t.codePoints().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)) {
                    return t;
                }
            }
        }
        if (!longestNonSite.isBlank()) return longestNonSite;
        return longest.isBlank() ? null : longest;
    }

    private boolean containsSiteKeyword(String t) {
        if (t == null || t.isBlank()) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        for (String kw : suffixKeywords) {
            if (kw == null || kw.isBlank()) continue;
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}

