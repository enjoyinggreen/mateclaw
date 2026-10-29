---
description: "Executable, story-based tasks for the optional Copilot runtime"
---

# Tasks: Copilot Runtime Implementation Plan

> **For agentic workers:** When implementation is explicitly requested, use
> `executing-plans` for inline execution or `subagent-driven-development` for an
> approved delegated approach; use `test-driven-development` for `[TDD]` slices.

**Goal**: Add an optional governed Copilot Java SDK runtime with existing
conversation, tool, lifecycle and configuration experiences.
**Architecture**: Own an isolated SDK-spawned CLI and fresh session per turn.
Application-owned history, trusted context, Tool Guard and conditional assistant
finalization remain authoritative; shared native/DSH contracts stay compatible.
**Tech Stack**: Java 21, Spring Boot 4.1.1, Spring AI 2.0.1,
Copilot Java SDK 1.0.14-preview.1, Vue 3.5, TypeScript 5.7, JUnit and Vitest.
**Spec**: [spec.md](spec.md).

**Input**: Design documents in `specs/001-add-copilot-runtime/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md),
[research.md](research.md), [data-model.md](data-model.md),
[runtime contract](contracts/runtime-provider.md),
[management contract](contracts/management-api.md), [quickstart.md](quickstart.md),
and `.specify/memory/constitution.md`.
**State**: Phase 6 is complete and Phase 7 is approved. T034's app-backed
restart-after-write scenario has not been run with a live provider. No live
provider acceptance is authorized or has been run; T043 remains blocked pending
separate authorization and its required disposable acceptance environment.
T045's code finding is fixed and its focused tests pass, but final release review
remains blocked by T043.

**T042 validation**: The current focused server gate ran 293 tests with 0
failures and 7 opt-in Copilot acceptance tests skipped because
`MATECLAW_COPILOT_ACCEPTANCE` was unset. Copilot and adjacent UI regression
tests passed 82/82; precision check, Vue type-check, and full UI build passed.
Full non-fixing ESLint reported 3 existing errors in
`ToolResultChart.test.ts:16` and `useAgentRunGroups.ts:185` plus 46 warnings;
neither error file was changed. The standalone `pnpm` command was not on PATH;
Corepack invoked pnpm for UI checks. The clean full server
run with `env -u MATECLAW_COPILOT_ACCEPTANCE -u DOCKER_HOST mvn -pl mateclaw-server clean test`
passed 6,888 tests with 0 failures, 0 errors, and 69 skips. `DOCKER_HOST` was
unset because the DSH supervisor test deliberately verifies its fake
daemon-context selection; the ambient override otherwise bypasses that fixture.

**T045 review**: The fresh whole-branch review found an Important race between
the tool-admission check and host callback invocation. The callback-entry gate now
serializes closure with callback invocation, interrupts the active callback, and
holds terminal publication until that invocation settles; latch-controlled bridge
and lifecycle tests cover the regression. A non-cooperative callback can delay
terminalization, so the five-second stop acceptance remains mandatory and T043
remains blocked. The clean full server suite now passes with the ambient Docker
override removed. No live-provider acceptance or merge/enablement approval is
claimed.
**Ruling**: An admitted host callback settles before terminal publication — this
preserves the no-post-terminal-tool-execution contract — cost if wrong: a
non-cooperative callback can delay cancellation beyond the five-second target.

## Task Format

Every actionable task is
`- [ ] Tnnn [P?] [USn?] [TDD?] [REVIEW?] [SUBAGENT?] Description with exact file paths`.
Story labels apply only to story phases. Tests are included because the approved
plan explicitly requires TDD and the constitution requires boundary/failure tests.
This enhanced Superspec task list preserves the original 45 IDs and story order.

Each behavior task follows RED-GREEN-REFACTOR: add the named assertions, run the
smallest applicable test selector and confirm the intended failure, implement,
rerun to green, then refactor only if needed. Test-only tasks leave an intentional
red state until their dependent implementation tasks; do not commit a broken
logical slice as completed. Record test evidence when checking a task off.

| Marker | Execution rule |
|--------|----------------|
| `[P]` | Eligible parallel lane **after listed prerequisites**; never concurrent shared-file edits. |
| `[TDD]` | Follow the RED-GREEN-REFACTOR slice protocol below, including test-first integration assertions. |
| `[REVIEW]` | Pause for human code/design review and explicit approval before dependent work; see the review-gate table. |
| `[SUBAGENT]` | Optional bounded delegation candidate, not an instruction to launch agents or workflows. |

Only the substantial independent foundation extractions/settings lane T004-T006
are marked for optional delegation. Other `[P]` tasks identify file-safe lanes,
not a requirement to spend separate agent contexts on test preparation.

## Path Conventions

- Server implementation: `mateclaw-server/src/main/java/vip/mate/`.
- Server tests: `mateclaw-server/src/test/java/vip/mate/`.
- Server resources: `mateclaw-server/src/main/resources/`.
- Console: `mateclaw-ui/src/`; test files use existing Vitest conventions.
- Paths on task lines are repository-relative and name files to create or modify.
  New test files/classes are implementation deliverables, not existing evidence.

## Global Constraints

- Preserve unrelated work, including existing root `pom.xml` SDK management and
  `pom.xml.local`; no branch creation, commit, implementation, or live tests occurs
  during task generation. Implementation commits, if requested, use precise staging.
- Keep Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 and the managed
  `com.github:copilot-sdk-java:1.0.14-preview.1`; do not upgrade frameworks implicitly.
- Use SDK-spawned local CLI, protocol 3, EMPTY mode, explicit token/home/environment,
  fresh sessions, and no auto-restart. No new installer, TCP server, BYOK, or schema.
- Native remains the default. Changes to shared classes preserve native/DSH
  constructors, filtering, history, approvals, and lifecycle behavior.
- Approvals/delegation are disabled for Copilot initially; block required approval
  without pending native replay. Do not copy DSH's narrower independent dispatcher.
- The SDK force-stop reap bound is ten seconds. Five seconds is a **measured
  acceptance gate**, never inferred from caller timeout. Retain cleanup ownership
  after an overrun and keep deployment disabled if required acceptance fails.
- Read the contract error vocabulary rather than inventing incompatible responses.
  No secret, raw provider dump, or unauthorized content belongs in logs/evidence.

## Review Focus

| Input or failure condition | Required behavior and owning tests |
|----------------------------|------------------------------------|
| Partial employee updates and editor hydration | Preserve `copilot` and its model override, never silently select native; T010/T017, implemented by T015/T018. |
| Stop racing tool admission | No host action begins after finalization; latch-controlled side-effect assertions in T020/T027, implemented by T023/T029. |
| Final full message repeats deltas | Display and persist one answer, with one terminal outcome; T011/T012, implemented by T013-T016. |
| Hostile ambient CLI/project configuration | No extra context, tools or authority; T007/T024 deterministic checks plus T034/T043 real isolation evidence. |
| Exit after a possibly committed external write | Preserve partial history/artifact, fail interrupted work, never replay automatically; T028/T031 and T034/T043. |

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the exact dependency/API feasibility without starting a CLI.

- [x] T001 Consume the already managed `com.github:copilot-sdk-java:1.0.14-preview.1` in `mateclaw-server/pom.xml` without reverting or duplicating user edits in `pom.xml`; run Java 21 reactor compile and filtered dependency tree from `specs/001-add-copilot-runtime/quickstart.md`, retaining existing Spring/Jackson alignment and reporting any compatibility blocker before proceeding.
- [x] T002 [REVIEW] Add executable exact-pin compatibility fixtures in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotSdkCompatibilityTest.java` that round-trip a real nested Spring ToolDefinition JSON schema through the SDK custom-tool definition, capture typed events, and construct EMPTY-mode empty/custom-only filters without network/process startup; verify the pinned API actually supports the planned schema/permission/lifecycle wiring and stop for design review if it does not.

**Checkpoint**: The server compiles, the fixtures run rather than skip, and no
framework upgrade, invented SDK signature, CLI spawn, or credential use was needed.

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Supply secure context, configuration, isolated transport, and shared
history/tool/outcome contracts before any selectable Copilot story.

- [x] T003 [TDD] Add test-first `RuntimeTurnContext(ChatOrigin origin, Path workspaceRoot, String systemPrompt)` in `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeTurnContext.java` and compatible additions to `RuntimeSession.java`, `RuntimeSessionFactory.java`, and `AgentRuntimeCoordinator.java` in that directory; extend `mateclaw-server/src/test/java/vip/mate/agent/runtime/contract/RuntimeSessionFactoryTest.java` and `AgentRuntimeCoordinatorTest.java` to require matching employee/conversation/workspace and verified requester for Copilot, reject missing context, nonexistent paths, symlink escapes and strict member isolation before content transmission, and retain legacy constructors. Enforce: "Working directory and root must exist and satisfy canonical containment; symlinks are resolved before validation."
- [x] T004 [P] [TDD] [SUBAGENT] Extract `RuntimeToolSetResolver.resolve(AgentEntity employee): AgentToolSet` to `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeToolSetResolver.java` and replace only the identical filtering in `mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`; add `mateclaw-server/src/test/java/vip/mate/agent/runtime/RuntimeToolSetResolverTest.java` proving global denies, skill-discovery policy, disabled `load_skill`, aliases, and "null = no restriction" versus an explicitly empty bound-tool allowlist remain unchanged.
- [x] T005 [P] [TDD] [SUBAGENT] Extract bounded text selection into `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeConversationHistory.java` while preserving `mateclaw-server/src/main/java/vip/mate/agent/runtime/dsh/DshConversationHistory.java` as a delegating facade; extend `mateclaw-server/src/test/java/vip/mate/agent/runtime/dsh/DshConversationHistoryTest.java` and add `mateclaw-server/src/test/java/vip/mate/agent/runtime/RuntimeConversationHistoryTest.java` for the existing 40-message/4,096-estimated-history-token limits, completed-user/assistant-only selection, current-message deduplication, channel/cron rules, Unicode boundary and conversation isolation; do not truncate current input or replay tool requests.
- [x] T006 [P] [TDD] [SUBAGENT] Implement test-first immutable settings resolution/save in `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotRuntimeConfigService.java`, register `copilot.github_token` in the sensitive-key set in `mateclaw-server/src/main/java/vip/mate/system/service/SystemSettingService.java`, and add disabled defaults to `mateclaw-server/src/main/resources/application.yml`; cover in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotRuntimeConfigServiceTest.java` and `mateclaw-server/src/test/java/vip/mate/system/service/SystemSettingServiceCatalogTest.java` the four allowlisted `copilot.*` keys, encrypted-at-rest round-trip, explicit-secret-only auth, default startup without CLI, managed-over-property precedence, 30,000ms initialize/5,000ms stop defaults, and repairable invalid storage. Enforce: "Blank/masked token updates mean keep, and `clearToken=true` explicitly clears the stored token; a clear with a new token is invalid."
- [x] T007 [TDD] [REVIEW] Implement the narrow owned `CopilotSdkClient` wrapper in `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotSdkClient.java` with deterministic tests in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotSdkClientTest.java`; use EMPTY mode, distinct private home/config per execution, explicit process/session cwd, an allowlisted child environment, explicit token, dedicated host-owned executor, APPEND instructions, memory/store disabled, no native MCP/custom-agent discovery, empty tools by default, auto-restart off and no resume; prove denied unknown permission/tool callbacks, startup-failure cleanup and async abort/forceStop ownership without touching real user homes or spawning provider work in ordinary tests.
- [x] T008 [TDD] [REVIEW] Extend explicit usage availability and terminal metadata compatibly in `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeContextUsage.java`, `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeEventProjector.java`, and `mateclaw-server/src/main/java/vip/mate/channel/web/AgentStreamAccumulator.java`; add `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotOutcomeProjectionTest.java` and extend `mateclaw-server/src/test/java/vip/mate/agent/runtime/RuntimeEventProjectorTest.java` to preserve old constructors and capture runtimeExecutionId/assistant marker ID, one explicit outcome, actual provider/model, context-only measurements and absent counters. Enforce: "Never invent prompt/output counters from current context length."
- [x] T009 [REVIEW] Validate and review foundation against `specs/001-add-copilot-runtime/contracts/runtime-provider.md` and `specs/001-add-copilot-runtime/contracts/management-api.md`; run the new compatibility/config/session/resolver/history/projection tests plus existing runtime contract and DSH history tests, verify no provider startup at application boot, and request human approval of SDK pin, fail-closed filters and the disclosed stop limitation before story consumers.

**Checkpoint**: All foundation tasks are complete and reviewed. Text-only is the
default SDK tool posture; full host-tool enablement is deliberately deferred to US2.

## Phase 3: User Story 1 - Choose Copilot and have a conversation (Priority: P1) MVP

**Goal**: Persist Copilot selection and complete contextual, streamed or synchronous
text conversations through the existing experience. This increment exposes no tools.
**Independent Test**: Select Copilot for one authorized employee with explicit test
configuration, exchange two related messages, reload history and verify employee
instructions, one answer per turn and unchanged native/DSH behavior.

### Tests for User Story 1

- [x] T010 [P] [US1] [TDD] Write failing selection/routing/model tests in `mateclaw-server/src/test/java/vip/mate/agent/AgentServiceCopilotTest.java` for create/update, omitted-field preservation, explicit model precedence (conversation pin > runtimeConfig.model > employee modelName > admin default), non-Copilot pins rejected, sync/stream/channel provider routing and unsupported native-only execute/approval replay rejection; enforce "`runtimeConfig` absent, blank, or `{}` means no employee-specific override" and "`model`, when present, must be a nonblank string", rejecting unknown keys, credentials, paths, commands, environment, tool policy and endpoints.
- [x] T011 [P] [US1] [TDD] Write failing provider/event tests in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotRuntimeServiceTest.java` and `CopilotEventMapperTest.java` in the same directory: type `copilot`, disabled/invalid startup, progressive text/reasoning, full-message delta deduplication by message ID, increasing sequence, error-before-idle, EOF without completion, one send per connection, and no event after terminal finalization.
- [x] T012 [P] [US1] [TDD] Write failing marker/finalization tests in `mateclaw-server/src/test/java/vip/mate/workspace/conversation/CopilotTurnPersistenceTest.java` and `mateclaw-server/src/test/java/vip/mate/channel/web/ChatControllerCopilotTest.java`: create one authorized generating assistant before SDK work, conditional updates require row and execution IDs, duplicate completion/error/cancel/emergency-save cannot append another assistant, and normalized failure followed by Flux completion remains failed.
- [x] T013 [US1] [TDD] Implement `CopilotRuntimeService` and `CopilotEventMapper` in `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotRuntimeService.java` and `CopilotEventMapper.java`, plus the text-only single-prompt connection in `CopilotRuntimeConnection.java` in that directory; register through existing provider discovery, validate before startup, use fresh opaque execution/provider IDs and handlers-before-send, enforce EMPTY empty tools and no resume, map text/reasoning/measured usage/error/verified completion without duplicate final text, and turn T011 green using T007/T008.
- [x] T014 [US1] [TDD] [REVIEW] Implement Copilot begin/checkpoint/conditional-finalization methods in `mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java` using existing `mate_message` and metadata, not a new table; make T012 persistence assertions pass. Enforce "`role` | `assistant`", "`status` | `generating`, then `completed`, `failed`, or existing `stopped` for normalized cancellation", and "`metadata.runtimeOutcome` | `completed`, `failed`, or `cancelled` when terminal"; preserve saved partial output, forbid replacing a terminal outcome, and return the same assistant ID on idempotent finalization.
- [x] T015 [US1] [TDD] Complete explicit Copilot selection, merged partial-update validation, model precedence and trusted-context routing in `mateclaw-server/src/main/java/vip/mate/agent/AgentService.java` and `mateclaw-server/src/main/java/vip/mate/agent/controller/AgentController.java`; use T003/T005/T013/T014 without widening DSH's path policy, ensure instruction/current-input overflow fails explicitly, use Copilot-aware model capability/pin handling without requiring a native model row, and reject native-only planning/Goals/Team Run/approval replay rather than constructing a native graph; make T010 green.
- [x] T016 [US1] [TDD] Integrate the same Copilot marker, explicit outcome and actual provider/model into `mateclaw-server/src/main/java/vip/mate/channel/web/ChatController.java`, `mateclaw-server/src/main/java/vip/mate/channel/ChannelMessageRouter.java`, `mateclaw-server/src/main/java/vip/mate/agent/AgentService.java` (`ChatResult` and sync error collection), and `mateclaw-server/src/main/java/vip/mate/channel/web/AgentStreamAccumulator.java`; update completion/error/cancel/shutdown saves and payloads to reuse T014, preserve native/DSH persistence, attach reconnect to existing SSE IDs without another prompt, and make T012 controller assertions green.
- [x] T017 [P] [US1] [TDD] Write failing shared selection and mounted editor/wizard tests in `mateclaw-ui/src/utils/__tests__/runtimeSelection.test.ts` and `mateclaw-ui/src/views/__tests__/CopilotRuntimeSelection.test.ts`; assert saved/draft `copilot` survives hydration, partial edits preserve allowed model override, malformed/unknown override keys are rejected, native defaults remain native, and Copilot model controls never silently select a native provider.
- [x] T018 [US1] [TDD] Implement shared selection helpers in `mateclaw-ui/src/utils/runtimeSelection.ts` and update `mateclaw-ui/src/views/Agents.vue`, `mateclaw-ui/src/views/AgentCreateWizard.vue`, `mateclaw-ui/src/types/index.ts`, `mateclaw-ui/src/views/ChatConsole.vue`, and `mateclaw-ui/src/i18n/locales/en-US.ts`/`zh-CN.ts` for Copilot selection, badges/filter, JSON model override validation and provider-specific model controls; make T017 green without clearing existing DSH settings.
- [x] T019 [US1] [TDD] [REVIEW] Validate US1 with `mateclaw-server/src/test/java/vip/mate/agent/AgentServiceCopilotTest.java`, the T011/T012 tests and `mateclaw-ui/src/views/__tests__/CopilotRuntimeSelection.test.ts`; add the two-turn/reopen scenario to `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotConversationIntegrationTest.java`, asserting retained identity/history, exactly one answer and empty exposed tool set, and run existing `mateclaw-server/src/test/java/vip/mate/agent/AgentServiceDshHistoryTest.java` as a regression before human review.

**Checkpoint**: US1 is a text-only MVP, not production release authorization.
Admin-supplied deployment configuration from T006 is sufficient to test it; the
settings UI and readiness endpoint belong to US4. Real-model tests still need
permission and the final release gates.

## Phase 4: User Story 2 - Use tools without bypassing workspace controls (Priority: P1)

**Goal**: Enable only application-governed tools under the employee's authority.
**Independent Test**: Perform an allowed read, denied read-only write, disabled or
out-of-scope call, and approval-required action; inspect actual effects and paired
tool receipts. Repeat with strict member isolation and cross-workspace targets.

### Tests for User Story 2

- [x] T020 [P] [US2] [TDD] Write failing bridge tests in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotToolBridgeTest.java` covering nested schemas/invalid arguments, allowed read, read-only/disabled/unknown-tool denial, duplicate call IDs, symlink/outside-root targets, captured requester/workspace restoration on worker threads, direct-output/result-budget policy and cancellation-before-dispatch with latches; enforce "Nonblank unique provider call ID within execution; duplicates never re-execute" and "Exact application-scoped exposed name".
- [x] T021 [P] [US2] [TDD] Write failing approval-policy tests in `mateclaw-server/src/test/java/vip/mate/agent/graph/executor/ToolExecutionExecutorCopilotApprovalTest.java` for a turn-owned approval-disabled executor: required human approval produces no effect or pending row, normal guard/auto-grant rules remain evaluated, and the unchanged default native executor still creates ordinary approvals.
- [x] T022 [US2] [TDD] [REVIEW] Add an explicit per-executor approval-continuation policy in `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`, defaulting to current behavior; turn T021 green by blocking unsupported human approval before `createPending`, retaining guard/audit/grant semantics, and returning a distinguishable approval-required-blocked result. Do not mark the origin as cron, mutate shared native executor state, use replay mode, or grant blanket approval to evade the decision.
- [x] T023 [US2] [TDD] Implement `CopilotToolBridge` in `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotToolBridge.java` using T004 scoped schemas and a turn-owned T022 executor with normal `isReplay=false`, explicit origin, ToolContext/restored scoped thread context, existing result/timeouts and an admission/cancellation checkpoint; serialize this turn's calls, cache duplicate receipts without re-executing, handle returnDirect without leaking content to provider context, and emit paired call IDs with succeeded/failed/denied/approval_required/cancelled/uncertain status; make T020 green.
- [x] T024 [US2] [TDD] Enable registered custom tools in `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotSdkClient.java`, `CopilotRuntimeService.java`, and `CopilotRuntimeConnection.java` in the same directory using only `ToolSet.addCustom(name)` entries from T023; extend `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotSdkClientTest.java` to reject native tools, MCP and task/agent wildcard exposure, approve a provider request only for a verified registered host invocation still subject to Tool Guard, and block unknown callbacks. Enforce "`supportsApprovals=false` and `supportsSubagents=false`" and `copilot.approval_unsupported` without a pending replay ID.
- [x] T025 [US2] [TDD] Add integration tests in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotToolIsolationIntegrationTest.java` using two members/two workspaces and same-named employees; assert zero unauthorized data/effects, no transmitted content with strict isolation, no host action after terminal, denial of application-level delegation/orchestration that is not supported (even when a bound host tool offers it), and rejection of forged permission/authority configuration with actual invocation counts and artifact checks.
- [x] T026 [US2] [REVIEW] Run T020/T021/T025 plus existing `mateclaw-server/src/test/java/vip/mate/agent/graph/executor/ToolExecutionExecutorWorkspaceGuardContextTest.java`, `ToolExecutionExecutorNonInteractiveApprovalTest.java`, `ToolExecutionExecutorCancellationTest.java`, and `ToolExecutionExecutorReturnDirectTest.java` in that directory; review the real tool-admission path against `specs/001-add-copilot-runtime/contracts/runtime-provider.md` before allowing tools or task content in an opt-in live test.

**Checkpoint**: Tool capability is conditional on both catalog scope and runtime
guard checks. Required human approval is an explicit block, not a hanging approval.

## Phase 5: User Story 3 - Monitor, stop, and recover a turn (Priority: P1)

**Goal**: Expose truthful execution ownership/outcomes, stop SDK and host work, and
recover interrupted persisted conversations without replay.
**Independent Test**: Stop a controlled turn and separately lose its provider,
inspect live and persisted state, then run a later turn. Only that execution is
affected, failed output is not successful, and prior effects do not repeat.

### Tests for User Story 3

- [x] T027 [P] [US3] [TDD] Write failing deterministic lifecycle tests in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotRuntimeLifecycleTest.java` for stop versus completion/dispatch races, repeated cancel/close, late notifications fenced by execution ID, initialize/idle expiry, cleanup beyond caller deadline, and five independent conversations; assert exactly one terminal event, no new action after stop, removal of hooks/timers, and retained cleanup ownership when SDK forceStop exceeds five seconds.
- [x] T028 [P] [US3] [TDD] Write failing restart tests in `mateclaw-server/src/test/java/vip/mate/workspace/conversation/CopilotTurnRecoveryTest.java` using a disposable persisted database; require "reconcile only orphaned `generating` messages owned by `copilot` to `failed` with `copilot.interrupted`", retaining partial/completed history and already committed host artifacts, leaving native/DSH rows unchanged, and issuing zero automatic SDK/tool resume calls.
- [x] T029 [US3] [TDD] [REVIEW] Complete async cancellation, start/idle/stop deadlines, terminal fencing and cleanup ownership in `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotRuntimeConnection.java`, `CopilotSdkClient.java` in that directory and `mateclaw-server/src/main/java/vip/mate/agent/AgentService.java`; integrate existing `mateclaw-server/src/main/java/vip/mate/channel/web/ChatStreamTracker.java` cancellation hooks/stop/recycle, fence queued/current host work before bounded abort/forceStop, never use ordinary default blocking close on event-loop threads, report cleanup overruns without assuming process exit, and make T027 green.
- [x] T030 [US3] [TDD] Capture provider-at-start on active runs in `mateclaw-server/src/main/java/vip/mate/channel/web/ChatStreamTracker.java` and project it from `mateclaw-server/src/main/java/vip/mate/agent/runtime/AgentRuntimeAggregator.java`; extend `mateclaw-server/src/test/java/vip/mate/agent/runtime/AgentRuntimeAggregatorTest.java` and `AgentRuntimeControllerTest.java` in that directory to preserve workspace/admin authorization and audit stop/recycle, deny cross-workspace control/status, and ensure editing employee runtime/config never relabels or interrupts another active run.
- [x] T031 [US3] [TDD] Add Copilot-only interruption reconciliation to `mateclaw-server/src/main/java/vip/mate/workspace/conversation/config/ConversationSchemaMigration.java` using conditional persistence in `mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java`; make T028 green while retaining existing startup stream normalization, never changing completed/native/DSH messages or resuming uncertain external effects.
- [x] T032 [P] [US3] [TDD] Write failing UI/stream tests in `mateclaw-ui/src/components/chat/__tests__/MessageBubble.copilotUsage.test.ts` and `mateclaw-ui/src/composables/__tests__/copilotRuntimeMetadata.test.ts`; cover context-only/missing/measured usage on live completion and history reload, exclude unavailable counters from totals, preserve legacy usage, show failed/stopped rather than completed partial answers, and use run-start provider instead of employee metadata.
- [x] T033 [US3] [TDD] Preserve usageAvailability, outcome, marker ID and actual model/provider through `mateclaw-ui/src/composables/chat/useChat.ts`, `mateclaw-ui/src/types/index.ts`, `mateclaw-ui/src/api/index.ts`, `mateclaw-ui/src/components/chat/MessageBubble.vue`, `ContextUsagePanel.vue` in that directory, `mateclaw-ui/src/views/Agents.vue`, and the `en-US.ts`/`zh-CN.ts` files in `mateclaw-ui/src/i18n/locales/`; make T032 green with "Unavailable" for absent Copilot measurements on live/reload/aggregate displays, keeping existing native/DSH numeric behavior.
- [x] T034 [US3] [TDD] Create opt-in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotRealRuntimeAcceptanceTest.java` and disposable fixtures under `mateclaw-server/src/test/resources/copilot/` gated by `MATECLAW_COPILOT_ACCEPTANCE=true`; require explicit executable/model/secret and disposable DB/root prerequisites, then implement two-turn identity/history, hostile ambient-config/tool suppression, two-member/two-workspace denial, 20 measured member-stop/admin-recycle trials within five seconds, five conversations with one cancelled, specific owned-process loss, restart after partial output/write, quota/auth failure fixtures and next-turn recovery. Record sanitized SDK/CLI/platform pins and real effects; skip without opt-in but fail missing prerequisites when opted in.
- [x] T035 [US3] [REVIEW] Run deterministic lifecycle/recovery/attribution/UI tests and relevant existing `mateclaw-server/src/test/java/vip/mate/channel/web/ChatStreamTrackerCleanupTest.java`, `ChatStreamTrackerEventIdTest.java`, `ChatStreamTrackerOrphanPolicyTest.java`, and `ChatControllerPersistStatusTest.java` in that directory; confirm async cleanup and reconnect invariants in `specs/001-add-copilot-runtime/quickstart.md` before requesting approval for the later real-provider gate, and distinguish implemented T034 scenarios from any live evidence not yet run. Server: 279 tests passed, 7 opt-in acceptance tests skipped; UI: 22 tests passed.

**Checkpoint**: Ordinary completion disposal must not emit cancellation; failures
never become successful because a Flux ended. Live acceptance is T043, not implied
by green mock tests. Failed/uncertain effects require reconciliation, not replay.

## Phase 6: User Story 4 - Configure and diagnose Copilot availability (Priority: P2)

**Goal**: Global admins configure and verify the runtime; users see honest
availability, capabilities and remediation without exposed secrets.
**Independent Test**: Check working/missing executable, missing/invalid auth,
unsupported model, protocol mismatch, unsafe config and non-admin access; verify
and status send no prompts or tools. Save/reload settings through the admin page.

### Tests for User Story 4

- [x] T036 [P] [US4] [TDD] Write failing management/readiness contract tests in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotManagementControllerTest.java` and `CopilotReadinessTest.java` in that directory for GET status, PUT partial config, POST verify, global-admin-only access, unknown-key rejection, token keep/clear/conflict, readiness cache invalidation, no task content/model prompt/tool invocation, and distinct disabled/cli/auth/protocol/model/scope/deadline errors; enforce "`readiness.state`: `disabled`, `unchecked`, `ready`, or `unavailable`" and no secret or masked fragment in responses/logs.
- [x] T037 [P] [US4] [TDD] Write failing page tests in `mateclaw-ui/src/views/Settings/Copilot/__tests__/index.test.ts` for global-admin-only controls, status/save/verify loading/failure states, write-only token-presence display, explicit clear versus preserve, invalid config, translated remediation and disabled approval/delegation; mock the exact management contract, not DSH installer endpoints.
- [x] T038 [US4] [TDD] Implement `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotManagementController.java` and bounded readiness in `CopilotRuntimeConfigService.java`/`CopilotSdkClient.java` in that directory; make T036 green with `/api/v1/admin/copilot/status`, `/config`, `/verify` and existing `R<T>`/global-admin conventions, using config snapshots and protocol-3/auth/model discovery without generation. A field that cannot be checked without a model call remains unverified/unavailable; status/config save never starts a prompt, invalid stored config remains repairable, and readiness closes all probe resources.
- [x] T039 [US4] [TDD] Add typed `copilotApi` methods to `mateclaw-ui/src/api/index.ts`, create `mateclaw-ui/src/views/Settings/Copilot/index.vue`, and register global-admin navigation/route in `mateclaw-ui/src/views/Settings/Layout.vue` and `mateclaw-ui/src/router/index.ts`; make T037 green with allowlisted partial fields, no echoed/stored browser token, no installer/upgrade/remote-server controls, and status invalidation after effective changes.
- [x] T040 [US4] [TDD] Complete the stable `copilot.*` remediation/error/capability vocabulary in `mateclaw-server/src/main/resources/messages.properties`, `messages_en.properties` in that directory, and `mateclaw-ui/src/i18n/locales/en-US.ts`/`zh-CN.ts`; update `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotRuntimeService.java`, `CopilotRuntimeConfigService.java`, and `CopilotManagementController.java` in that directory to redact provider failures and advertise only cancellation/context projection, not simulated reasoning/usage/approvals/delegation. Add redaction assertions to T036 and localized-state assertions to T037.
- [x] T041 [US4] [TDD] [REVIEW] Run T036/T037 and disabled-startup integration in `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotAvailabilityIntegrationTest.java`; assert unchanged native/DSH operation when Copilot is absent, unavailable, enabled or disabled, immutable configuration for already-running turns, rejection of new disabled turns, and no silent runtime/model substitution; review management permissions and secret handling before sign-off.

**Checkpoint**: Admin verify is a non-generative compatibility/readiness check,
not proof that hostile configuration, real tool effects or stop timing pass.
No optional SDK capability is advertised merely because a type exists.

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Validate the complete feature, collect authorized release evidence,
and document safe opt-in/disable behavior.

- [x] T042 Run focused server suites and unchanged native/DSH regressions from `specs/001-add-copilot-runtime/quickstart.md`, adding the new persistence/recovery/controller/channel/executor test selectors explicitly, and run relevant Vitest files plus non-fixing ESLint, precision check and full UI build from `mateclaw-ui/package.json`; inspect actual executed counts, distinguish opt-in skips from passes, and record applicable failures rather than editing unrelated baselines.
- [ ] T043 [REVIEW] Execute `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotRealRuntimeAcceptanceTest.java` only after human approval of the deployment account, test prompts, executable and disposable workspaces; run `specs/001-add-copilot-runtime/quickstart.md` scenarios and verify SC-001 through SC-007, including first configured conversation within five minutes, zero unauthorized effects, all 20 actual cleanup times <=5s, five isolated conversations, real hostile-config suppression and interrupted-write no-replay. Keep runtime disabled for missing/failing required evidence; record macOS arm64 and Linux x64 separately without claiming untested platform support.
- [x] T044 Update `specs/001-add-copilot-runtime/quickstart.md`, `README.md`, and `README_zh.md` with actual supported SDK/CLI/platform evidence, explicit authentication/prerequisites, opt-in configuration, unknown-usage/unsupported-approval limits, the stop/reap limitation, and disable/reassign rollback that preserves history; retain sanitized test records in the implementation handoff rather than committing secrets or test-user content.
- [ ] T045 [REVIEW] Perform final requirement/constitution and focused authorization/persistence/lifecycle code review against `specs/001-add-copilot-runtime/spec.md`, `plan.md`, and `tasks.md`; verify all dependent save/render paths use the same execution marker/outcome, all tests and real-gate gaps are honestly reported, no DSH/native scope drift or unrelated edits occurred, and only then request merge/enablement approval.

**Checkpoint**: Tests, platform evidence, safe rollback and review are complete.
Do not mark unexecuted live acceptance complete or enable a failing deployment.

## Dependencies & Execution Order

### Phase Dependencies

```text
Setup T001-T002
  -> Foundation T003-T009
    -> US1 T010-T019 (text-only MVP)
      -> US2 T020-T026 (governed tools)
        -> US3 T027-T035 (full lifecycle/recovery/monitoring)
          -> US4 T036-T041 (admin diagnostics/configuration UX)
            -> Cross-cutting T042-T045 (release gates)
```

Stories are independently **testable increments**, not artificially independent
implementations: later stories extend the same provider and shared files. US4's
controller/page test preparation can overlap earlier work after T006/T007, but its
final verification depends on integrated lifecycle and observability.

### Exact Task Prerequisites

Every task inherits its previous-phase checkpoint unless explicitly described as
early test preparation. Rows group tasks only when their prerequisites are equal.
Dependencies on a test-only task require red tests to exist, not to pass yet.

| Task | Additional prerequisites in its phase |
|------|--------------------------------------|
| T001 | None |
| T002 | T001 |
| T003, T004, T005, T006 | None; independent foundation lanes |
| T007 | T003, T006 |
| T008 | T003 |
| T009 | T003, T004, T005, T006, T007, T008 |
| T010, T011, T012, T017 | None; independent story test files |
| T013 | T011; T007/T008 reviews approved |
| T014 | T012 |
| T015 | T010, T013, T014 |
| T016 | T015; T014 review approved |
| T018 | T017; server consumer integration waits for T015/T016 |
| T019 | T010-T018 |
| T020, T021 | None |
| T022 | T021 |
| T023 | T020, T022; T022 review approved |
| T024 | T023 |
| T025 | T024 |
| T026 | T020-T025 |
| T027, T028, T032 | None |
| T029 | T027 |
| T030 | T029; T029 review approved |
| T031 | T028 |
| T033 | T030, T032 |
| T034 | T029, T030, T031, T033 |
| T035 | T027-T034 |
| T036, T037 | None |
| T038 | T036 |
| T039 | T037, T038 |
| T040 | T038, T039 |
| T041 | T036-T040 |
| T042 | None beyond all story checkpoints |
| T043 | T042 and live-provider human authorization |
| T044 | T043 evidence or explicit blocked result |
| T045 | T042, T043, T044; blocked acceptance prevents release completion |

T018 is not marked `[P]`: it touches shared locale/API/selection surfaces also
needed by later stories. T031 can run after T028 but must not overlap US1
persistence edits. T034 only creates the opt-in harness; T043 executes it.

### Parallel Opportunities and Per-Story Examples

| Scope | Safe example once phase prerequisites are complete |
|-------|--------------------------------------------------|
| Foundation | T004 scoped-tool extraction and T005 history extraction have distinct production/test files; T006 settings is a third lane. |
| US1 | T010 routing tests, T011 SDK event tests, T012 persistence tests and T017 UI tests can be written concurrently; join before dependent implementations. |
| US2 | T020 bridge tests and T021 executor approval tests use different files; join at T022/T023. |
| US3 | T027 lifecycle, T028 recovery and T032 UI metadata tests are independent; join before T029/T031/T033 respectively. |
| US4 | T036 server management contract tests and T037 settings page tests are independent; T039 integration waits for T038. |

Do not run two agents against `AgentService`, shared runtime records,
`ToolExecutionExecutor`, `api/index.ts` or locale files concurrently. Parallelism
is optional; sequential execution is correct and is the default.

### Requirement and Acceptance Coverage

| Requirements / outcomes | Implementing tasks | Evidence tasks |
|-------------------------|--------------------|----------------|
| FR-001; US1 selection | T015, T018 | T010, T017, T019 |
| FR-002, FR-009; isolation/identity | T003, T004, T007, T015, T023 | T010, T020, T025, T030, T036, T043 |
| FR-003, FR-005; streaming/final outcome | T008, T013-T016, T029 | T011, T012, T019, T027, T035 |
| FR-004; completed bounded history | T005, T015, T016 | T005, T019, T034, T043 |
| FR-006, FR-015; stop/deadlines | T007, T029, T030 | T027, T034, T035, T043 |
| FR-007, FR-008; governed tools | T004, T022-T024 | T020, T021, T025, T026, T043 |
| FR-010, FR-012; configuration/secrets | T006, T038-T040 | T006, T036, T037, T041, T043 |
| FR-011; honest usage/capabilities/ownership | T008, T013, T030, T033, T040 | T011, T030, T032, T036 |
| FR-013; native/DSH coexistence | T006, T013, T015, T041 | T009, T019, T041, T042 |
| FR-014; durable interruption/no replay | T014, T016, T031 | T012, T028, T034, T043 |
| SC-001, SC-002; setup/time and conversation completion | T015-T018, T038-T040 | T019, T034, T043 |
| SC-003; 20 stops <=5s | T029 | T027, T034, T043 |
| SC-004; two workspaces/two members/zero unauthorized effects | T003, T023, T030, T038 | T025, T030, T036, T043 |
| SC-005; no false success/secret disclosure | T008, T013, T016, T029, T040 | T011, T012, T027, T036, T041, T043 |
| SC-006; unchanged native/DSH | T004, T005, T015, T016, T022 | T009, T019, T026, T041, T042 |
| SC-007; five conversations/one stopped | T007, T023, T029 | T027, T034, T043 |

## Implementation Strategy

### MVP First

Complete setup/foundation and US1 for a **text-only, explicitly configured**
demonstration. Empty tools, strict workspace validation and basic cancellation/
startup cleanup are not optional even in the MVP. Full host tools, restart/stop
acceptance, admin UI and production enablement wait for later phases. The three
P1 stories US1-US3 plus the final release gates are the minimum governed runtime
release, not a claim that the US1 demo alone meets the entire specification.

### Incremental Delivery

Finish one reviewed story slice at a time; update this checklist only after its
specified evidence exists. Preserve the existing native/DSH behavior throughout.
For failed SDK/schema compatibility, fail-closed tool isolation, stop timing, or
required live checks, stop and report the blocking evidence instead of weakening
the contract or introducing a new runtime architecture without approval.

### Verification Commands

For each server task, combine the affected test-class names in one runner:

```bash
mvn -pl mateclaw-server -am \
  -Dtest='AffectedTestClass,AnotherAffectedTestClass' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Replace the example selectors with actual classes named by the task, inspect
Surefire reports, and require those tests to execute; the reactor option must not
hide a zero-test feature run. For UI tasks, use one `pnpm exec vitest run` with
the named test file paths. T042 runs established non-fixing lint/build and the
focused shared regression set in [quickstart.md](quickstart.md). Do not install
new tooling; restore dependencies only after a manifest change or missing-tool
failure.

## Superpowers Execution

### Execution Discipline

Enhanced decomposition has been explicitly selected. Markers now carry execution
semantics; the prose assertions, exact paths, prerequisite table and linked
contracts remain part of each task's instructions.

Implementation is a separate user action. No agent or workflow has been launched
by this task file. Use the applicable implementation skills when execution is
requested; keep shared-file tasks sequential and honor the human gates.

### Testable Slices and Checkable Steps

The checklist IDs remain stable tracking units, not instructions to implement
every assertion in one large change. Within each `[TDD]` slice:

1. Select one named assertion from the owning task; add its focused test in the
   exact test file on that task. Use the relevant interface in the table below
   and the linked contract; do not invent SDK APIs.
2. Run the server selector or UI file command in **Verification Commands**.
   Expect the new assertion to fail for the missing behavior, not a missing
   dependency, broken fixture or unrelated baseline. Record the observed failure.
3. Implement only that behavior in the named production file and rerun the same
   selector; require the new assertion and affected existing tests to pass.
4. Refactor only while green, repeat the selector if code changes, and record
   executed counts. Repeat steps 1-4 for the task's remaining assertions.
5. Mark the task complete only after its slice is green and any `[REVIEW]` gate is
   approved. If commits are requested, precisely stage the green logical slice;
   never stage unrelated user work or commit a deliberately red test-only task.

| Red tests | Green implementation / independently verified deliverable |
|-----------|----------------------------------------------------------|
| T010 | T015 selection, model validation and provider routing |
| T011 | T013 provider/event mapping |
| T012 persistence assertions | T014 conditional marker persistence |
| T012 controller assertions | T016 consistent sync/stream/channel finalization |
| T017 | T018 editor/wizard selection round-trip |
| T020 | T023 governed bridge, after T022 executor policy |
| T021 | T022 native-compatible approval policy |
| T027 | T029 stop/deadline/cleanup ownership |
| T028 | T031 scoped crash recovery |
| T032 | T033 truthful usage and provider presentation |
| T036 | T038 management contract, then T040 redaction assertions |
| T037 | T039 settings page, then T040 localized-state assertions |

T003-T008, T019, T024-T025, T030, T034 and T041 contain their own
test/implementation cycle or integration assertions. T034 constructs an opt-in
harness; use deterministic failure fixtures without paid/live generation during
ordinary tests. Only T043 executes authorized real-provider acceptance.

### Interfaces Between Slices

| Producer | Consumer contract |
|----------|-------------------|
| T003 | `RuntimeTurnContext(ChatOrigin origin, Path workspaceRoot, String systemPrompt)` plus compatible session/coordinator overloads; legacy native/DSH constructors retained. |
| T004 | `RuntimeToolSetResolver.resolve(AgentEntity employee): AgentToolSet`; T023 consumes only this scoped catalog. |
| T005 | `RuntimeConversationHistory.enrich(...)` retains the existing DSH enrichment signature and policy; copy the facade's exact types, preserving source compatibility. |
| T006 | `CopilotRuntimeConfigService.resolve()` returns an immutable validated snapshot; T007 and T038 consume that snapshot, not mutable global settings during a turn. |
| T007/T013 | `CopilotRuntimeService` implements existing `type/validate/capabilities/start`; `start(RuntimeSession)` returns the existing connection type. Wrap exact-pin SDK APIs only after T002 compiles them. |
| T008/T014 | Execution ID, assistant marker ID and explicit terminal outcome survive projector, accumulator and `ChatResult`; T016 must not infer success from Flux completion. |
| T022/T023 | Turn-owned executor defaults native behavior unchanged; Copilot uses normal guarded `execute(..., isReplay=false, ..., origin)` with approval continuation disabled. |
| T038 | GET `/api/v1/admin/copilot/status`, PUT `/api/v1/admin/copilot/config`, POST `/api/v1/admin/copilot/verify`; T039 uses the exact linked management contract and existing `R<T>`. |

Where existing generic types/overloads are intentionally retained, inspect their
declaration before editing rather than replacing them with a guessed signature.
The SDK feasibility checkpoint must settle new SDK signatures before consumers.

### Review Gates

| `[REVIEW]` task | Evidence required before approval / blocked consumers |
|-----------------|-------------------------------------------------------|
| T002 | Exact-pin compile/schema/filter fixtures pass without provider startup; blocks foundation if incompatible. |
| T007 | Private environment/home, denied permissions and cleanup tests; blocks T013 provider startup integration. |
| T008 | Explicit failure and absent-usage projection tests; blocks T013 and persistence consumers. |
| T009 | All foundation evidence and both contracts; blocks US1. |
| T014 | Conditional/idempotent finalization tests preserve one row and terminal outcome; blocks T016. |
| T019 | Text-only conversation and native/DSH regressions; blocks US2, not release authorization. |
| T022 | Copilot approval block creates no pending row while native approvals work; blocks T023. |
| T026 | Governed effects, denials, scoped catalog and context restoration; blocks US3 and live tool use. |
| T029 | Async cleanup, admission fencing, retained overrun ownership; blocks T030 and later lifecycle consumers. |
| T035 | Deterministic lifecycle/recovery/UI evidence; blocks US4 completion and later live gate. |
| T041 | Admin authorization, secret handling, disabled/changed settings; blocks final release phase. |
| T043 | **Before** execution: explicit live account/content/process authorization. **After**: actual stop/isolation/concurrency/platform evidence; failures block release, not honest documentation in T044. |
| T045 | Full requirement/constitution, focused code review and release evidence; blocks merge/enablement. |

### Checkpoint Protocol

At each phase boundary, run the relevant checks, state completed and blocked work,
present results and request explicit approval before continuing. T009 and T045
also require focused contract/constitution review. Before T043, obtain separate
authorization for any real-model content transmission and disposable process tests.

## Notes

Task counts: setup 2; foundation 7; US1 10; US2 7; US3 9; US4 6; cross-cutting 4;
**45 total**, all initially unchecked. Parallel candidates: **14** (`T004`, `T005`,
`T006`, `T010`, `T011`, `T012`, `T017`, `T020`, `T021`, `T027`, `T028`, `T032`,
`T036`, `T037`).
Execution markers: **36 `[TDD]`**, **13 `[REVIEW]`**, and
**3 `[SUBAGENT]`** tasks. Markers do not add tasks or count as completed evidence.
Never treat a generated test name, planned scenario or unchecked task as executed
validation evidence.
