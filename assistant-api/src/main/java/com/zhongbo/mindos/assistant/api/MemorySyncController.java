package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanResponseDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionStepDto;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.common.dto.PendingPreferenceOverrideDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileExplainDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileDto;
import com.zhongbo.mindos.assistant.common.dto.ProceduralMemoryEntryDto;
import com.zhongbo.mindos.assistant.common.dto.SemanticMemoryEntryDto;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
public class MemorySyncController {

    private final MemoryFacade memoryFacade;
    private final MemoryCommandOrchestrator memoryCommandOrchestrator;
    private final boolean retrievePreviewRequireAdminToken;
    private final String adminTokenHeader;
    private final String adminToken;

    public MemorySyncController(MemoryFacade memoryFacade,
                                MemoryCommandOrchestrator memoryCommandOrchestrator,
                                @Value("${mindos.security.memory.retrieve-preview.require-admin-token:false}") boolean retrievePreviewRequireAdminToken,
                                @Value("${mindos.security.risky-ops.admin-token-header:X-MindOS-Admin-Token}") String adminTokenHeader,
                                @Value("${mindos.security.risky-ops.admin-token:}") String adminToken) {
        this.memoryFacade = memoryFacade;
        this.memoryCommandOrchestrator = memoryCommandOrchestrator;
        this.retrievePreviewRequireAdminToken = retrievePreviewRequireAdminToken;
        this.adminTokenHeader = adminTokenHeader == null || adminTokenHeader.isBlank()
                ? "X-MindOS-Admin-Token"
                : adminTokenHeader.trim();
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping("/{userId}/sync")
    public MemorySyncResponseDto fetchUpdates(@PathVariable String userId,
                                              @RequestParam(defaultValue = "0") long since,
                                              @RequestParam(defaultValue = "100") int limit) {
        MemorySyncSnapshot snapshot = memoryFacade.fetchIncrementalUpdates(userId, since, limit);
        return toResponse(snapshot, 0, 0, 0, 0, 0);
    }

    @PostMapping("/{userId}/sync")
    public MemorySyncResponseDto applyUpdates(@PathVariable String userId,
                                              @RequestBody MemorySyncRequestDto request,
                                              @RequestParam(defaultValue = "100") int limit) {
        MemoryApplyResult applyResult = memoryCommandOrchestrator.applyIncrementalUpdates(userId, new MemorySyncBatch(
                request.eventId(),
                toConversationTurns(request.episodic()),
                toSemanticEntries(request.semantic()),
                toProceduralEntries(request.procedural())
        ));
        MemorySyncSnapshot snapshot = memoryFacade.fetchIncrementalUpdates(userId, 0L, limit);
        return toResponse(snapshot,
                applyResult.acceptedCount(),
                applyResult.skippedCount(),
                applyResult.deduplicatedCount(),
                applyResult.keySignalInputCount(),
                applyResult.keySignalStoredCount());
    }

    @GetMapping("/{userId}/retrieve-preview")
    public PromptMemoryContextDto retrievePreview(@PathVariable String userId,
                                                  @RequestParam String query,
                                                  @RequestParam(defaultValue = "1600") int maxChars,
                                                  @RequestParam(required = false) String language,
                                                  @RequestParam(required = false) String timezone,
                                                  @RequestParam(required = false) String style,
                                                  @RequestParam(required = false) String role,
                                                  HttpServletRequest request) {
        validateRetrievePreviewToken(request);
        Map<String, Object> profileContext = new LinkedHashMap<>();
        putIfPresent(profileContext, "language", language);
        putIfPresent(profileContext, "timezone", timezone);
        putIfPresent(profileContext, "style", style);
        putIfPresent(profileContext, "role", role);
        return memoryFacade.buildPromptMemoryContext(userId, query, maxChars, profileContext);
    }

    private void validateRetrievePreviewToken(HttpServletRequest request) {
        if (!retrievePreviewRequireAdminToken) {
            return;
        }
        if (adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "retrieve-preview admin token is not configured");
        }
        String provided = request == null ? null : request.getHeader(adminTokenHeader);
        if (!adminToken.equals(provided)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid admin token");
        }
    }

    @GetMapping("/{userId}/persona")
    public PersonaProfileDto getPersonaProfile(@PathVariable String userId) {
        return toDto(memoryFacade.getPreferenceProfile(userId));
    }

    @GetMapping("/{userId}/persona/explain")
    public PersonaProfileExplainDto getPersonaProfileExplain(@PathVariable String userId) {
        return toDto(memoryFacade.getPreferenceProfileExplain(userId));
    }

    @GetMapping("/{userId}/style")
    public MemoryStyleProfileDto getMemoryStyle(@PathVariable String userId) {
        return toDto(memoryFacade.getMemoryStyleProfile(userId));
    }

    @PostMapping("/{userId}/style")
    public MemoryStyleProfileDto updateMemoryStyle(@PathVariable String userId,
                                                   @RequestBody MemoryStyleProfileDto request,
                                                   @RequestParam(defaultValue = "false") boolean autoTune,
                                                   @RequestParam(required = false) String sampleText) {
        MemoryStyleProfile updated = memoryCommandOrchestrator.updateMemoryStyleProfile(userId, toModel(request), autoTune, sampleText);
        return toDto(updated);
    }

    @PostMapping("/{userId}/compress-plan")
    public MemoryCompressionPlanResponseDto buildCompressionPlan(@PathVariable String userId,
                                                                 @RequestBody MemoryCompressionPlanRequestDto request) {
        MemoryStyleProfile overrideStyle = new MemoryStyleProfile(request.styleName(), request.tone(), request.outputFormat());
        String sourceText = request.sourceText();
        if (sourceText == null || sourceText.isBlank()) {
            sourceText = memoryFacade.getRecentConversation(userId, 8).stream()
                    .map(turn -> turn.role() + ": " + turn.content())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("暂无可压缩记忆");
        }
        MemoryCompressionPlan plan = memoryFacade.buildMemoryCompressionPlan(userId, sourceText, overrideStyle, request.focus());
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

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value.trim());
        }
    }
}
