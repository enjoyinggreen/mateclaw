package vip.mate.agent.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class RuntimeToolSetResolver {

    private final ToolRegistry toolRegistry;
    private final AgentBindingService agentBindingService;
    private final ToolGuardConfigService toolGuardConfigService;
    private final boolean loadSkillToolEnabled;

    public RuntimeToolSetResolver(
            ToolRegistry toolRegistry,
            AgentBindingService agentBindingService,
            ToolGuardConfigService toolGuardConfigService,
            @Value("${mateclaw.skill.disclosure.load-skill-tool.enabled:true}")
            boolean loadSkillToolEnabled) {
        this.toolRegistry = toolRegistry;
        this.agentBindingService = agentBindingService;
        this.toolGuardConfigService = toolGuardConfigService;
        this.loadSkillToolEnabled = loadSkillToolEnabled;
    }

    public AgentToolSet resolve(AgentEntity employee) {
        AgentToolSet toolSet = toolRegistry.getEnabledToolSet();

        Set<String> deniedTools = new LinkedHashSet<>(toolGuardConfigService.getDeniedTools());
        deniedTools.addAll(agentBindingService.getSkillDiscoveryDeniedTools(employee.getId()));
        if (!loadSkillToolEnabled) {
            deniedTools.add("load_skill");
        }
        toolSet = toolSet.withDeniedToolsFiltered(deniedTools);

        Set<String> boundTools = agentBindingService.getEffectiveToolNames(employee.getId());
        return toolSet.withAllowedToolsOnly(boundTools);
    }
}
