package vip.mate.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeToolSetResolverTest {

    static class PolicyTools {
        @Tool(description = "Allowed tool")
        public String allowed_tool() {
            return "allowed";
        }

        @Tool(description = "Globally denied tool")
        public String global_denied_tool() {
            return "denied";
        }

        @Tool(description = "Skill discovery tool")
        public String skill_discovery_tool() {
            return "discovery";
        }

        @Tool(description = "Skill loading tool")
        public String load_skill() {
            return "loaded";
        }
    }

    static class AliasedTools {
        @Tool(description = "First aliased tool")
        public String first_tool() {
            return "first";
        }

        @Tool(description = "Second aliased tool")
        public String second_tool() {
            return "second";
        }
    }

    @Test
    void resolve_keepsGlobalAndSkillDiscoveryDenies() {
        AgentEntity employee = employee(7L);
        RuntimeToolSetResolver resolver = resolver(
                toolSet(new PolicyTools()),
                Set.of("global_denied_tool"),
                Set.of("skill_discovery_tool"),
                null,
                true);

        assertEquals(Set.of("allowed_tool", "load_skill"), names(resolver.resolve(employee)));
    }

    @Test
    void resolve_deniesLoadSkillWhenFeatureIsDisabled() {
        AgentEntity employee = employee(7L);
        RuntimeToolSetResolver resolver = resolver(
                toolSet(new PolicyTools()),
                Set.of(),
                Set.of(),
                null,
                false);

        assertEquals(Set.of("allowed_tool", "global_denied_tool", "skill_discovery_tool"),
                names(resolver.resolve(employee)));
    }

    @Test
    void resolve_acceptsClassAliasesForBoundTools() {
        AgentEntity employee = employee(7L);
        RuntimeToolSetResolver resolver = resolver(
                toolSet(new AliasedTools()),
                Set.of(),
                Set.of(),
                Set.of("AliasedTools"),
                true);

        assertEquals(Set.of("first_tool", "second_tool"), names(resolver.resolve(employee)));
    }

    @Test
    void resolve_distinguishesNoRestrictionFromAnEmptyAllowlist() {
        AgentToolSet tools = toolSet(new PolicyTools());
        AgentEntity employee = employee(7L);

        RuntimeToolSetResolver unrestricted = resolver(tools, Set.of(), Set.of(), null, true);
        RuntimeToolSetResolver explicitlyEmpty = resolver(tools, Set.of(), Set.of(), Set.of(), true);

        assertEquals(Set.of("allowed_tool", "global_denied_tool", "skill_discovery_tool", "load_skill"),
                names(unrestricted.resolve(employee)));
        assertEquals(Set.of(), names(explicitlyEmpty.resolve(employee)));
    }

    private static AgentEntity employee(Long id) {
        AgentEntity employee = new AgentEntity();
        employee.setId(id);
        return employee;
    }

    private static AgentToolSet toolSet(Object... beans) {
        List<Object> toolBeans = Arrays.asList(beans);
        return AgentToolSet.from(toolBeans, List.of());
    }

    private static RuntimeToolSetResolver resolver(
            AgentToolSet toolSet,
            Set<String> globalDenied,
            Set<String> skillDiscoveryDenied,
            Set<String> allowed,
            boolean loadSkillToolEnabled) {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AgentBindingService bindingService = mock(AgentBindingService.class);
        ToolGuardConfigService guardConfigService = mock(ToolGuardConfigService.class);
        when(toolRegistry.getEnabledToolSet()).thenReturn(toolSet);
        when(guardConfigService.getDeniedTools()).thenReturn(globalDenied);
        when(bindingService.getSkillDiscoveryDeniedTools(7L)).thenReturn(skillDiscoveryDenied);
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(allowed);
        return new RuntimeToolSetResolver(
                toolRegistry, bindingService, guardConfigService, loadSkillToolEnabled);
    }

    private static Set<String> names(AgentToolSet toolSet) {
        return toolSet.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
