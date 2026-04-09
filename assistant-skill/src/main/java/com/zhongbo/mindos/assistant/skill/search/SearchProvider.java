package com.zhongbo.mindos.assistant.skill.search;

import java.util.List;

public interface SearchProvider {

    boolean supports(SearchSourceConfig source);

    List<SearchResultItem> search(SearchSourceConfig source, SearchRequest request) throws Exception;
}
