package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.search.SearchResultDetailAugmentor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebLookupDetailHelper {

    static final String HELPER_TARGET = "helper:web.lookup.detail";

    private final SearchResultDetailAugmentor detailAugmentor;

    public WebLookupDetailHelper() {
        this(new SearchResultDetailAugmentor(true, 4500, 3, 320, 6000));
    }

    @Autowired
    public WebLookupDetailHelper(@Value("${mindos.skill.search.detail-fetch.enabled:true}") boolean detailFetchEnabled,
                                 @Value("${mindos.skill.search.detail-fetch.timeout-ms:4500}") int detailFetchTimeoutMs,
                                 @Value("${mindos.skill.search.detail-fetch.max-candidates:3}") int detailFetchMaxCandidates,
                                 @Value("${mindos.skill.search.detail-fetch.max-summary-chars:320}") int detailFetchMaxSummaryChars,
                                 @Value("${mindos.skill.search.detail-fetch.max-page-chars:6000}") int detailFetchMaxPageChars) {
        this(new SearchResultDetailAugmentor(
                detailFetchEnabled,
                detailFetchTimeoutMs,
                detailFetchMaxCandidates,
                detailFetchMaxSummaryChars,
                detailFetchMaxPageChars
        ));
    }

    WebLookupDetailHelper(SearchResultDetailAugmentor detailAugmentor) {
        this.detailAugmentor = detailAugmentor == null ? SearchResultDetailAugmentor.disabled() : detailAugmentor;
    }

    public SkillResult enrich(String query, String searchOutput) {
        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedOutput = searchOutput == null ? "" : searchOutput.trim();
        if (normalizedOutput.isBlank()) {
            return SkillResult.failure(HELPER_TARGET, "missing search output for web detail enrichment");
        }
        return SkillResult.success(
                HELPER_TARGET,
                detailAugmentor.augmentRenderedSearchOutput(normalizedQuery, normalizedOutput)
        );
    }
}
