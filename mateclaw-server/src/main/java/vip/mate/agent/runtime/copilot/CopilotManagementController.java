package vip.mate.agent.runtime.copilot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

import java.util.Map;

@Tag(name = "Copilot Runtime Management")
@RestController
@RequestMapping("/api/v1/admin/copilot")
public class CopilotManagementController {
    private final CopilotReadinessService readinessService;

    public CopilotManagementController(CopilotReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @Operation(summary = "Get Copilot runtime configuration and readiness")
    @GetMapping("/status")
    @RequireGlobalAdmin
    public R<CopilotReadinessService.Status> status() {
        return R.ok(readinessService.status());
    }

    @Operation(summary = "Save Copilot runtime configuration")
    @PutMapping("/config")
    @RequireGlobalAdmin
    public R<CopilotReadinessService.Status> saveConfig(@RequestBody Map<String, ?> updates) {
        return R.ok(readinessService.save(updates));
    }

    @Operation(summary = "Verify Copilot runtime readiness without generating a response")
    @PostMapping("/verify")
    @RequireGlobalAdmin
    public R<CopilotReadinessService.Status> verify() {
        return R.ok(readinessService.verify());
    }
}
