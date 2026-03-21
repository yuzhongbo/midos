package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanResponseDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionStepDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.common.dto.PendingPreferenceOverrideDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileExplainDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileDto;
import com.zhongbo.mindos.assistant.common.dto.ProceduralMemoryEntryDto;
import com.zhongbo.mindos.assistant.common.dto.SemanticMemoryEntryDto;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.PendingPreferenceOverride;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfileExplain;
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
        return toResponse(snapshot, 0, 0, 0, 0, 0);
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
        return toResponse(snapshot,
                applyResult.acceptedCount(),
                applyResult.skippedCount(),
                applyResult.deduplicatedCount(),
                applyResult.keySignalInputCount(),
                applyResult.keySignalStoredCount());
    }

    @GetMapping("/{userId}/persona")
    public PersonaProfileDto getPersonaProfile(@PathVariable String userId) {
        return toDto(memoryManager.getPreferenceProfile(userId));
    }

    @GetMapping("/{userId}/persona/explain")
    public PersonaProfileExplainDto getPersonaProfileExplain(@PathVariable String userId) {
        return toDto(memoryManager.getPreferenceProfileExplain(userId));
    }

    @GetMapping("/{userId}/style")
    public MemoryStyleProfileDto getMemoryStyle(@PathVariable String userId) {
        return toDto(memoryManager.getMemoryStyleProfile(userId));
    }

    @PostMapping("/{userId}/style")
    public MemoryStyleProfileDto updateMemoryStyle(@PathVariable String userId,
                                                   @RequestBody MemoryStyleProfileDto request,
                                                   @RequestParam(defaultValue = "false") boolean autoTune,
                                                   @RequestParam(required = false) String sampleText) {
        MemoryStyleProfile updated = memoryManager.updateMemoryStyleProfile(userId, toModel(request), autoTune, sampleText);
        return toDto(updated);
    }

    @PostMapping("/{userId}/compress-plan")
    public MemoryCompressionPlanResponseDto buildCompressionPlan(@PathVariable String userId,
                                                                 @RequestBody MemoryCompressionPlanRequestDto request) {
        MemoryStyleProfile overrideStyle = new MemoryStyleProfile(request.styleName(), request.tone(), request.outputFormat());
        String sourceText = request.sourceText();
        if (sourceText == null || sourceText.isBlank()) {
            sourceText = memoryManager.getRecentConversation(userId, 8).stream()
                    .map(turn -> turn.role() + ": " + turn.content())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("暂无可压缩记忆");
        }
        MemoryCompressionPlan plan = memoryManager.buildMemoryCompressionPlan(userId, sourceText, overrideStyle, request.focus());
        return toDto(plan);
    }

    private MemorySyncResponseDto toResponse(MemorySyncSnapshot snapshot,
                                             int acceptedCount,
                                             int skippedCount,
                                             int deduplicatedCount,
                                             int keySignalInputCount,
                                             int keySignalStoredCount) {
        return new MemorySyncResponseDto(
                snapshot.cursor(),
                acceptedCount,
                skippedCount,
                deduplicatedCount,
                keySignalInputCount,
                keySignalStoredCount,
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

    private MemoryStyleProfile toModel(MemoryStyleProfileDto dto) {
        if (dto == null) {
            return new MemoryStyleProfile(null, null, null);
        }
        return new MemoryStyleProfile(dto.styleName(), dto.tone(), dto.outputFormat());
    }

    private MemoryStyleProfileDto toDto(MemoryStyleProfile style) {
        return new MemoryStyleProfileDto(style.styleName(), style.tone(), style.outputFormat());
    }

    private MemoryCompressionPlanResponseDto toDto(MemoryCompressionPlan plan) {
        List<MemoryCompressionStepDto> steps = plan.steps().stream().map(this::toDto).toList();
        return new MemoryCompressionPlanResponseDto(toDto(plan.styleProfile()), steps, plan.createdAt());
    }

    private PersonaProfileDto toDto(PreferenceProfile profile) {
        return new PersonaProfileDto(
                profile.assistantName(),
                profile.role(),
                profile.style(),
                profile.language(),
                profile.timezone(),
                profile.preferredChannel()
        );
    }

    private PersonaProfileExplainDto toDto(PreferenceProfileExplain explain) {
        return new PersonaProfileExplainDto(
                toDto(explain.confirmedProfile()),
                explain.pendingOverrides().stream().map(this::toDto).toList()
        );
    }

    private PendingPreferenceOverrideDto toDto(PendingPreferenceOverride pending) {
        return new PendingPreferenceOverrideDto(
                pending.field(),
                pending.pendingValue(),
                pending.count(),
                pending.confirmThreshold(),
                pending.remainingConfirmTurns()
        );
    }

    private MemoryCompressionStepDto toDto(MemoryCompressionStep step) {
        return new MemoryCompressionStepDto(step.stage(), step.content(), step.length());
    }
}

