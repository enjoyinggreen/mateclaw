package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.SessionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeCoordinator;
import vip.mate.agent.runtime.contract.AgentRuntimeProvider;
import vip.mate.agent.runtime.contract.RuntimeCapabilities;
import vip.mate.agent.runtime.contract.RuntimeProviderRegistry;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.agent.runtime.contract.RuntimeValidation;
import vip.mate.exception.MateClawException;

class CopilotAvailabilityIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void nativeAndDshRemainAvailableWhenCopilotIsAbsentDisabledOrUnavailable(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        AtomicInteger nativeStarts = new AtomicInteger();
        AtomicInteger dshStarts = new AtomicInteger();
        AgentRuntimeProvider nativeProvider = passiveProvider("native", nativeStarts);
        AgentRuntimeProvider dshProvider = passiveProvider("dsh", dshStarts);

        assertNativeAndDshWork(
                coordinator(List.of(nativeProvider, dshProvider)),
                workspace,
                nativeStarts,
                dshStarts);
        IllegalArgumentException absentError = assertThrows(
                IllegalArgumentException.class,
                () -> coordinator(List.of(nativeProvider, dshProvider)).start(
                        agent("copilot"),
                        "conversation",
                        "absent",
                        null,
                        workspace,
                        workspace,
                        turnContext(workspace, "conversation")));
        assertEquals("unknown runtime provider: copilot", absentError.getMessage());
        assertEquals(1, nativeStarts.get());
        assertEquals(1, dshStarts.get());

        for (CopilotRuntimeConfigService.Snapshot unavailable : List.of(
                disabled(tempDir),
                invalid(tempDir))) {
            AgentRuntimeProvider copilotProvider = copilotProvider(unavailable);
            AgentRuntimeCoordinator coordinator = coordinator(
                    List.of(nativeProvider, dshProvider, copilotProvider));
            assertNativeAndDshWork(coordinator, workspace, nativeStarts, dshStarts);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> coordinator.start(
                            agent("copilot"),
                            "conversation",
                            "unavailable",
                            null,
                            workspace,
                            workspace,
                            turnContext(workspace, "conversation")));
            assertEquals(
                    unavailable.enabled() ? "copilot.config_invalid" : "copilot.disabled",
                    error.getMessage().substring(0, error.getMessage().indexOf(':')));
        }

        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(configured(
                tempDir.resolve("state-failure"), "configured-model", "test-token"));
        AgentRuntimeProvider failedCopilot = new CopilotRuntimeService(
                config,
                new CopilotSdkClient(options -> {
                    throw new IllegalStateException("private provider failure");
                }),
                new CopilotEventMapper());
        MateClawException providerError = assertThrows(
                MateClawException.class,
                () -> coordinator(List.of(nativeProvider, dshProvider, failedCopilot)).start(
                        agent("copilot"),
                        "conversation",
                        "failed",
                        null,
                        workspace,
                        workspace,
                        turnContext(workspace, "conversation")));
        assertEquals("copilot.provider_unavailable", providerError.getMsgKey());
        assertNativeAndDshWork(
                coordinator(List.of(nativeProvider, dshProvider, failedCopilot)),
                workspace,
                nativeStarts,
                dshStarts);
    }

    @Test
    void enabledCopilotKeepsEachRunningTurnConfigurationAndExactConfiguredModel(@TempDir Path tempDir)
            throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        AtomicReference<CopilotRuntimeConfigService.Snapshot> current = new AtomicReference<>(
                configured(tempDir.resolve("state-one"), "first-model", "first-token"));
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenAnswer(ignored -> current.get());
        List<CopilotClientOptions> clientOptions = new ArrayList<>();
        List<SessionConfig> sessionConfigs = new ArrayList<>();
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            clientOptions.add(options);
            CopilotClient client = mock(CopilotClient.class);
            CopilotSession session = mock(CopilotSession.class);
            when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
            when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
                sessionConfigs.add(invocation.getArgument(0));
                return CompletableFuture.completedFuture(session);
            });
            when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
            return client;
        });
        AgentRuntimeProvider copilotProvider =
                new CopilotRuntimeService(config, sdkClient, new CopilotEventMapper());
        AgentRuntimeCoordinator coordinator =
                coordinator(List.of(passiveProvider("native", new AtomicInteger()),
                        passiveProvider("dsh", new AtomicInteger()), copilotProvider));

        AgentRuntimeConnection first = coordinator.start(
                agent("copilot"),
                "conversation-one",
                "execution-one",
                null,
                workspace,
                workspace,
                turnContext(workspace, "conversation-one"));
        current.set(configured(tempDir.resolve("state-two"), "second-model", "second-token"));
        AgentRuntimeConnection second = coordinator.start(
                agent("copilot"),
                "conversation-two",
                "execution-two",
                null,
                workspace,
                workspace,
                turnContext(workspace, "conversation-two"));

        assertEquals(List.of("first-model", "second-model"),
                sessionConfigs.stream().map(SessionConfig::getModel).toList());
        assertEquals(List.of("first-token", "second-token"),
                clientOptions.stream().map(CopilotClientOptions::getGitHubToken).toList());
        first.close();
        second.close();
    }

    private static void assertNativeAndDshWork(
            AgentRuntimeCoordinator coordinator,
            Path workspace,
            AtomicInteger nativeStarts,
            AtomicInteger dshStarts) {
        int nativeStartsBefore = nativeStarts.get();
        int dshStartsBefore = dshStarts.get();
        coordinator.start(agent("native"), "conversation", "native", null,
                workspace, workspace);
        coordinator.start(agent("dsh"), "conversation", "dsh", null,
                workspace, workspace);
        assertEquals(nativeStartsBefore + 1, nativeStarts.get());
        assertEquals(dshStartsBefore + 1, dshStarts.get());
    }

    private static AgentRuntimeProvider passiveProvider(String type, AtomicInteger starts) {
        return new AgentRuntimeProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public RuntimeValidation validate(RuntimeSession session) {
                return RuntimeValidation.success();
            }

            @Override
            public RuntimeCapabilities capabilities() {
                return new RuntimeCapabilities(true, false, false, false);
            }

            @Override
            public AgentRuntimeConnection start(RuntimeSession session) {
                starts.incrementAndGet();
                return mock(AgentRuntimeConnection.class);
            }
        };
    }

    private static AgentRuntimeProvider copilotProvider(
            CopilotRuntimeConfigService.Snapshot configuration) {
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(configuration);
        return new CopilotRuntimeService(config, new CopilotSdkClient(options -> {
            throw new AssertionError("invalid or disabled Copilot must not start the SDK");
        }), new CopilotEventMapper());
    }

    private static AgentRuntimeCoordinator coordinator(List<AgentRuntimeProvider> providers) {
        return new AgentRuntimeCoordinator(new RuntimeProviderRegistry(providers), JSON);
    }

    private static AgentEntity agent(String runtimeType) {
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setWorkspaceId(9L);
        agent.setRuntimeType(runtimeType);
        agent.setRuntimeConfig("{}");
        return agent;
    }

    private static RuntimeTurnContext turnContext(Path workspace, String conversationId) {
        ChatOrigin origin = ChatOrigin.web(
                        conversationId, "account", 9L, workspace.toString(), null, 73L)
                .withAgent(7L);
        return new RuntimeTurnContext(origin, workspace, "Employee instructions");
    }

    private static CopilotRuntimeConfigService.Snapshot configured(
            Path stateRoot, String model, String token) {
        return new CopilotRuntimeConfigService.Snapshot(
                true,
                "/opt/copilot/bin/copilot",
                model,
                token,
                stateRoot.toString(),
                30_000,
                5_000,
                null);
    }

    private static CopilotRuntimeConfigService.Snapshot disabled(Path tempDir) {
        return new CopilotRuntimeConfigService.Snapshot(
                false, "", "", null, tempDir.toString(), 30_000, 5_000, null);
    }

    private static CopilotRuntimeConfigService.Snapshot invalid(Path tempDir) {
        return new CopilotRuntimeConfigService.Snapshot(
                true,
                "",
                "",
                null,
                tempDir.toString(),
                30_000,
                5_000,
                "copilot.cli_path is required");
    }
}
