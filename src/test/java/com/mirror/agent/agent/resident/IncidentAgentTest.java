package com.mirror.agent.agent.resident;

import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.IncidentSummaryResult;
import com.mirror.agent.util.PromptUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IncidentAgent 摘要+场景归一边界测试：prompt 变量注入（白名单单一来源=枚举）、
 * LLM 越界输出归一「其他」、缺失保留 null、空摘要降级。
 */
class IncidentAgentTest {

    private LlmGateway llmGateway;
    private PromptUtil promptUtil;
    private IncidentAgent agent;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        promptUtil = mock(PromptUtil.class);
        when(promptUtil.render(anyString(), any(Map.class))).thenReturn("rendered");
        agent = new IncidentAgent(llmGateway, promptUtil);
    }

    private static IncidentSummaryResult result(String summary, String scene) {
        IncidentSummaryResult r = new IncidentSummaryResult();
        r.setSummary(summary);
        r.setScene(scene);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptReceivesWhitelistFromEnum() {
        when(llmGateway.callStructured(any(LlmRequest.class), eq(IncidentSummaryResult.class)))
                .thenReturn(result("摘要", "工作职业"));

        agent.summarizeIncident("裁员风波", "1. 事实", "", "");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptUtil).render(eq("incident-summary.txt"), captor.capture());
        Map<String, String> vars = captor.getValue();
        assertTrue(vars.get("scenes").contains("生存安全"), "prompt 应注入 19 类清单");
        assertTrue(vars.get("scenes").endsWith("其他"));
        assertEquals("19", vars.get("sceneCount"));
    }

    @Test
    void validScenePassesThroughTrimmed() {
        when(llmGateway.callStructured(any(LlmRequest.class), eq(IncidentSummaryResult.class)))
                .thenReturn(result("摘要", " 工作职业 "));

        AgentResult<IncidentSummaryResult> r = agent.summarizeIncident("n", "f", "", "");
        assertEquals("工作职业", r.getData().getScene());
    }

    @Test
    void outOfWhitelistSceneFallsBackToOther() {
        when(llmGateway.callStructured(any(LlmRequest.class), eq(IncidentSummaryResult.class)))
                .thenReturn(result("摘要", "太空探索"));

        AgentResult<IncidentSummaryResult> r = agent.summarizeIncident("n", "f", "", "");
        assertEquals("其他", r.getData().getScene());
    }

    @Test
    void missingSceneStaysNullForCallerToKeepPrevious() {
        when(llmGateway.callStructured(any(LlmRequest.class), eq(IncidentSummaryResult.class)))
                .thenReturn(result("摘要", null));

        AgentResult<IncidentSummaryResult> r = agent.summarizeIncident("n", "f", "", "");
        assertNull(r.getData().getScene(), "缺失不兜底成其他，由调用方保留原分类");
    }

    @Test
    void blankSummaryDegradesWithoutData() {
        when(llmGateway.callStructured(any(LlmRequest.class), eq(IncidentSummaryResult.class)))
                .thenReturn(result(" ", "工作职业"));

        AgentResult<IncidentSummaryResult> r = agent.summarizeIncident("n", "f", "", "");
        assertNull(r.getData(), "摘要失败整次降级（保留 bootstrap），不单独保留 scene");
    }
}
