# Implementation Plan: Copilot Runtime

**Branch**: `copilotclaw` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)
**Input**: `specs/001-add-copilot-runtime/spec.md`

## Summary

Add an opt-in `copilot` provider using the requested Java SDK, keeping application
conversation storage, employee identity, tool authority, and lifecycle ownership.
Use fresh isolated provider sessions per turn; normalize their events into the
existing runtime contract. Extend the existing employee editor, wizard, chat, and
live runtime display, plus a small global-admin configuration/readiness surface.
Do not implement DSH-style installation, upgrades, or durable provider-session resume.

Planning stops after research and interface design. Implementation and `tasks.md`
generation are separate phases. Proposed classes below do not exist yet.

## Technical Context

**Language/Version**: Java 21; TypeScript 5.7; Vue 3.5.
**Primary Dependencies**: Spring Boot 4.1.1, Spring AI 2.0.1, Reactor, MyBatis-Plus
3.5.17; official `com.github:copilot-sdk-java:1.0.14-preview.1` (Java 17+
baseline, protocol 3). Exact-tag API findings are recorded in research R8-R11.
**Storage**: Existing application database and `mate_agent`, `mate_message`,
`mate_conversation`, and system settings; isolated temporary provider state. No new
schema or migration planned.
**Testing**: JUnit Jupiter, Mockito, Reactor tests where already supported; Vitest,
ESLint, vue-tsc, Vite. Controlled SDK fixtures plus opt-in real-provider acceptance.
**Target Platform**: Existing Java server deployments; first real-provider
acceptance on macOS arm64, then Linux x64 before declaring Linux support verified.
**Project Type**: Maven multi-module server and Vue web console.
**Performance Goals**: Five concurrent isolated conversations; 20 controlled stop
trials each complete cleanup within five seconds; first configured conversation
within five minutes. No invented provider latency or throughput guarantee.
**Constraints**: Disabled by default; no new model-provider service; encrypted
credentials; no ambient user login; fail-closed tools; reject strict member file
isolation initially; no native/DSH behavioral changes.

**SDK lifecycle constraint**: Use one SDK-spawned, isolated local CLI per turn,
with `EMPTY` mode, explicit home/environment/token, and automatic restart disabled.
Request `abort()`, then use `forceStop()` on a dedicated cleanup worker rather than
the potentially 30-second graceful `stop()` path. The SDK's hard-stop reap bound is
10 seconds; the specification's five-second controlled acceptance target is
therefore a measured release gate, not a source-proven guarantee. An overrun is a
failed acceptance/cleanup check, never claimed successful cleanup; the worker
continues owning cleanup and the runtime is not enabled. Do not invent a public
SDK process-handle API or add a new TCP supervisor without a reviewed design change.

The root POM's existing user edits pin
`com.github:copilot-sdk-java:1.0.14-preview.1`; preserve them and add the server
dependency only during implementation after compatibility verification.

## Constitution Check

*Initial design gate: assessed before design. Post-design gate: assessed after all
contracts and research are complete. A failed gate blocks implementation.*

| Principle | Initial | Post-design | Evidence in design |
|-----------|---------|-------------|--------------------|
| I. Tenant Isolation and Least Privilege | PASS | PASS | Trusted origin and real-path workspace scope; EMPTY mode with custom-only filters and isolated home; server authorization; member-isolation mode fails closed. |
| II. Governed Agent and Tool Execution | PASS | PASS | Only application-scoped tools through normal Tool Guard; SDK and host cancellation; unsupported approvals/delegation blocked; no blind retries. |
| III. Evidence-Based Correctness | PASS | PASS | Exact-tag SDK source researched; contract/lifecycle/authorization/history/UI/regression test design; real CLI and five-second cleanup explicitly unverified release gates. |
| IV. Durable, Reversible Change | PASS | PASS | Existing schema reused; application history authoritative; generating marker becomes failed on restart; disable/reassign without deleting history. |
| V. Resilience and Operability | PASS | PASS | Deadlines, one outcome, provider attribution, usage availability, redaction; SDK hard-stop limitation disclosed and tested before enablement. |
| Technology and data constraints | PASS | PASS | Java 21 baseline supported by pinned SDK; framework versions preserved; dependency compile and live CLI compatibility remain required implementation checks. |

No constitutional exception is proposed.

Post-design review passes for the proposed design, not for unbuilt implementation.
No research clarification remains. Exact CLI compatibility, dependency convergence,
actual tool suppression and five-second stop measurements are explicitly assigned
verification gates rather than assumed facts.

## Project Structure

### Documentation (this feature)

```text
specs/001-add-copilot-runtime/
  spec.md
  plan.md
  research.md
  data-model.md
  contracts/
    runtime-provider.md
    management-api.md
  quickstart.md
  checklists/requirements.md
```

`tasks.md` is intentionally deferred to `/speckit-tasks`.

### Source Code (repository root)

```text
pom.xml                                      # existing SDK management, preserve
mateclaw-server/pom.xml                       # consume managed SDK dependency
mateclaw-server/src/main/java/vip/mate/
  agent/AgentService.java                     # selection, validation, chat routing
  agent/controller/AgentController.java       # provider-aware capabilities/pins
  agent/AgentGraphBuilder.java                # reuse extracted tool filtering
  agent/runtime/
    contract/RuntimeSession.java              # compatible trusted-context addition
    contract/RuntimeTurnContext.java          # new: trusted origin/root/instructions
    contract/RuntimeSessionFactory.java        # Copilot scope validation
    contract/AgentRuntimeCoordinator.java      # typed-context overload
    contract/RuntimeContextUsage.java          # additive availability semantics
    RuntimeEventProjector.java                # additive metadata, existing vocabulary
    AgentRuntimeAggregator.java               # active execution provider attribution
    RuntimeToolSetResolver.java               # new: existing binding/filter policy
    RuntimeConversationHistory.java           # new: existing bounded history policy
    copilot/
      CopilotRuntimeService.java               # provider; creates owned connection
      CopilotSdkClient.java                    # narrow SDK test/ownership boundary
      CopilotRuntimeConnection.java            # turn state and bounded teardown
      CopilotEventMapper.java                  # SDK event normalization
      CopilotToolBridge.java                   # schema adaptation and governed calls
      CopilotRuntimeConfigService.java         # settings, validation, secret masking
      CopilotManagementController.java         # global-admin config and readiness
    dsh/DshConversationHistory.java            # preserve old API via shared helper
  channel/web/ChatStreamTracker.java           # capture provider on active run
  channel/web/AgentStreamAccumulator.java      # usage availability and turn metadata
  channel/web/ChatController.java              # finalize same marker, all exit paths
  channel/ChannelMessageRouter.java            # channel saves reuse marker/outcome
  agent/graph/executor/ToolExecutionExecutor.java
                                             # per-executor approval continuation mode
  system/service/SystemSettingService.java    # encrypted Copilot token key
  workspace/conversation/ConversationService.java
                                             # generating marker, conditional finalization
  workspace/conversation/config/ConversationSchemaMigration.java
                                             # Copilot-only interruption reconciliation
mateclaw-server/src/main/resources/
  application.yml                            # disabled opt-in properties
  messages.properties                        # established default locale
  messages_en.properties                     # translated configuration/errors
mateclaw-server/src/test/java/vip/mate/
  agent/runtime/copilot/                     # new unit/contract/real acceptance tests
  agent/runtime/contract/                     # session and registry regression tests
  agent/                                     # routing, context, history regressions
  channel/web/                               # cancellation/metadata regressions
  workspace/conversation/                     # crash and finalization tests
  system/service/                            # secret encryption regression
mateclaw-ui/src/
  api/index.ts                               # admin client and live card additions
  types/index.ts                             # Copilot selection and usage availability
  views/Agents.vue                           # editor, badges/filter, active runtime
  views/AgentCreateWizard.vue                 # preserve selection through draft/save
  views/Settings/Copilot/index.vue            # new: config and readiness only
  views/Settings/Layout.vue                  # global-admin navigation
  router/index.ts                            # admin settings route
  composables/chat/useChat.ts                 # honest unknown usage and attribution
  utils/runtimeSelection.ts                  # shared editor/wizard runtime rules
  utils/__tests__/runtimeSelection.test.ts
  i18n/locales/en-US.ts
  i18n/locales/zh-CN.ts
```

**Structure Decision**: Keep Copilot-specific transport and policy adaptation
together under the existing runtime package. Extract only the existing tool
filter/history decisions needed by a second caller; do not restructure the native
agent graph or DSH process manager. Reuse shared event types and persistence.

## Execution Strategy

### TDD Requirements

- [ ] Provider/session boundary: unsupported runtime, client authority injection,
  missing origin, cross-workspace and symlink escape fail before startup.
- [ ] SDK connection: streaming/final-message deduplication, one terminal outcome,
  error-before-idle, disconnect-without-terminal, cancellation and repeated close.
- [ ] Governed tools: allowed read, read-only write denial, disabled tool, forged
  name, approval-required block, malformed arguments, context restoration, timeout,
  cancellation race, and no new action after finalization.
- [ ] History and recovery: bounded completed text, current-message deduplication,
  channel policy, restart after partial output, uncertain side-effect no-replay.
- [ ] Configuration: encrypted secret round-trip, masked keep/clear semantics,
  unknown-key rejection, non-admin denial, disabled-provider startup.
- [ ] UI/API: wizard and editor preserve `copilot`; optional capability/usage is
  displayed honestly; provider badge reflects the running turn, not edited metadata.

### Implementation Slices and Interfaces

These are design boundaries for later task generation, not an instruction to start
implementation or a substitute `tasks.md`.

1. **Configuration and dependency boundary**: Consume the managed SDK, implement
   typed settings/readiness and secret handling, and expose the management contract.
   `CopilotRuntimeConfigService.resolve()` returns an immutable validated snapshot;
   probes never submit user prompts. Verify dependency alignment without upgrading
   Spring/Jackson globally.
2. **Trusted session and scoped tools**: Introduce
   `RuntimeTurnContext(ChatOrigin origin, Path workspaceRoot, String systemPrompt)`
   and a compatible session/coordinator overload. Extract
   `RuntimeToolSetResolver.resolve(AgentEntity employee): AgentToolSet`.
   Preserve existing native filtering, and reject Copilot authority supplied through
   `runtimeConfig`.
3. **Provider and lifecycle**: `CopilotRuntimeService` implements the existing
   `type/validate/capabilities/start` contract; `start` returns a connection with
   one prompt, bounded cancel, usage reporting, and idempotent close.
   `CopilotSdkClient` wraps only the selected SDK's client/session APIs, so tests
   can drive deterministic events without a credential or live model.
4. **Governed tool bridge**:
   `CopilotToolBridge` adapts `AgentToolSet` schemas and serializes host calls for one
   turn. Invoke `ToolExecutionExecutor.execute(..., false, ..., origin)` with a
   cancellation checkpoint. An approval-disabled invocation context blocks before
   the executor can create a replayable pending approval; the default native
   behavior remains unchanged. Use a turn-owned executor rather than mutate a
   shared native executor's policy/checkpoint. Keep configured auto-grants subject
   to existing guard rules. Translate results using the provider contract.
5. **Application routing/history/persistence**: Extend only explicit `copilot`
   selection paths, including synchronous chat and streaming/channel callers.
   `RuntimeConversationHistory.enrich(...)` retains DSH's existing selection policy.
   Begin and conditionally finalize one assistant row per Copilot turn, using an
   execution ID to reject late writes and mark interrupted work failed on restart.
   Update `ChatController` completion/error/cancel/emergency-save and
   `ChannelMessageRouter` save paths to reuse that row for Copilot only; the current
   append-on-shutdown behavior must not duplicate it. Carry explicit normalized
   terminal outcome through accumulator and synchronous `ChatResult`, not an
   error-string prefix or plain Flux completion. Include marker ID, outcome,
   usage availability and actual model in completion payload and history metadata.
   Reject native-only orchestration/replay for Copilot rather than silently running
   it through a native graph.
6. **Console integration**: Add selection, validation, admin settings, translated
   messages, provider-at-start attribution, and usage availability to existing
   consumers. No new chat protocol or parallel runtime console.
7. **Acceptance and rollout**: Execute focused suites, existing native/DSH
   regressions, and opt-in real-provider tests. Keep disabled until the controlled
   host-tool, startup isolation, authentication, and teardown checks pass.

### Parallel Execution Opportunities

- SDK adapter fixtures and UI tests may be prepared independently once
  [runtime-provider.md](contracts/runtime-provider.md) and
  [management-api.md](contracts/management-api.md) are fixed.
- Server routing/history and settings UI can proceed independently after the
  trusted-context and management interfaces exist.
- Do not parallelize edits to `AgentService`, shared runtime records, or tool
  executor approval handling; these have shared invariants.

### Human Checkpoints

1. Before implementation, review the dependency pin and fail-closed capability
   choices. This plan does not authorize exposing SDK-native tools or delegated work.
2. Before any real-provider validation, confirm the deployment-approved account,
   CLI installation, isolated test workspace, and permission to send test prompts.
3. Before enablement, inspect the 20-stop/five-conversation evidence and application
   tool effects, including negative authorization and strict-isolation cases.
4. Before merge, review configuration/redaction, platform evidence, recovery and
   rollback, and native/DSH regression results.

### Review Gates

- [ ] Runtime/session and management contracts reviewed before consumers.
- [ ] Tool catalog restrictions and actual provider-native capabilities verified
  before task content is sent.
- [ ] Async cancellation, owned process cleanup, and late callback fencing reviewed.
- [ ] Copilot marker/recovery and nullable usage behavior reviewed before persistence
  integration; add a migration only if implementation discovers a real schema need.
- [ ] User changes in root `pom.xml` and unrelated untracked files remain untouched.

### Requirement Coverage

| Requirements | Design owner | Validation |
|--------------|--------------|------------|
| FR-001, FR-013 | Configuration, explicit routing, editor/wizard | Selection round-trip; native/DSH with Copilot absent and configured |
| FR-002, FR-009 | Trusted session and scoped tool resolver | Identity/context capture; unauthorized access; path and strict-isolation rejection |
| FR-003, FR-005 | Event mapper and connection | Progressive deltas; final-message deduplication; one terminal outcome |
| FR-004 | Shared bounded history | Two-turn follow-up; completed-only replay; channel/current-message rules |
| FR-006, FR-015 | Connection and tracker integration | 20 controlled stops; idle/start deadlines; five concurrent turns |
| FR-007, FR-008 | Governed bridge/executor | Allowed/denied/approval-required/failed calls; paired call IDs; no bypass |
| FR-010, FR-012 | Settings/readiness/controller | Admin authorization; configuration matrix; encryption/redaction |
| FR-011 | Event mapper, accumulator, live snapshots, UI | Measured vs unavailable usage; accurate execution provider |
| FR-014 | Assistant marker and restart reconciliation | Interrupted turn failed; history preserved; no automatic side-effect replay |

### Review Focus

- A saved partial employee edit must not reset `copilot` or accidentally clear its
  model override: cover in routing and wizard/editor round-trip tests.
- A cancellation racing tool dispatch must fence invocation before it starts:
  use a controlled latch in bridge/connection tests and inspect side effects.
- Provider final full text may duplicate earlier deltas: fixture tests assert the
  answer is shown and persisted once.
- A shared CLI home or project configuration may load unintended context/tools:
  isolated-home startup acceptance must prove it cannot affect another turn.
- A process exit after a write but before completion must not replay the write:
  crash/recovery acceptance verifies the artifact before allowing another action.

## Complexity Tracking

No constitutional violations or extra services are required. The trusted-context
addition, shared tool/history helpers, and persisted in-progress marker address
specific existing boundary gaps; they are not a general runtime redesign.
