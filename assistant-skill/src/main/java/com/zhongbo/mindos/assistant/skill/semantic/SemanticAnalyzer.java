package com.zhongbo.mindos.assistant.skill.semantic;

import java.util.List;
import java.util.Map;

public interface SemanticAnalyzer {

    SemanticAnalysisResult analyze(String userId,
                                   String userInput,
                                   String memoryContext,
                                   Map<String, Object> profileContext,
                                   List<String> availableSkillSummaries);
}
