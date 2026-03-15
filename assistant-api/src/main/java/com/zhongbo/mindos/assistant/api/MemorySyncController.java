package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ProceduralMemoryEntryDto;
import com.zhongbo.mindos.assistant.common.dto.SemanticMemoryEntryDto;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemorySyncController {

    private final MemoryManager memoryManager;

    public MemorySyncController(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @GetMapping("/{userId}/sync")
    public MemorySyncResponseDto fetchUpdates(@PathVariable String userId,
                                              @RequestParam(defaultValue = "0") long since,
                                              @RequestParam(defaultValue = "100") int limit) {
        MemorySyncSnapshot snapshot = memoryManager.fetchIncrementalUpdates(userId, since, limit);
        return toResponse(snapshot, 0, 0);
    }

    @PostMapping("/{userId}/sync")
    public MemorySyncResponseDto applyUpdates(@PathVariable String userId,
                                              @RequestBody MemorySyncRequestDto request,
                                              @RequestParam(defaultValue = "100") int limit) {
        MemoryApplyResult applyResult = memoryManager.applyIncrementalUpdates(userId, new MemorySyncBatch(
                request.eventId(),
                toConversationTurns(request.episodic()),
                toSemanticEntries(request.semantic()),
                toProceduralEntries(request.procedural())
        ));
        MemorySyncSnapshot snapshot = memoryManager.fetchIncrementalUpdates(userId, 0L, limit);
        return toResponse(snapshot, applyResult.acceptedCount(), applyResult.skippedCount());
    }

    private MemorySyncResponseDto toResponse(MemorySyncSnapshot snapshot, int acceptedCount, int skippedCount) {
        return new MemorySyncResponseDto(
                snapshot.cursor(),
                acceptedCount,
                skippedCount,
                snapshot.episodic().stream().map(this::toDto).toList(),
                snapshot.semantic().stream().map(this::toDto).toList(),
                snapshot.procedural().stream().map(this::toDto).toList()
        );
    }

    private List<ConversationTurn> toConversationTurns(List<ConversationTurnDto> turns) {
        return turns.stream().map(this::toModel).toList();
    }

    private List<SemanticMemoryEntry> toSemanticEntries(List<SemanticMemoryEntryDto> entries) {
        return entries.stream().map(this::toModel).toList();
    }

    private List<ProceduralMemoryEntry> toProceduralEntries(List<ProceduralMemoryEntryDto> entries) {
        return entries.stream().map(this::toModel).toList();
    }

    private ConversationTurn toModel(ConversationTurnDto dto) {
        return new ConversationTurn(dto.role(), dto.content(), dto.createdAt());
    }

    private SemanticMemoryEntry toModel(SemanticMemoryEntryDto dto) {
        return new SemanticMemoryEntry(dto.text(), dto.embedding(), dto.createdAt());
    }

    private ProceduralMemoryEntry toModel(ProceduralMemoryEntryDto dto) {
        return new ProceduralMemoryEntry(dto.skillName(), dto.input(), dto.success(), dto.createdAt());
    }

    private ConversationTurnDto toDto(ConversationTurn turn) {
        return new ConversationTurnDto(turn.role(), turn.content(), turn.createdAt());
    }

    private SemanticMemoryEntryDto toDto(SemanticMemoryEntry entry) {
        return new SemanticMemoryEntryDto(entry.text(), entry.embedding(), entry.createdAt());
    }

    private ProceduralMemoryEntryDto toDto(ProceduralMemoryEntry entry) {
        return new ProceduralMemoryEntryDto(entry.skillName(), entry.input(), entry.success(), entry.createdAt());
    }
}

