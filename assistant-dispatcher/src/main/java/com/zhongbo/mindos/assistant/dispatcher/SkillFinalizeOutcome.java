package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;

record SkillFinalizeOutcome(SkillResult result, boolean applied) {

    static SkillFinalizeOutcome notApplied(SkillResult result) {
        return new SkillFinalizeOutcome(result, false);
    }

    static SkillFinalizeOutcome applied(SkillResult result) {
        return new SkillFinalizeOutcome(result, true);
    }
}
