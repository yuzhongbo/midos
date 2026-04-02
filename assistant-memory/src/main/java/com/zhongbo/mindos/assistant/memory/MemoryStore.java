package com.zhongbo.mindos.assistant.memory;

import java.util.List;

public interface MemoryStore {

    void save(MemoryRecord record);

    List<MemoryRecord> query(MemoryQuery query);
}
