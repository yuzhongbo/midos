package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import java.util.List;

public record ProcedureMatch(ProcedureTemplate template, double score, List<String> reasons) {

    public ProcedureMatch {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
