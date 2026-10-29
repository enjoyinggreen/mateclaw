# Data Model: Copilot Runtime

**Status**: Proposed design. Existing database structures are reused; this file
does not introduce a migration or assert that implementation exists.

## 1. Employee runtime selection

Use the existing `AgentEntity` / `mate_agent` fields:

| Field | Rule |
|-------|------|
| `id`, `workspaceId` | Existing server-authorized employee and workspace identity |
| `runtimeType` | `native`, `dsh`, or new `copilot`; blank retains existing native default |
| `runtimeConfig` | Optional object serialized as existing JSON string; Copilot allows only `model` |
| `systemPrompt` | Application-owned employee identity/instructions, supplied separately from history |
| `workspaceBasePath` | Resolved against authorized workspace scope; not an authority override |

For Copilot, `runtimeConfig` absent, blank, or `{}` means no employee-specific
override. `model`, when present, must be a nonblank string. Unknown keys, credentials,
paths, commands, environment, tool policy, and provider endpoints are rejected.
Create and update normalize runtime names consistently; partial updates validate
the merged effective selection, not an incomplete request. Existing native/DSH
configuration rules are unchanged.

Model precedence is employee `runtimeConfig.model`, then employee `modelName`,
then administrator `copilot.model_name`. Each nonblank value is interpreted as a
Copilot model identifier, not silently mapped through native provider failover.
An explicit conversation pin is accepted only when its provider is `copilot`,
takes precedence over the employee, and passes Copilot model validation. Other
pins produce an explicit unsupported-model error, not native execution.

## 2. Deployment runtime configuration

Existing system setting rows contain allowlisted string values:

| Setting | Meaning / default |
|---------|-------------------|
| `copilot.enabled` | `false` by default |
| `copilot.cli_path` | Administrator-supplied executable; required when enabled |
| `copilot.model_name` | Explicit default Copilot model; required when enabled |
| `copilot.github_token` | Access token; registered for encryption at rest; never returned |

Deployment properties under `mateclaw.agent.runtime.copilot` provide defaults for
the corresponding settings and `state-root`, `initialize-timeout-ms` (30,000), and
`stop-timeout-ms` (5,000). Managed settings, when present, override deployment
defaults; a disabled managed value overrides an enabled property. The effective
token comes from the managed secret or explicit deployment secret, never ambient
GitHub/CLI login. The existing SSE idle policy supplies the turn idle deadline;
existing `ToolTimeoutProperties` govern host actions.

Paths are global-admin-only configuration, never employee overrides. Do not accept
arbitrary command strings or shell expansion. Resolve and verify an executable
file. No remote server, in-process native runtime, BYOK, or installer is exposed
in the initial configuration.

Resolved configuration is an immutable snapshot for one execution. Updating or
disabling it affects new turns; running turns retain their snapshot and can still
be stopped. Invalid stored settings remain repairable and never prevent server
startup. Blank/masked token updates mean keep, and `clearToken=true` explicitly
clears the stored token; a clear with a new token is invalid.

## 3. Trusted runtime session

Add a typed server-only `RuntimeTurnContext`:

| Field | Source / validation |
|-------|---------------------|
| `origin: ChatOrigin` | Captured verified origin; includes employee, conversation, workspace, requester and execution attribution |
| `workspaceRoot: Path` | Server-resolved authorized root; real-path containment required |
| `systemPrompt: String` | Employee instructions; cannot be replaced by runtimeConfig |

Extend `RuntimeSession` with this context while retaining its current constructor
for source compatibility. Add a coordinator/session-factory overload carrying it.
Native and DSH existing call sites continue to use their existing semantics;
Copilot requires complete trusted context. Recheck that origin employee,
conversation and workspace match the session and selected employee before sending
content or exposing tools.

The existing session fields remain:
`sessionId`, `conversationId`, `agentId`, `workspaceId`, `modelName`,
`workingDirectory`, and `configuration`. `sessionId` for Copilot is a fresh opaque
execution identifier, not the conversation ID or a filesystem path. The provider
session gets a distinct opaque identifier; its state is never resumed.

Strict member file isolation rejects Copilot startup initially. Working directory
and root must exist and satisfy canonical containment; symlinks are resolved before
validation. Tool paths remain checked by the established application guard.

## 4. Runtime execution

An in-memory Copilot execution owns:

- Fresh execution ID; parent conversation/employee/workspace IDs; configuration
  snapshot; trusted context; selected model and runtime type.
- SDK client/session, event subscriptions, dedicated executor, registered tracker
  cancellation hook, current host-tool future, and owned provider process resources.
- Strictly increasing normalized event sequence and an atomic terminal decision.
- Deadline timers, latest measured usage snapshot (optional), and the persisted
  assistant marker ID.

State transitions:

```text
VALIDATING -> STARTING -> RUNNING -> COMPLETED
     |           |          |
     +-----------+----------+----> FAILED
                 |          |
                 +----------+----> CANCELLING -> CANCELLED
```

Finalization is compare-and-set: completion, error, and cancellation races have
one winner. Unknown provider exit is failure; a provider idle notification is
success only after an active send and no earlier error, pending host action, or
stop request. Repeated cancel/close is harmless. Resource closure after completion
must not emit cancellation or change its final outcome. Late events and callbacks
are discarded using execution identity, never just conversation identity.

Stopping blocks new actions before signaling provider abort. The cleanup worker
owns SDK hard-stop until it exits; exceeding the five-second acceptance wait is
a reported cleanup failure, not proof of process termination. See the lifecycle
limitation in [plan.md](plan.md). Existing external
effects may be irreversible; interrupted or timed-out tool outcomes are marked
uncertain and are not automatically retried.

## 5. Persisted conversation and interruption marker

Use `mate_message` with existing fields, not a new execution table:

| Field | During Copilot execution |
|-------|--------------------------|
| `role` | `assistant` |
| `status` | `generating`, then `completed`, `failed`, or existing `stopped` for normalized cancellation |
| `runtimeProvider` | `copilot` |
| `runtimeModel` | Actual selected model |
| `content`, `contentParts` | Partial/final answer through existing accumulator policy |
| `metadata.runtimeExecutionId` | Fresh ID used for conditional finalization |
| `metadata.runtimeOutcome` | `completed`, `failed`, or `cancelled` when terminal |
| `metadata.runtimeErrorCode` | Sanitized stable failure/interruption code, when relevant |
| `metadata.usageAvailability` | Per-field availability, independent of legacy numeric defaults |

Create the marker after authorization and before provider work begins, including
direct and channel chat entry points; finalize that same row instead of appending
a duplicate assistant. Associate the marker ID with the accumulator/run context.
Persist partial output using existing save/checkpoint points. A write must match
both marker and execution ID and cannot replace a terminal outcome.

At startup, reconcile only orphaned `generating` messages owned by `copilot` to
`failed` with `copilot.interrupted`, preserving any saved partial content. Keep the
existing stream-status normalization, but do not infer success from `idle`.
Do not alter completed rows or replay prior tools. A later turn receives only
history permitted by the existing completed-message policy.

## 6. Governed action

Actions are turn-scoped records/events, not a new durable replay queue:

| Field | Rule |
|-------|------|
| `callId` | Nonblank unique provider call ID within execution; duplicates never re-execute |
| `toolName` | Exact application-scoped exposed name |
| `arguments` | Validated JSON against the advertised schema |
| `origin` | Trusted captured turn identity |
| `decision` | Allowed, denied, approval-required-blocked |
| `outcome` | Succeeded, failed, blocked, cancelled, or uncertain |
| `result` | Existing result-budget/redaction rules apply |

The bridge serializes host calls for one turn, while different turns remain
independent. Only callbacks in the resolved employee tool set can execute.
Execution always uses normal Tool Guard, not approval replay. Initially,
`supportsApprovals=false` and `supportsSubagents=false`; a required approval is
reported as blocked with `copilot.approval_unsupported`, without a pending approval
ID that could trigger native replay. Provider-native delegated agents are disabled.

## 7. Usage and capabilities

Retain source compatibility for `RuntimeContextUsage`'s existing three numeric
arguments, adding explicit known-field flags or an equivalent typed snapshot.
Legacy constructors mean measured legacy values. Copilot returns no snapshot
until real counters arrive; missing individual counters remain unavailable.

The normalized usage event uses existing accumulator keys:
`promptTokens`, `completionTokens`, `runtimeModelName`, `runtimeProviderId`,
plus additive `contextTokens`, `contextWindow`, and `usageAvailability`.
Never invent prompt/output counters from current context length.

Existing integer columns can retain legacy defaults; metadata availability is
authoritative for Copilot display, reload, and aggregate reporting. UI and totals
must not count unavailable values as measured zero. Capability reporting
distinguishes SDK support from enabled application support: optional SDK features
can be deliberately unavailable under this runtime's governance policy.
