# Copilot Runtime Provider Contract

This is an implementation contract, not a new public chat protocol. The existing
`AgentRuntimeProvider` and application stream vocabulary remain authoritative.

## Provider boundary

`CopilotRuntimeService` implements:

| Operation | Contract |
|-----------|----------|
| `type()` | Exactly `copilot` |
| `validate(RuntimeSession)` | No task content or model call; fail closed on disabled config, absent trusted context, scope, invalid override, unsupported isolation or model |
| `capabilities()` | Cancellation enabled; approvals and subagents disabled; context usage projection enabled with per-field availability |
| `start(RuntimeSession)` | Create an owned connection; no shared mutable user session or ambient authentication |

The registry must reject unknown providers and preserve native as the default.
Server startup never starts Copilot or depends on its availability.

Add source-compatible session/factory/coordinator overloads with
`RuntimeTurnContext(ChatOrigin origin, Path workspaceRoot, String systemPrompt)`.
The original constructor remains available to native/DSH and existing tests.
Copilot rejects the legacy context-less session. The context is assembled on the
server after access checks and cannot be deserialized from employee overrides.

## Connection ownership and terminal rules

Each connection accepts one prompt subscription, owns a fresh provider session,
and never resumes a previous provider session. Duplicate/concurrent subscriptions
fail rather than send the same request twice. Creation, event callbacks, and cleanup
run off servlet/Reactor event-loop threads with explicit bounded deadlines.

Register event handlers before sending. `prompt` yields ordered `RuntimeEvent`
objects with this execution's session ID and increasing sequence number.
`cancel` is idempotent and asynchronous; `close` is idempotent and bounded.
Normal successful resource disposal does not cancel the completed turn.

Cancellation ordering:

1. Atomically claim cancellation if no final outcome exists and close the host-tool
   admission gate.
2. Cancel queued/current host-tool work and request provider abort.
3. Dispose event subscriptions, close provider session, and terminate only owned
   runtime resources within the configured five-second acceptance stop budget.
4. Finalize as cancelled once; discard late deltas/results. Record uncertain
   externally visible effects without claiming rollback.

Use SDK-owned local spawn and `CopilotClient.forceStop()` on a dedicated cleanup
worker after bounded `CopilotSession.abort()`, not graceful `stop()`. The exact
SDK pin does not expose a process handle; its force-kill reap wait is up to ten
seconds. Bound the caller's wait to the configured stop budget without abandoning
the cleanup worker. A five-second overrun is reported as cleanup failure and
fails acceptance; never assert resources exited merely because waiting timed out.
Do not enable deployments that fail the controlled stop target. This measured
target is not a hard real-time OS/SDK guarantee. No process-name killing, reflective
access to SDK internals, or unplanned remote supervisor is permitted.
Remove tracker hooks and timers on every terminal and startup-failure path.

Provider idle is completion only for the active prompt, after successful answer
or valid tool-only completion, with no error/stop/pending tool work. Provider EOF,
missing terminal evidence, startup errors, and deadline expiration produce failure.
An explicit empty provider completion may be recorded only if its completion
semantics are verified; transport end alone is never sufficient.

## Event mapping

| Provider evidence | Normalized event | Existing stream projection |
|-------------------|------------------|----------------------------|
| Successful session setup | `RUNTIME_READY` | `phase=runtime_ready` |
| Assistant text delta | `ASSISTANT_DELTA` | Content delta |
| Available reasoning delta | `THINKING_DELTA` | Thinking delta |
| Governed host call starts | `TOOL_STARTED` | `tool_call_started` |
| Host call succeeds/fails/is blocked | `TOOL_FINISHED` | `tool_call_completed` with explicit status |
| Measured counters/context information | `CONTEXT_USAGE` | `_usage_final` plus additive availability |
| Verified prompt idle/completion | `COMPLETED` | `done` |
| Provider or application failure | `FAILED` | `error` |
| User/admin stop | `CANCELLED` | `cancelled` |

Full final assistant messages reconcile against already emitted deltas per message
ID. They cannot duplicate text. Unknown optional event types may be ignored;
unknown permission/action requests are denied, not ignored as success.
Delegated execution is disabled, so no synthetic subagent events are generated.

Common data includes `runtimeProvider="copilot"` and actual model identity.
Tool events contain nonblank `callId`, `toolName`, and an explicit
`status` (`succeeded`, `failed`, `denied`, `approval_required`, `cancelled`,
`uncertain`). Preserve the projector's aliases `toolCallId` and `name`.
Approval-required is a blocked tool outcome, not a live `pendingId` prompt.

Usage data must match existing accumulator fields:

```json
{
  "runtimeProviderId": "copilot",
  "runtimeModelName": "actual-copilot-model",
  "promptTokens": 125,
  "completionTokens": 30,
  "contextTokens": 500,
  "contextWindow": 128000,
  "usageAvailability": {
    "promptTokens": true,
    "completionTokens": true,
    "contextTokens": true,
    "contextWindow": true
  }
}
```

Only supplied measurements appear as numeric counters. For absent fields,
availability is false and no inferred zero is emitted. A context-only report does
not imply known input/output usage. The accumulator, message metadata, completion
payload, history display, and totals preserve that distinction. Existing legacy
numeric fields and constructors keep their historical behavior.

## Host tool bridge

Derive schemas only from `RuntimeToolSetResolver.resolve(employee)`, preserving
global denies, skill-discovery policy, disabled `load_skill`, and bound-tool
three-state semantics (no restriction vs empty allowlist). Unknown names never
fall back to SDK-native tools.

Bridge requirements:

- Explicit allowlist of host-governed tools; default SDK tools, native MCP servers,
  automatic memory, cross-session search, filesystem-discovered agents/skills,
  and provider-native delegated execution disabled.
- At the pinned SDK, use `CopilotClientMode.EMPTY`, an explicit unique
  `copilotHome`, session `configDirectory`, and `ToolSet.addCustom(name)` for each
  advertised host tool. Pass an explicit empty `ToolSet` for text-only execution.
  Custom tools are filtered too; unprefixed tool names are not the chosen contract.
  Do not enable `BuiltInTools.ISOLATED`: it includes `task` and other capabilities
  that exceed the initial no-delegation policy.
- Disable memory and session store explicitly, set custom agents local-only with
  no custom agents, and do not register provider-native MCP servers. Use EMPTY-mode
  ambient-discovery restrictions plus isolated config, not an empty MCP map alone.
- Pre-tool hook denies unregistered tools before execution. Permission handler
  denies every ungoverned request; no approve-all or permission-skip setting.
- Use `PreToolUseHookOutput.deny(reason)` for unknown tools and
  `PermissionRequestResult.reject(reason)` for ungoverned permission requests.
  If a registered host tool needs a provider permission decision, approve only
  that exact registered invocation after validating its identity, never a generic
  file/shell/network permission. Its real effect still passes host Tool Guard.
- Fresh isolated runtime home/configuration and session state per execution; an
  empty map/list alone is not proof that ambient configuration was disabled.
- Schema/argument validation; per-execution unique call-ID cache prevents
  duplicate execution. Serialize host calls and fence them against finalization.
- Dispatch through `ToolExecutionExecutor.execute` in normal guarded mode, with
  captured `ChatOrigin`, existing result budget/timeouts, and a cancellation
  checkpoint. Do not use direct callbacks or `executePreApproved`.
- Initial approval continuation is unsupported. An explicit approval-disabled
  invocation mode returns a block before creating pending approvals; default
  native executor behavior remains unchanged. This must not remove ordinary guard
  evaluation or grant-based policy.
- Restore origin/tool/delegation context on the callback thread and clear it in
  `finally`; never infer requester from SDK input.
- Oversized results follow existing result policy. `returnDirect` output is
  rendered through the application path, not leaked back into provider context.
- Do not automatically retry failed/uncertain calls. Any later retry re-evaluates
  current permissions and external-effect safety.

If installed CLI behavior cannot uphold these restrictions, readiness fails and
no task content is sent. A source-level setting is not sufficient release evidence;
test with adversarial ambient configuration and real tool attempts.

Supply employee instructions using `SystemMessageMode.APPEND`, preserving provider
guardrails; do not use `REPLACE`. Disable SDK auto-restart to avoid hidden prompt
replay. Set process cwd and session working directory explicitly; provide an
allowlisted child environment rather than inheriting server/desktop credentials.
Compile against exact SDK types to adapt Spring's arbitrary JSON schemas; do not
replace real schemas with inferred parameter lists. API compilation and the
no-tools/custom-tools fixtures are prerequisites for runtime integration.

## Persistence and supported entry points

Use the existing chat lifecycle with the Copilot execution marker defined in
[data-model.md](../data-model.md). Streaming and synchronous chat use the same
provider, history selection, and outcome semantics. Synchronous failure is not
returned as successful partial text.

Reconnection attaches to the existing application stream using SSE event IDs; it
does not create another provider turn. Persisted completed history remains visible
after restart or changing the employee to another runtime.

Copilot is not a native graph implementation. Calls requiring native-only
Plan-and-Execute, durable Goal segments, Team Run orchestration, or approval replay
must explicitly reject unsupported execution rather than instantiate a native
agent behind a Copilot selection. Existing native/DSH behavior is unchanged.
