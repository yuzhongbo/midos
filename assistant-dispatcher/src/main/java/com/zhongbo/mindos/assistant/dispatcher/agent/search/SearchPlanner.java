package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import java.util.List;

public interface SearchPlanner {

    List<SearchCandidate> search(SearchPlanningRequest request);
}
