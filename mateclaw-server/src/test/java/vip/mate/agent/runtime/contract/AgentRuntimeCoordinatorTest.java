package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;

class AgentRuntimeCoordinatorTest {
    @Test
    void startsOnlyAfterSessionValidation() {
        AgentRuntimeConnection connection = mock(AgentRuntimeConnection.class);
        AgentRuntimeProvider provider = mock(AgentRuntimeProvider.class);
        when(provider.type()).thenReturn("dsh");
        when(provider.validate(org.mockito.ArgumentMatchers.any())).thenReturn(RuntimeValidation.success());
        when(provider.start(org.mockito.ArgumentMatchers.any())).thenReturn(connection);

        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                new RuntimeProviderRegistry(List.of(provider)), new ObjectMapper());
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setWorkspaceId(2L);
        agent.setRuntimeType("dsh");
        agent.setRuntimeConfig("{}");

        assertSame(connection, coordinator.start(agent, "conversation", "session", "model",
                Path.of("/workspace"), Path.of("/workspace/agent")));
    }

    @Test
    void rejectsContextlessCopilotBeforeProviderValidationOrStartup() {
        AgentRuntimeProvider provider = mock(AgentRuntimeProvider.class);
        when(provider.type()).thenReturn("copilot");
        when(provider.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeValidation.success());

        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                new RuntimeProviderRegistry(List.of(provider)), new ObjectMapper());
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setWorkspaceId(2L);
        agent.setRuntimeType("copilot");
        agent.setRuntimeConfig("{}");

        assertThrows(IllegalArgumentException.class, () -> coordinator.start(
                agent, "conversation", "session", "model",
                Path.of("/workspace"), Path.of("/workspace/agent")));

        verify(provider, never()).validate(org.mockito.ArgumentMatchers.any());
        verify(provider, never()).start(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void forwardsTrustedTurnContextToCopilotProvider(@TempDir Path tempDir) throws IOException {
        AgentRuntimeConnection connection = mock(AgentRuntimeConnection.class);
        AgentRuntimeProvider provider = mock(AgentRuntimeProvider.class);
        when(provider.type()).thenReturn("copilot");
        when(provider.validate(any())).thenReturn(RuntimeValidation.success());
        when(provider.start(any())).thenReturn(connection);

        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                new RuntimeProviderRegistry(List.of(provider)), new ObjectMapper());
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setWorkspaceId(2L);
        agent.setRuntimeType("copilot");
        agent.setRuntimeConfig("{}");
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path workingDirectory = Files.createDirectories(root.resolve("project"));
        RuntimeTurnContext context =
                new RuntimeTurnContext(
                        ChatOrigin.web("conversation", "alice", 2L, root.toString(), null, 42L)
                                .withAgent(1L),
                        root,
                        "trusted system prompt");

        assertSame(
                connection,
                coordinator.start(
                        agent,
                        "conversation",
                        "session",
                        "model",
                        root,
                        workingDirectory,
                        context));

        ArgumentCaptor<RuntimeSession> session = ArgumentCaptor.forClass(RuntimeSession.class);
        verify(provider).start(session.capture());
        verify(provider).validate(any());
        assertEquals(context.origin(), session.getValue().turnContext().origin());
        assertEquals(root.toRealPath(), session.getValue().turnContext().workspaceRoot());
        assertEquals(context.systemPrompt(), session.getValue().turnContext().systemPrompt());
    }
}
