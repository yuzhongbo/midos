package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class RealtimeIntentHeuristics {

    private static final List<String> REALTIME_DOMAIN_TERMS = List.of(
            "天气", "气温", "空气质量", "pm2.5", "aqi",
            "新闻", "资讯", "快讯", "头条", "热搜", "热闻",
            "汇率", "股价", "行情", "油价", "路况", "拥堵",
            "航班", "列车", "高铁", "火车", "机票", "车票", "延误", "到达", "出发",
            "比赛", "比分", "赛事", "赛程", "直播",
            "股票", "基金", "外汇", "指数", "大盘", "市场",
            "旅行", "行程", "出行", "交通", "天气预报"
    );

    private static final List<String> SEMANTIC_REALTIME_SKILL_HINTS = List.of(
            "news_search", "bravesearch", "qwensearch", "weather", "weather_query", "weather.query",
            "flight", "flight_status", "train", "train_status", "traffic", "traffic_query",
            "stock", "stock_price", "exchange", "exchange_rate", "market", "travel", "travel_query"
    );

    private static final List<String> SEMANTIC_HISTORY_HINTS = List.of(
            "总结", "回顾", "复盘", "历史", "过去", "昨天", "前天", "上周", "上月", "去年", "行程回顾", "旅行回顾"
    );

    private static final List<String> FRESHNESS_HINTS = List.of(
            "最新", "最近", "近期", "刚刚", "当前", "现在", "今日", "今天",
            "实时", "当下", "此刻", "最新的", "近况", "now", "current", "latest", "recent", "today"
    );

    private static final List<String> LOOKUP_HINTS = List.of(
            "查一下", "查询", "搜索", "搜一下", "帮我查", "帮我找", "看看", "找一下", "获取", "了解", "查找"
    );

    private static final List<String> QUESTION_HINTS = List.of(
            "怎么样", "如何", "多少", "几时", "几点", "哪儿", "哪里", "什么情况", "是否", "会不会", "能不能"
    );

    private static final List<String> HISTORICAL_HINTS = List.of(
            "昨天", "前天", "上周", "上月", "去年", "历史", "回顾", "复盘", "曾经", "以前", "过去"
    );

    private static final List<String> WEATHER_HINTS = List.of(
            "天气", "气温", "空气质量", "pm2.5", "aqi", "weather", "forecast", "降雨", "下雨", "下雪"
    );

    private static final List<String> NEWS_HINTS = List.of(
            "新闻", "资讯", "快讯", "头条", "热搜", "热闻", "news"
    );

    private static final List<String> MARKET_HINTS = List.of(
            "汇率", "股价", "行情", "油价", "股票", "基金", "外汇", "指数", "大盘", "市场", "stock", "market", "exchange"
    );

    private static final List<String> TRAVEL_HINTS = List.of(
            "航班", "列车", "高铁", "火车", "机票", "车票", "延误", "到达", "出发", "路况", "拥堵", "交通", "出行", "旅行", "行程", "flight", "train", "traffic", "travel"
    );

    private enum IntentDomain {
        WEATHER,
        NEWS,
        MARKET,
        TRAVEL,
        OTHER
    }

    enum MatchKind {
        BLANK,
        HISTORICAL,
        CONFIGURED_TERM,
        SEMANTIC_HISTORICAL,
        SEMANTIC_FRESHNESS,
        SEMANTIC_LOOKUP,
        SEMANTIC_QUESTION,
        SEMANTIC_DOMAIN,
        SEMANTIC_WEATHER,
        SEMANTIC_NEWS,
        SEMANTIC_MARKET,
        SEMANTIC_TRAVEL,
        DOMAIN_FRESHNESS,
        DOMAIN_LOOKUP,
        DOMAIN_QUESTION,
        DOMAIN_COMPACT,
        DOMAIN_WEATHER,
        DOMAIN_NEWS,
        DOMAIN_MARKET,
        DOMAIN_TRAVEL,
        DOMAIN_ONLY,
        NON_REALTIME
    }

    private RealtimeIntentHeuristics() {
    }

    static boolean isRealtimeIntent(String userInput, Collection<String> configuredTerms) {
        return analyze(userInput, configuredTerms, null).realtime();
    }

    static boolean isRealtimeIntent(String userInput, Collection<String> configuredTerms, SemanticAnalysisResult semanticAnalysis) {
        return analyze(userInput, configuredTerms, semanticAnalysis).realtime();
    }

    static boolean isRealtimeLikeInput(String userInput, Collection<String> configuredTerms) {
        return analyze(userInput, configuredTerms, null).realtime();
    }

    static boolean isRealtimeLikeInput(String userInput, Collection<String> configuredTerms, SemanticAnalysisResult semanticAnalysis) {
        return analyze(userInput, configuredTerms, semanticAnalysis).realtime();
    }

    static RealtimeIntentSignal analyze(String userInput, Collection<String> configuredTerms) {
        return analyze(userInput, configuredTerms, null);
    }

    static RealtimeIntentSignal analyze(String userInput, Collection<String> configuredTerms, SemanticAnalysisResult semanticAnalysis) {
        RealtimeIntentSignal semanticSignal = analyzeSemantic(semanticAnalysis, configuredTerms);
        if (semanticSignal != null) {
            return semanticSignal;
        }

        String raw = userInput == null ? "" : userInput.trim();
        if (raw.isBlank()) {
            return new RealtimeIntentSignal(false, MatchKind.BLANK, "blank-input");
        }

        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            return new RealtimeIntentSignal(false, MatchKind.BLANK, "blank-input");
        }

        IntentDomain domain = classifyDomain(normalized);
        boolean configuredHit = containsAny(normalized, configuredTerms);
        boolean domainHit = domain != IntentDomain.OTHER || configuredHit;
        boolean freshnessHit = containsAny(normalized, FRESHNESS_HINTS);
        boolean lookupHit = containsAny(normalized, LOOKUP_HINTS);
        boolean questionHit = raw.contains("?") || raw.contains("？") || containsAny(normalized, QUESTION_HINTS);
        boolean historicalHit = containsAny(normalized, HISTORICAL_HINTS);
        boolean compactQuery = normalized.length() <= 12;

        if (historicalHit && !freshnessHit && !lookupHit && !questionHit) {
            return new RealtimeIntentSignal(false, MatchKind.HISTORICAL, "historical-query");
        }

        if (domain != IntentDomain.OTHER) {
            return new RealtimeIntentSignal(true, matchKindForDomain(domain, false), "domain-" + domain.name().toLowerCase(Locale.ROOT));
        }
        if (configuredHit) {
            return new RealtimeIntentSignal(true, MatchKind.CONFIGURED_TERM, "configured-term");
        }
        if (domainHit) {
            if (freshnessHit) {
                return new RealtimeIntentSignal(true, MatchKind.DOMAIN_FRESHNESS, "domain+freshness");
            }
            if (lookupHit) {
                return new RealtimeIntentSignal(true, MatchKind.DOMAIN_LOOKUP, "domain+lookup");
            }
            if (questionHit) {
                return new RealtimeIntentSignal(true, MatchKind.DOMAIN_QUESTION, "domain+question");
            }
            if (compactQuery) {
                return new RealtimeIntentSignal(true, MatchKind.DOMAIN_COMPACT, "compact-domain-query");
            }
            return new RealtimeIntentSignal(true, MatchKind.DOMAIN_ONLY, "domain-signal");
        }
        return new RealtimeIntentSignal(false, MatchKind.NON_REALTIME, "insufficient-realtime-signals");
    }

    private static RealtimeIntentSignal analyzeSemantic(SemanticAnalysisResult semanticAnalysis, Collection<String> configuredTerms) {
        if (semanticAnalysis == null) {
            return null;
        }
        String semanticText = buildSemanticText(semanticAnalysis);
        if (semanticText.isBlank()) {
            return null;
        }

        IntentDomain domain = classifyDomain(semanticText);
        boolean historicalHit = containsAny(semanticText, HISTORICAL_HINTS) || containsAny(semanticText, SEMANTIC_HISTORY_HINTS);
        boolean freshnessHit = containsAny(semanticText, FRESHNESS_HINTS);
        boolean lookupHit = containsAny(semanticText, LOOKUP_HINTS);
        boolean questionHit = semanticText.contains("?") || semanticText.contains("？") || containsAny(semanticText, QUESTION_HINTS);
        boolean realtimeDomainHit = domain != IntentDomain.OTHER
                || containsAny(semanticText, configuredTerms)
                || containsAny(semanticText, SEMANTIC_REALTIME_SKILL_HINTS)
                || hasRealtimeSemanticSkill(semanticAnalysis);

        if (historicalHit && !freshnessHit && !lookupHit && !questionHit) {
            return new RealtimeIntentSignal(false, MatchKind.SEMANTIC_HISTORICAL, "semantic-historical");
        }
        if (!realtimeDomainHit) {
            return null;
        }
        if (domain != IntentDomain.OTHER) {
            return new RealtimeIntentSignal(true, matchKindForDomain(domain, true), "semantic-domain-" + domain.name().toLowerCase(Locale.ROOT));
        }
        if (freshnessHit) {
            return new RealtimeIntentSignal(true, MatchKind.SEMANTIC_FRESHNESS, "semantic-domain+freshness");
        }
        if (lookupHit) {
            return new RealtimeIntentSignal(true, MatchKind.SEMANTIC_LOOKUP, "semantic-domain+lookup");
        }
        if (questionHit) {
            return new RealtimeIntentSignal(true, MatchKind.SEMANTIC_QUESTION, "semantic-domain+question");
        }
        return new RealtimeIntentSignal(true, MatchKind.SEMANTIC_DOMAIN, "semantic-domain");
    }

    private static IntentDomain classifyDomain(String text) {
        if (text == null || text.isBlank()) {
            return IntentDomain.OTHER;
        }
        if (containsAny(text, WEATHER_HINTS)) {
            return IntentDomain.WEATHER;
        }
        if (containsAny(text, NEWS_HINTS)) {
            return IntentDomain.NEWS;
        }
        if (containsAny(text, TRAVEL_HINTS)) {
            return IntentDomain.TRAVEL;
        }
        if (containsAny(text, MARKET_HINTS)) {
            return IntentDomain.MARKET;
        }
        return IntentDomain.OTHER;
    }

    private static MatchKind matchKindForDomain(IntentDomain domain, boolean semantic) {
        return switch (domain) {
            case WEATHER -> semantic ? MatchKind.SEMANTIC_WEATHER : MatchKind.DOMAIN_WEATHER;
            case NEWS -> semantic ? MatchKind.SEMANTIC_NEWS : MatchKind.DOMAIN_NEWS;
            case MARKET -> semantic ? MatchKind.SEMANTIC_MARKET : MatchKind.DOMAIN_MARKET;
            case TRAVEL -> semantic ? MatchKind.SEMANTIC_TRAVEL : MatchKind.DOMAIN_TRAVEL;
            case OTHER -> semantic ? MatchKind.SEMANTIC_DOMAIN : MatchKind.DOMAIN_ONLY;
        };
    }

    private static boolean hasRealtimeSemanticSkill(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return false;
        }
        if (isRealtimeSemanticSkillName(semanticAnalysis.suggestedSkill())) {
            return true;
        }
        for (SemanticAnalysisResult.CandidateIntent candidate : semanticAnalysis.candidateIntents()) {
            if (candidate != null && isRealtimeSemanticSkillName(candidate.intent())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRealtimeSemanticSkillName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return containsAny(skillName.toLowerCase(Locale.ROOT), SEMANTIC_REALTIME_SKILL_HINTS);
    }

    private static String buildSemanticText(SemanticAnalysisResult semanticAnalysis) {
        StringBuilder builder = new StringBuilder(192);
        appendIfHasText(builder, semanticAnalysis.intent());
        appendIfHasText(builder, semanticAnalysis.suggestedSkill());
        appendIfHasText(builder, semanticAnalysis.rewrittenInput());
        appendIfHasText(builder, semanticAnalysis.summary());
        for (String keyword : semanticAnalysis.keywords()) {
            appendIfHasText(builder, keyword);
        }
        semanticAnalysis.payload().forEach((key, value) -> {
            appendIfHasText(builder, key);
            appendIfHasText(builder, String.valueOf(value));
        });
        for (SemanticAnalysisResult.CandidateIntent candidate : semanticAnalysis.candidateIntents()) {
            if (candidate != null) {
                appendIfHasText(builder, candidate.intent());
            }
        }
        return builder.toString();
    }

    private static void appendIfHasText(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value.trim());
    }

    private static boolean containsAny(String text, Collection<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    record RealtimeIntentSignal(boolean realtime, MatchKind kind, String reason) {
    }
}

