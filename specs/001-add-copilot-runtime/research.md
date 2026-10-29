# Research: Copilot Runtime

**Date**: 2026-10-26
**Scope**: Design research only; no runtime, dependency, or live-provider validation
has been performed.

## Repository findings

### R1 - Preserve the existing runtime boundary, complete its Copilot call sites

**Decision**: Implement `AgentRuntimeProvider` with runtime type `copilot` and use
`AgentRuntimeCoordinator`, `RuntimeEvent`, and `RuntimeEventStreamAdapter`. Extend
only the external-turn routing and validation needed to reach it.

**Rationale**: The registry already discovers provider beans, but registration
alone is insufficient. `AgentService.createAgent` accepts only native/DSH;
`updateAgent` validates only DSH; synchronous and streaming chat branch on
`isDshAgent`. `RuntimeSessionFactory` checks workspace containment only for DSH.
All these boundaries need explicit Copilot coverage. Preserve native defaults
and existing DSH behavior rather than replacing their execution loops.

**Alternatives considered**: A parallel Copilot chat endpoint would duplicate
conversation persistence and cancellation. Treating every unknown runtime as
external would weaken fail-closed selection. Rewriting all providers is unnecessary.

**Evidence**:
- `mateclaw-server/src/main/java/vip/mate/agent/AgentService.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeProviderRegistry.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeSessionFactory.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeEventProjector.java`

### R2 - Carry trusted turn context explicitly

**Decision**: Add a typed, server-only turn context to the runtime session boundary
with a source-compatible legacy constructor. It carries the verified `ChatOrigin`,
workspace root, and employee instructions. Resolve the employee's allowed tools
from application services, not caller-submitted configuration. Capture context
before asynchronous work; restore scoped context on each tool callback thread.

**Rationale**: `RuntimeSession` currently carries IDs, model, directory, and an
untyped configuration map, but no authenticated requester or employee instructions.
Copying a thread-local on an SDK callback thread cannot reconstruct authority.
`ChatOrigin`, `RuntimeExecutionScope`, and `ToolExecutionContext` demonstrate the
existing explicit-context and same-thread restoration conventions.

**Alternatives considered**: Putting authority or callback objects in
`runtimeConfig` allows client-controlled policy. Reconstructing identity from a
conversation name is unsafe. Changing DSH's prompt strategy as part of this feature
would introduce unnecessary regression scope.

**Evidence**:
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeSession.java`
- `mateclaw-server/src/main/java/vip/mate/agent/context/ChatOrigin.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/state/RuntimeExecutionScope.java`
- `mateclaw-server/src/main/java/vip/mate/tool/builtin/ToolExecutionContext.java`

### R3 - Reuse application tool governance, not the DSH-specific dispatcher

**Decision**: Advertise only employee-scoped host tools and execute each through
the application-owned `ToolExecutionExecutor`, with normal guard evaluation
(`isReplay=false`), immutable origin, timeout policy, and cancellation checkpoint.
Extract the existing build-time tool filtering into a small reusable resolver
used by the native builder and Copilot. Do not copy SDK-native execution authority.
Initial Copilot approval continuation is unsupported: approval-required actions
are explicitly blocked, without creating an approval that would replay via native.

**Rationale**: `AgentGraphBuilder.build` combines global denied tools, skill
discovery restrictions, the `load_skill` feature flag, and effective employee
bindings. `ToolExecutionExecutor` adds argument validation, Tool Guard, scope,
timeouts, and result policy. The DSH dispatcher has a narrower independent policy;
it is not a substitute for application governance. Native
`ApplicationGuardedToolkit` is useful precedent but depends on AgentScope-specific
types, so it is not the Copilot adapter.

**Alternatives considered**: Direct `ToolCallback.call` skips runtime governance.
Reusing `executePreApproved` skips ordinary guard evaluation. Automatically
approving provider permission requests violates the constitution. Implementing
durable cross-provider approval resume adds scope the specification does not require.

**Evidence**:
- `mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`
- `mateclaw-server/src/main/java/vip/mate/agent/AgentToolSet.java`
- `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/dsh/DshToolDispatcher.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/agentscope/ApplicationGuardedToolkit.java`

### R4 - Application history is authoritative; fresh provider sessions per turn

**Decision**: Use a new isolated provider session for every application turn and
replay bounded completed user/assistant history. Reuse the existing history
selection and channel policy through a provider-neutral helper, retaining the DSH
entry point as a delegating facade. Never replay tool requests as new actions.

**Rationale**: DSH already uses fresh-per-turn sessions with application-owned text
history, capped at 40 messages and an estimated 4,096 history tokens, excluding the
current message and non-completed rows. This prevents a second durable provider
history from becoming the source of truth after restart or runtime changes.
Keep current input and employee instructions intact; fail explicitly if they alone
exceed the selected provider's supported limit.

**Alternatives considered**: Durable provider session IDs create another recovery,
retention, and tenant-isolation surface. Copying all stored reasoning or tool output
into history needlessly discloses content and can repeat actions.

**Evidence**:
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/dsh/DshConversationHistory.java`
- `mateclaw-server/src/main/java/vip/mate/agent/context/ChannelHistoryPolicy.java`
- `mateclaw-server/src/test/java/vip/mate/agent/AgentServiceDshHistoryTest.java`

### R5 - Small additive configuration, no new schema

**Decision**: Store allowlisted deployment-wide `copilot.*` settings using
`SystemSettingService`; register the Copilot access-token key in its encrypted
sensitive-key set before any write. Expose sanitized global-admin configuration
and readiness endpoints. Keep employee `runtimeConfig` restricted to a model
override, not commands, credentials, permissions, environment, or endpoints.

**Rationale**: V186 already introduced `runtime_type VARCHAR(32)` and
`runtime_config`; no enum migration or new table is needed. The settings service
supports masked reads and encrypted storage only for explicitly registered secret
keys. DSH's managed generations, installation, and rollback are outside this scope.

**Alternatives considered**: Plaintext JSON token storage is unacceptable.
Environment-only configuration would not meet the administrator configuration
journey. A new settings table and installer add no required value.

**Evidence**:
- `mateclaw-server/src/main/resources/db/migration/h2/V186__agent_runtime_provider.sql`
- `mateclaw-server/src/main/resources/db/migration/mysql/V186__agent_runtime_provider.sql`
- `mateclaw-server/src/main/java/vip/mate/system/service/SystemSettingService.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/dsh/management/DshManagementController.java`

### R6 - Complete observability without fabricating capabilities or usage

**Decision**: Map provider events into existing normalized runtime events. Add
execution-time `runtimeProvider` attribution to live run snapshots and additive
usage-availability metadata to the shared stream/persistence/display boundary.
Retain existing numeric fields for legacy consumers but do not display absent
Copilot counters as measured zero. Leave delegated execution disabled initially.

**Rationale**: `AgentRuntimeAggregator.RunCard` currently has no provider field.
`RuntimeContextUsage` has nonnegative numeric values only, while
`AgentStreamAccumulator` initializes usage to zero and consumes `_usage_final`
using `promptTokens`, `completionTokens`, `runtimeModelName`, and
`runtimeProviderId`. Merely emitting `inputTokens` will lose attribution and usage.
Provider ownership must describe the active execution, not a possibly edited
employee record.

**Alternatives considered**: A separate runtime console duplicates existing UX.
Using zero as "unknown" violates FR-011. Adding subagents solely because the SDK
has agent features would exceed the governed scope.

**Evidence**:
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/AgentRuntimeAggregator.java`
- `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeContextUsage.java`
- `mateclaw-server/src/main/java/vip/mate/channel/web/AgentStreamAccumulator.java`
- `mateclaw-ui/src/api/index.ts`
- `mateclaw-ui/src/composables/chat/useChat.ts`

### R7 - Lifecycle deadlines and crash recovery are application responsibilities

**Decision**: Register a cancellation hook with `ChatStreamTracker`, guard one
terminal outcome atomically, and bound startup, idle progress, and cleanup.
Cancellation prevents scheduling new host actions and interrupts owned host work.
Use owned process handles only for hard cleanup. Persist a Copilot in-progress
assistant marker before provider start; on restart mark interrupted Copilot turns
failed, without replay or provider-session resume.

**Rationale**: Reactor cancellation alone does not necessarily stop asynchronous
SDK work. `ChatStreamTracker` already supports non-Reactor cancellation hooks,
force-recycle, orphan cleanup, and a configurable idle deadline. The existing
`ConversationSchemaMigration` resets orphaned streams to idle, but does not by
itself record an interrupted turn as failed. A scoped additive recovery action is
necessary to satisfy FR-014 without changing native or DSH history semantics.

**Alternatives considered**: Treating end-of-stream or provider idle as unconditional
success hides interruption. Killing processes by name risks unrelated executions.
Persisting and automatically replaying arbitrary tool calls risks duplicate effects.

**Evidence**:
- `mateclaw-server/src/main/java/vip/mate/channel/web/ChatStreamTracker.java`
- `mateclaw-server/src/main/java/vip/mate/workspace/conversation/config/ConversationSchemaMigration.java`
- `mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java`
- `mateclaw-server/src/main/java/vip/mate/channel/web/ChatController.java`

`ChatController` currently appends an assistant row from completion/error and
emergency-save paths; its shutdown comment permits a duplicate row in a race.
The Copilot path must update its existing marker instead, and channel/synchronous
save callers must use the same marker/outcome. An emitted normalized error followed
by Reactor completion must not pass through the existing successful-completion
heuristic. These are directly dependent integration changes, not a general
conversation persistence rewrite.

### R8 - Preserve the published SDK pin and research exact-tag APIs

**Decision**: Use the existing managed
`com.github:copilot-sdk-java:1.0.14-preview.1` dependency. It is an official published
artifact from `github/copilot-sdk`, tag `java/v1.0.14-preview.1`, commit
`557e496195c5c8dc1a964812f25790636930763e`. Do not replace it with an assumed
artifact or upgrade to a newer release during this feature without review.

**Rationale**: Public-source research verified the published POM and canonical
monorepo. The separately named `github/copilot-sdk-java` is a documentation/site
mirror rather than the SDK build source. The distributed SDK supports Java 17+,
so Java 21 is within its stated baseline. It uses Jackson 2.x; resolve dependency
alignment with the application's existing Jackson 2 compatibility layer during
the first compile. No library was installed or compiled during planning.

On Java 21, the SDK baseline uses the common pool unless an executor is supplied;
the MR-JAR's JDK 25 virtual-thread overlay does not apply. Supply a dedicated
host-owned executor so SDK work cannot monopolize JVM-global execution resources.
Do not assume the SDK closes a caller-owned executor.

**Alternatives considered**: Newest stable/preview may contain useful changes, but
would disregard the user's existing pin. Experimental in-process native mode and
shared remote CLI servers add platform/ownership risks; use an owned external CLI
process initially. SDK defaults that resemble an unrestricted CLI are unsuitable.

**Source references**:
- [Published POM](https://repo1.maven.org/maven2/com/github/copilot-sdk-java/1.0.14-preview.1/copilot-sdk-java-1.0.14-preview.1.pom)
- [Exact-tag Java README](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/README.md)
- [SDK client source](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/CopilotClient.java)
- [Session source](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/CopilotSession.java)
- [Session configuration](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/SessionConfig.java)
- [Baseline executor](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/InternalExecutorProvider.java)

### R9 - Treat the CLI binary as an externally supplied, handshake-gated prerequisite

**Decision**: Require a deployment-owned executable and verify protocol, auth,
model availability, isolation, and fail-closed tool behavior before enablement.
Record exact SDK/CLI/platform combination in real acceptance evidence. No claimed
minimum CLI version is invented, and no CLI installer is added.

**Rationale**: The Java SDK does not bundle the Copilot CLI by default. Research
did not establish an authoritative SDK-to-CLI version matrix for this pin.
This is resolved as a runtime compatibility contract: installed binaries that
fail the required handshake/capability checks are unavailable. A live tested
binary pin is release evidence still required during implementation.

**Alternatives considered**: Assuming any `copilot` on PATH is compatible or
declaring source inspection equivalent to a tested installation would overstate
evidence. Embedding an unverified binary or introducing an installer exceeds scope.

**Source references**:
- [Canonical repository FAQ](https://github.com/github/copilot-sdk/tree/java/v1.0.14-preview.1)
- [Exact-tag Java prerequisites](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/README.md)

### R10 - EMPTY mode and typed custom-only filters are the isolation baseline

**Decision**: Use `CopilotClientMode.EMPTY` with an explicit unique `copilotHome`,
session configuration directory, explicit credential and allowlisted environment.
Set a non-null `ToolSet` on every session, containing only `addCustom(name)` entries
for application-scoped tools, or an empty set for text-only execution.
Explicitly disable memory/session store, omit native MCP registration, prohibit
filesystem agent discovery, and do not expose built-in tools.

**Rationale**: Exact-tag source enforces an explicit home or external URL in EMPTY
mode and rejects null available-tools configuration. Custom tools are also subject
to filtering through the `custom:` prefix; registering tool definitions alone is
not enough. EMPTY mode disables optional ambient behavior and forces excluded-tool
precedence. `BuiltInTools.ISOLATED` includes `task`, so it is broader than this
feature's no-delegation policy and must not be used wholesale.

The pre-tool hook uses `PreToolUseHookOutput.deny(reason)` for unregistered tools.
The permission callback uses `PermissionRequestResult.reject(reason)` for
ungoverned requests. Neither hook substitutes for application Tool Guard on
registered host actions. Employee instructions use `SystemMessageMode.APPEND`;
`REPLACE` would remove provider guardrail language.

**Alternatives considered**: Default `COPILOT_CLI` mode has intentionally ambient
CLI behavior. A wildcard custom/MCP filter, all-isolated built-ins, or approve-all
callback exposes more capability than required. Source-level settings alone are
not sufficient proof that an installed CLI cannot load ambient configuration.

**Exact-tag source references**:
- [Client mode](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/CopilotClientMode.java)
- [ToolSet](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/ToolSet.java)
- [Built-in groups](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/BuiltInTools.java)
- [Pre-tool decision](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/PreToolUseHookOutput.java)
- [Permission result](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/PermissionRequestResult.java)
- [System message modes](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/SystemMessageMode.java)

### R11 - Use hard-stop and verify the five-second target empirically

**Decision**: Use SDK-spawned per-turn local processes, `abort()` and then
`forceStop()` with application-owned async cleanup. Bound the caller wait, retain
cleanup ownership if it overruns, and fail enablement acceptance unless all 20
controlled trials meet five seconds. Do not claim a source-proven five-second
upper bound. Disable auto-restart and never resume provider sessions.

**Rationale**: `stop()` can spend ten seconds in shutdown RPC plus two ten-second
process waits. `forceStop()` skips graceful shutdown and immediately force-kills
the process, but still has a ten-second reap wait. There is no public process/PID
accessor or shorter reap-timeout option at this pin. A future wrapper around a
host-spawned loopback server could provide direct process ownership, but requires
more transport/authentication/port management and is not justified by the current
controlled-test target. If hard real-time cleanup is required or acceptance fails,
that alternative requires a design revision before enablement.

Protocol source defines `SdkProtocolVersion.LATEST(3)`. Require the actual CLI to
pass protocol-3 negotiation and isolation checks; the specific working CLI semver
still belongs in implementation acceptance evidence.

Available source-verified usage includes `SessionUsageInfoEvent.currentTokens`
and `tokenLimit`; treat input/output usage as unavailable until the pinned
provider emits verified counters. No missing counter is inferred from context size.
Detailed assistant counters and arbitrary-schema tool wiring must be pinned by
compile/fixture tests during the first implementation slice, not invented here.

**Alternatives considered**: Calling ordinary close/stop inline can exceed the
acceptance budget. Timing out a future does not terminate a process or callback.
Reflection into private process state, process-name killing, or automatic retry
after an uncertain effect is unacceptable.

**Exact-tag source references**:
- [Lifecycle and process cleanup](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/CopilotClient.java)
- [Session abort and external tool cancellation](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/CopilotSession.java)
- [Launch options](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/rpc/CopilotClientOptions.java)
- [Protocol version](https://github.com/github/copilot-sdk/blob/java/v1.0.14-preview.1/java/sdk/src/main/java/com/github/copilot/SdkProtocolVersion.java)

## Research completion and remaining validation

All design unknowns are resolved into decisions above; no user clarification is
required. Public-source research confirms the pin and baseline APIs, not a running
installation. Compile-time Jackson/schema compatibility, actual CLI protocol/model
access, ambient-feature suppression, host-tool policy, and stop timing are explicit
implementation/release gates in the plan and quickstart. No dependencies were
installed and no provider prompts were sent in this planning phase.

## Planning environment

- Java 21.0.9 and Maven 3.9.16 are available locally.
- Root build specifies Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, AgentScope
  2.0.4, and MCP SDK 0.18.3. UI uses Vue 3.5, TypeScript 5.7, Vitest 4, and ESLint 9.
- Existing user changes already manage `com.github:copilot-sdk-java` version
  `1.0.14-preview.1` in the root POM; the server does not yet consume it. Preserve
  these edits. Dependency verification is recorded separately below.
- `setup-plan.sh --json` resolves this feature correctly but reports
  `BRANCH=001-add-copilot-runtime` from feature-path resolution. Actual Git branch
  remains `copilotclaw`; no branch creation hook is configured.
