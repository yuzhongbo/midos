package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

import java.util.List;
import java.util.Map;

public interface ProcedureMemoryEngine {

    void recordSuccessfulGraph(String userId,
                               String intent,
                               String trigger,
                               TaskGraph graph,
                               Map<String, Object> contextAttributes);

    List<ProcedureMatch> matchTemplates(String userId,
                                        String userInput,
                                        String suggestedTarget,
                                        int limit);
}
