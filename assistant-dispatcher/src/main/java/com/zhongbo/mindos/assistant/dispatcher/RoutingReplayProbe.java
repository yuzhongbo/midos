package com.zhongbo.mindos.assistant.dispatcher;

final class RoutingReplayProbe {

    private String ruleCandidate = "NONE";
    private String preAnalyzeCandidate = "NOT_RUN";

    String ruleCandidate() {
        return ruleCandidate;
    }

    void setRuleCandidate(String ruleCandidate) {
        this.ruleCandidate = ruleCandidate == null || ruleCandidate.isBlank() ? "NONE" : ruleCandidate;
    }

    String preAnalyzeCandidate() {
        return preAnalyzeCandidate;
    }

    void setPreAnalyzeCandidate(String preAnalyzeCandidate) {
        this.preAnalyzeCandidate = preAnalyzeCandidate == null || preAnalyzeCandidate.isBlank()
                ? "NOT_RUN"
                : preAnalyzeCandidate;
    }
}
