package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.IncidentAgent;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Incident;
import com.mirror.agent.entity.SceneCategory;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.IncidentSummaryResult;
import com.mirror.agent.service.IncidentService;
import com.mirror.agent.service.IncidentVectorService;
import com.mirror.agent.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定稿轮询器测试：摘要与场景分类同批落 MySQL（finalizeIncident/refreshSummary），
 * 场景随刷新后的实体透传向量写入；LLM 降级时摘要与场景都不覆盖。
 */
class IncidentDraftFinalizerTest {

    private AgentProperties agentProperties;
    private IncidentFinalizeQueue finalizeQueue;
    private IncidentService incidentService;
    private IncidentVectorService vectorService;
    private IncidentMutationService mutationService;
    private IncidentAgent incidentAgent;
    private IncidentDraftFinalizer finalizer;

    @BeforeEach
    void setUp() {
        agentProperties = mock(AgentProperties.class);
        finalizeQueue = mock(IncidentFinalizeQueue.class);
        incidentService = mock(IncidentService.class);
        vectorService = mock(IncidentVectorService.class);
        mutationService = mock(IncidentMutationService.class);
        incidentAgent = mock(IncidentAgent.class);
        TagService tagService = mock(TagService.class);
        finalizer = new IncidentDraftFinalizer(agentProperties, finalizeQueue, incidentService,
                vectorService, mutationService, incidentAgent, tagService);
        when(agentProperties.isIncidentPipelineEnabled()).thenReturn(true);
    }

    private static Incident emerging() {
        Incident incident = new Incident();
        incident.setId(123L);
        incident.setUserId(1L);
        incident.setName("裁员风波");
        incident.setStatus(Incident.STATUS_EMERGING);
        incident.setSummary("bootstrap 摘要");
        return incident;
    }

    private void enqueueDue(String member) {
        when(finalizeQueue.due(anyInt())).thenReturn(Set.of(member));
        when(finalizeQueue.claim(123L)).thenReturn(true);
    }

    private static IncidentSummaryResult generated(String summary, String scene) {
        IncidentSummaryResult r = new IncidentSummaryResult();
        r.setSummary(summary);
        r.setScene(scene);
        return r;
    }

    @Test
    void settlePersistsSummaryAndSceneThenVectors() {
        enqueueDue("123");
        Incident emerging = emerging();
        when(incidentService.load(123L)).thenReturn(emerging);
        when(incidentService.collectFacts(123L)).thenReturn(List.of("公司裁员", "已投递简历"));
        when(incidentAgent.summarizeIncident(anyString(), anyString(), any(), any()))
                .thenReturn(AgentResult.ok(generated("现状摘要", SceneCategory.WORK_CAREER.label())));
        Incident settled = emerging();
        settled.setStatus(Incident.STATUS_SETTLED);
        settled.setSummary("现状摘要");
        settled.setSceneCategory(SceneCategory.WORK_CAREER.label());
        when(incidentService.finalizeIncident(123L, "现状摘要", "工作职业")).thenReturn(settled);

        finalizer.finalizeDueIncidents();

        verify(incidentService).finalizeIncident(123L, "现状摘要", "工作职业");
        ArgumentCaptor<Incident> vectorArg = ArgumentCaptor.forClass(Incident.class);
        verify(vectorService).upsertQuietly(vectorArg.capture());
        assertEquals("工作职业", vectorArg.getValue().getSceneCategory(),
                "向量写入实体必须携带新场景");
    }

    @Test
    void settleWithLlmDegradationPassesNullScene() {
        enqueueDue("123");
        when(incidentService.load(123L)).thenReturn(emerging());
        when(incidentService.collectFacts(123L)).thenReturn(List.of("事实"));
        when(incidentAgent.summarizeIncident(anyString(), anyString(), any(), any()))
                .thenReturn(AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "摘要生成降级"));
        when(incidentService.finalizeIncident(eq(123L), isNull(), isNull())).thenReturn(emerging());

        finalizer.finalizeDueIncidents();

        verify(incidentService).finalizeIncident(123L, null, null);
    }

    @Test
    void refreshSettledUpdatesSummaryAndScene() {
        enqueueDue("123");
        Incident settled = emerging();
        settled.setStatus(Incident.STATUS_SETTLED);
        when(incidentService.load(123L)).thenReturn(settled);
        when(incidentService.collectFacts(123L)).thenReturn(List.of("新事实：已入职"));
        when(incidentAgent.summarizeIncident(anyString(), anyString(), any(), any()))
                .thenReturn(AgentResult.ok(generated("新现状摘要", "工作职业")));

        finalizer.finalizeDueIncidents();

        verify(incidentService).refreshSummary(123L, "新现状摘要", "工作职业");
        verify(incidentService, never()).finalizeIncident(anyLong(), any(), any());
        verify(vectorService).upsertQuietly(settled);
    }

    @Test
    void refreshSkippedWhenGenerationFails() {
        enqueueDue("123");
        Incident settled = emerging();
        settled.setStatus(Incident.STATUS_SETTLED);
        when(incidentService.load(123L)).thenReturn(settled);
        when(incidentService.collectFacts(123L)).thenReturn(List.of("事实"));
        when(incidentAgent.summarizeIncident(anyString(), anyString(), any(), any()))
                .thenReturn(AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "降级"));

        finalizer.finalizeDueIncidents();

        verify(incidentService, never()).refreshSummary(anyLong(), anyString(), any());
        verify(vectorService, never()).upsertQuietly(any());
    }

    @Test
    void pipelineDisabledSkipsAll() {
        when(agentProperties.isIncidentPipelineEnabled()).thenReturn(false);
        finalizer.finalizeDueIncidents();
        verify(finalizeQueue, never()).due(anyInt());
    }
}
