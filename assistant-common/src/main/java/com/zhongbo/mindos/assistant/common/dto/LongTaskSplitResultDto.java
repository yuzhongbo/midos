package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record LongTaskSplitResultDto(
        LongTaskDto parentTask,
        List<LongTaskDto> childTasks
) {
}
