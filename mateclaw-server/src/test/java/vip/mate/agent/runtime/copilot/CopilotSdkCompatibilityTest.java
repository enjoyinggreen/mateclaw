package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class CopilotSdkCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sdkCustomToolPreservesNestedSpringSchema() throws Exception {
        String schema =
                """
                {"type":"object","properties":{"document":{"type":"object","properties":{"limit":{"type":"integer","minimum":2},"tags":{"type":"array","items":{"type":"string"}}},"required":["limit"]}},"required":["document"],"additionalProperties":false}
                """;
        ToolDefinition springDefinition =
                ToolDefinition.builder()
                        .name("workspace_search")
                        .description("Search scoped workspace documents")
                        .inputSchema(schema)
                        .build();
        Map<String, Object> parameters =
                JSON.readValue(
                        springDefinition.inputSchema(), new TypeReference<Map<String, Object>>() {});

        com.github.copilot.rpc.ToolDefinition sdkDefinition =
                com.github.copilot.rpc.ToolDefinition.create(
                        springDefinition.name(),
                        springDefinition.description(),
                        parameters,
                        ignored -> CompletableFuture.completedFuture("unused"));
        JsonNode encoded = JSON.valueToTree(sdkDefinition);

        assertEquals(JSON.readTree(schema), encoded.path("parameters"));
        assertNotEquals(Boolean.TRUE, sdkDefinition.skipPermission());
    }

    @Test
    void sdkAssistantDeltaCanBeDeserializedAndCapturedAsItsTypedEvent() throws Exception {
        AssistantMessageDeltaEvent event =
                JSON.readValue(
                        """
                        {"type":"assistant.message_delta","data":{"messageId":"message-1","deltaContent":"hello"}}
                        """,
                        AssistantMessageDeltaEvent.class);
        AtomicReference<AssistantMessageDeltaEvent> captured = new AtomicReference<>();
        Consumer<AssistantMessageDeltaEvent> handler = captured::set;

        handler.accept(event);

        assertEquals("assistant.message_delta", event.getType());
        assertEquals("message-1", captured.get().getData().messageId());
        assertSame(event, captured.get());
    }

    @Test
    void emptyModeSupportsEmptyAndCustomOnlyToolFilters(@TempDir Path copilotHome) {
        CopilotClientOptions options =
                new CopilotClientOptions()
                        .setMode(CopilotClientMode.EMPTY)
                        .setCopilotHome(copilotHome.toString())
                        .setAutoRestart(false);
        SessionConfig noTools = new SessionConfig().setAvailableTools(new ToolSet());
        ToolSet hostTools = new ToolSet().addCustom("workspace_search");
        SessionConfig customOnly = new SessionConfig().setAvailableTools(hostTools);

        assertEquals(CopilotClientMode.EMPTY, options.getMode());
        assertEquals(copilotHome.toString(), options.getCopilotHome());
        assertFalse(options.isAutoRestart());
        assertEquals(List.of(), noTools.getAvailableTools());
        assertEquals(List.of("custom:workspace_search"), customOnly.getAvailableTools());
    }
}
