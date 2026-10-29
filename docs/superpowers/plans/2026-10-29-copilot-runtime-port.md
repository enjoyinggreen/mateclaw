# Copilot Runtime Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the approved Copilot runtime changes from `../copilotclaw.bak` into
the current repository while preserving current-only work and excluding
machine-local state.

**Architecture:** Port the backup worktree delta from its own HEAD onto the current
branch using the common base, reconciling shared files rather than replacing them.
Keep the provider opt-in and disabled by default; preserve application-owned
identity, history, tool governance, and lifecycle behavior.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Copilot Java SDK
1.0.14-preview.1, Vue 3, TypeScript, Maven, Vitest.

**Spec:** [specs/001-add-copilot-runtime/spec.md](../../../specs/001-add-copilot-runtime/spec.md)

## Global Constraints

- The backup `HEAD` (`e99da0a2`) is the common base; current `HEAD`
  (`2d045b25`) contains an additional current-branch commit that MUST be preserved.
- Inventory the backup worktree against its own `HEAD`, including intentional
  untracked project files; preserve current-only edits and surface conflicts.
  Baseline inventory found 55 tracked modifications and 168 untracked candidates.
- Do not replace whole directories or blindly overwrite shared files.
- Preserve Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, and the managed
  `com.github:copilot-sdk-java:1.0.14-preview.1`; do not upgrade frameworks.
- Copilot MUST remain disabled by default; never silently substitute native or DSH.
- Keep authorization, workspace boundaries, approvals, cancellation, and application
  history authoritative; unsupported Copilot actions fail closed.
- Do not import credentials, local databases, logs, caches, dependency/build output,
  or `pom.xml.local`.
- Do not run real-provider acceptance or send prompts without separate deployment
  owner authorization. Do not claim platform acceptance that has not been run.
- Do not commit, deploy, or enable Copilot as part of this port.

## Review Focus

| Input or failure condition | Required behavior and owning tests |
|---|---|
| Current branch has changes absent from the backup | Preserve them; inventory and resolve file-level overlap in Task 1, then verify in Task 7. |
| Partial employee updates or editor hydration | Preserve Copilot selection and model override without changing native/DSH defaults; test in Tasks 4 and 5. |
| Cancellation races with tool admission | No host callback starts after terminalization; latch-controlled side-effect tests in Task 3. |
| Provider final text repeats streamed deltas | Persist and display one answer with one final outcome; mapper/controller tests in Tasks 3 and 4. |
| Ambient CLI configuration or uncertain external side effects | Prevent ungoverned tools and cross-workspace access; restart must not replay writes; tests in Tasks 3 and 4. |

---

## File Map

| Area | Files and responsibility |
|---|---|
| Dependency boundary | `pom.xml`, `mateclaw-server/pom.xml`: retain the pinned SDK and consume it in the server. |
| Runtime contracts | `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/`: trusted turn context, compatible session factory/coordinator, and usage availability. |
| Provider and policy | `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/`: SDK ownership, event mapping, configuration/readiness, and governed tool bridge. |
| Shared runtime helpers | `RuntimeToolSetResolver.java`, `RuntimeConversationHistory.java`, `dsh/DshConversationHistory.java`: reuse existing tool and bounded-history policies without changing native/DSH behavior. |
| Routing and persistence | `AgentService.java`, `AgentController.java`, `ConversationService.java`, `ConversationSchemaMigration.java`, `ChatController.java`, `ChannelMessageRouter.java`, runtime event/stream classes: one governed Copilot turn and one durable terminal outcome. |
| Server tests | `mateclaw-server/src/test/java/vip/mate/`: Copilot provider, tool, runtime, routing, persistence, recovery, and native/DSH regression coverage. |
| Admin and console | `mateclaw-ui/src/`: Copilot settings/API, employee selection, chat usage and provider attribution, translations, and focused UI tests. |
| Supporting artifacts | `README.md`, `README_zh.md`, `specs/001-add-copilot-runtime/`, `.specify/superpowers.yml`, and approved support files in `.agents/` and `.github/skills/`. Merge with the current spec; do not restore the older backup copy over it. |

## Task 1: Inventory and reconcile the two worktrees

**Files:**
- Review: all modified and intentional untracked project files in `../copilotclaw.bak`
- Preserve: current worktree files, including `.specify/` and `.github/skills/`
- Exclude: machine-local files listed in Global Constraints

**Interfaces:**
- Consumes: common base `e99da0a2`; backup worktree status and diff; current `HEAD` `2d045b25`.
- Produces: a path-by-path disposition for every source delta: port, merge with current, or exclude under an explicit constraint.

- [ ] **Step 1: Inventory source tracked changes** using `git -C ../copilotclaw.bak diff --name-status HEAD` and inspect each changed file against the current version.
- [ ] **Step 2: Inventory intentional untracked project files** using `git -C ../copilotclaw.bak ls-files --others --exclude-standard`; exclude only items covered by the explicit local-artifact rules.
- [ ] **Step 3: Compare target-only changes** against the common base and record every overlap that needs a three-way merge; do not overwrite current files. The current branch has 46 post-base paths; three overlap source edits in `mateclaw-ui/src/api/index.ts` and the English/Chinese locale files, and their changes are in independent hunks.
- [ ] **Step 4: Compare repeated untracked paths**; retain the identical `.github/skills/`, current constitution/spec/checklist, and newer generated install metadata; port backup-only workflow and feature artifacts.
- [ ] **Step 5: Verify scope coverage** by ensuring every source path is marked port, merge, retain-current, or explicitly excluded before changing application files.

**Check:** The reviewed inventory accounts for all source deltas and names all conflicts; `pom.xml.local`, secrets, generated files, data, and logs are excluded.

## Task 2: Port dependencies and shared runtime foundations

**Files:**
- Modify: `pom.xml`, `mateclaw-server/pom.xml`
- Create: `mateclaw-server/src/main/java/vip/mate/agent/runtime/contract/RuntimeTurnContext.java`
- Create: `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeToolSetResolver.java`
- Create: `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeConversationHistory.java`
- Modify: `RuntimeSession.java`, `RuntimeSessionFactory.java`, `AgentRuntimeCoordinator.java`, `RuntimeContextUsage.java`
- Modify: `AgentGraphBuilder.java`, `dsh/DshConversationHistory.java`
- Tests: corresponding runtime contract, resolver, and history test classes under `mateclaw-server/src/test/java/vip/mate/`

**Interfaces:**
- Consumes: current runtime contracts and existing native/DSH filtering and history rules.
- Produces: `RuntimeTurnContext(ChatOrigin origin, Path workspaceRoot, String systemPrompt)`, compatible provider session creation, `RuntimeToolSetResolver.resolve(AgentEntity employee): AgentToolSet`, and shared bounded conversation-history selection.

- [ ] **Step 1: Port the foundation tests** for compatible constructors, trusted-origin requirements, workspace containment, strict member-isolation rejection, existing tool filtering, DSH history behavior, and explicit usage availability.
- [ ] **Step 2: Run the focused tests** and confirm failures are limited to the missing Copilot-compatible foundation behavior.
- [ ] **Step 3: Reconcile and port the shared contract/helpers** without removing existing native/DSH APIs or changing their defaults.
- [ ] **Step 4: Run the focused contract, resolver, history, and DSH regression tests** and confirm all pass.
- [ ] **Step 5: Compile the server reactor** with `mvn -pl mateclaw-server -am -DskipTests compile`.

## Task 3: Port the Copilot provider, configuration, and governed tool boundary

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/agent/runtime/copilot/CopilotSdkClient.java`
- Create: `CopilotRuntimeConnection.java`, `CopilotRuntimeService.java`, `CopilotEventMapper.java`
- Create: `CopilotToolBridge.java`, `CopilotRuntimeConfigService.java`, `CopilotReadinessService.java`, `CopilotManagementController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/tool/guard/WorkspacePathGuard.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/executor/ToolExecutionExecutor.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/system/service/SystemSettingService.java`
- Modify: `mateclaw-server/src/main/resources/application.yml`, `messages.properties`, `messages_en.properties`
- Tests: `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/` and focused settings/guard tests

**Interfaces:**
- Consumes: Task 2 trusted context, existing provider contract, Tool Guard, and managed SDK version.
- Produces: a Copilot provider implementing the existing `type`, `validate`, `capabilities`, and `start` contract; isolated per-turn SDK ownership; immutable validated configuration; and tools executed only through application policy.

- [ ] **Step 1: Port SDK compatibility, settings, lifecycle, and tool-bridge tests**, including disabled defaults, explicit token handling, isolated state, denied unknown/provider-native tools, approval-required blocking, and cancellation races.
- [ ] **Step 2: Run those tests without credentials or provider startup** and confirm expected failures are confined to the provider implementation.
- [ ] **Step 3: Port provider/configuration/tool bridge code** while preserving SDK pinning, fail-closed permissions, cancellation ownership, and secret masking.
- [ ] **Step 4: Run the focused Copilot provider, settings, path-guard, and executor tests**; verify Copilot remains disabled with configuration absent.

## Task 4: Port routing, turn persistence, streaming, and recovery

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/AgentService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/controller/AgentController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/runtime/AgentRuntimeAggregator.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/runtime/RuntimeEventProjector.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/channel/ChannelMessageRouter.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/channel/web/AgentStreamAccumulator.java`, `ChatController.java`, `ChatStreamTracker.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/conversation/config/ConversationSchemaMigration.java`
- Tests: routing, stream, channel, persistence, restart, and recovery test classes under `mateclaw-server/src/test/java/vip/mate/`

**Interfaces:**
- Consumes: Task 3 provider events and Task 2's trusted turn context and explicit usage/outcome metadata.
- Produces: one generating assistant marker per Copilot turn, conditional checkpoint/finalization keyed by execution and marker IDs, and consistent sync, stream, and channel outcomes.

- [ ] **Step 1: Port routing/persistence tests** for create/update, model selection, turn markers, duplicate completion, late events, restart recovery, and no replay after uncertain side effects.
- [ ] **Step 2: Run the focused tests** and confirm they fail against missing Copilot routing or persistence only.
- [ ] **Step 3: Port the application routing and persistence changes**, preserving existing native/DSH save paths and terminal semantics.
- [ ] **Step 4: Run the focused server tests** for Copilot routing, controllers, channels, cancellation, persistence, and recovery.
- [ ] **Step 5: Verify the restart case** uses an isolated disposable H2 database and makes no live SDK call.

## Task 5: Port admin and user-facing console integration

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`, `src/types/index.ts`
- Create: `mateclaw-ui/src/utils/runtimeSelection.ts`
- Create: `mateclaw-ui/src/views/Settings/Copilot/` settings view
- Modify: `mateclaw-ui/src/views/Settings/Layout.vue`, `src/router/index.ts`
- Modify: `mateclaw-ui/src/views/AgentCreateWizard.vue`, `src/views/Agents.vue`, `src/views/ChatConsole.vue`
- Modify: `mateclaw-ui/src/components/chat/ContextUsagePanel.vue`, `MessageBubble.vue`
- Modify: `mateclaw-ui/src/components/live/LiveBoard.vue`, `LiveFocusPanel.vue`, `LivePanel.vue`
- Modify: `mateclaw-ui/src/composables/chat/useChat.ts`, `src/i18n/locales/en-US.ts`, `zh-CN.ts`
- Tests: runtime selection, settings, chat usage, and live attribution tests under `mateclaw-ui/src/**`

**Interfaces:**
- Consumes: Task 4 provider/model/outcome metadata and Task 3 admin management API.
- Produces: persisted Copilot employee selection, settings/readiness UI, honest unavailable-usage display, and attribution to the runtime that actually handled the turn.

- [x] **Step 1: Port utility and component tests** for selection persistence, partial edits, readiness, unavailable usage, and runtime attribution.
- [x] **Step 2: Run focused Vitest selectors** and confirm failures match missing Copilot UI behavior.
- [x] **Step 3: Port API, settings, employee editor/wizard, chat/live display, and English/Chinese translations** without changing native/DSH selection defaults.
- [x] **Step 4: Run focused Copilot Vitest tests, Vue type-check, and the production UI build.**

## Task 6: Port supporting specifications, docs, and project guidance

**Files:**
- Modify: `README.md`, `README_zh.md`
- Merge: `specs/001-add-copilot-runtime/plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`, `tasks.md`, and `progress.yml` from the backup
- Preserve: the current `spec.md`, its checklist, and `.specify/feature.json`
- Port: `.specify/superpowers.yml`, `docs/superpowers/plans/2026-10-28-copilot-restart-acceptance.md`, and intentional project guidance/skills from `.agents/` and `.github/skills/`
- Preserve: the current `.specify/memory/constitution.md` and newer generated install metadata; shared `.github/skills/` files are identical.

**Interfaces:**
- Consumes: implementation behavior and test evidence from Tasks 2–5.
- Produces: documentation and feature artifacts that accurately describe this implementation and clearly distinguish deterministic tests from unrun live-provider acceptance.

- [x] **Step 1: Reconcile each backup feature artifact** with the current spec; do not replace the current spec/checklist or constitution with the backup version.
- [x] **Step 2: Port and reconcile the README, Chinese README, support plan, and intentional guidance files**, preserving current-only additions.
- [x] **Step 3: Verify documentation claims** against tests actually run; describe live-provider and platform acceptance as pending unless separately authorized and completed.

## Task 7: Validate the integrated result and close the inventory

**Files:**
- Verify all changes from Tasks 1–6; no additional files unless a directly related test exposes a defect.

**Interfaces:**
- Consumes: completed source inventory and migrated implementation.
- Produces: test/build evidence, an updated path disposition, and a reviewable diff with no silently dropped source changes.

- [x] **Step 1: Run the focused server suite** using the selector from `specs/001-add-copilot-runtime/quickstart.md`, with the live-provider opt-in unset.
- [x] **Step 2: Run the relevant UI tests, non-fixing lint, precision check, type-check, and build** using the repository's installed package manager; do not use scripts that rewrite unrelated files.
- [x] **Step 3: Run the complete server test suite** with the repository's required environment overrides; distinguish skipped live acceptance from passing tests.
- [x] **Step 4: Run `git diff --check` and inspect the complete diff**, confirming no secrets/local state were added and every source path has a disposition.
- [x] **Step 5: Keep Copilot disabled** and report live-provider acceptance, platform coverage, or the five-second stop goal as unverified unless separately authorized evidence exists.

## Spec Coverage

| Spec area | Owning tasks |
|---|---|
| Runtime selection, identity, and conversation history (US1, FR-001–005) | Tasks 2, 4, 5 |
| Governed tools and workspace isolation (US2, FR-007–009) | Tasks 2, 3, 4 |
| Monitoring, cancellation, and recovery (US3, FR-005–006, FR-014–015) | Tasks 3, 4, 5 |
| Configuration and readiness (US4, FR-010–013) | Tasks 3, 5 |
| Complete migration, preserving current work, and excluding local state (US5, FR-016–018, SC-008) | Tasks 1, 6, 7 |
| Measurable outcomes and release gates (SC-001–008) | Tasks 4–7 |

## Self-Review

- **Spec coverage:** All five user stories and FR-001–018 map to tasks above.
- **Step scan:** Each task step is an inventory, focused test, implementation/merge,
  or verification action with an observable result.
- **Type consistency:** Shared types are introduced in Task 2 before provider,
  routing, and UI consumers; the provider contract is reused rather than redefined.
- **Review focus:** Each listed failure condition has an owning test/task.
- **Proportion:** This plan migrates an existing implementation; it does not
  restate implementation bodies from the backup task list.
