# T034 Copilot Restart Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the missing opt-in application restart-after-governed-write scenario to the Copilot runtime acceptance harness.

**Architecture:** Preserve application-level execution fencing while normalizing H2's JSON-string metadata representation in Copilot checkpoint/finalization comparisons. Extend deterministic recovery coverage to persist a user turn, assistant marker, and partial checkpoint through `ConversationService`; extend the existing opt-in acceptance harness to run a controlled host write, lose only its owned CLI process before finalization, and restart `MateClawApplication` against the same disposable H2 database. The live scenario remains gated and is not run without separate authorization.

**Tech Stack:** Java 21, Spring Boot, JUnit Jupiter, H2, Copilot Java SDK `1.0.14-preview.1`.

**Spec:** `specs/001-add-copilot-runtime/spec.md`; task T034 in `specs/001-add-copilot-runtime/tasks.md`.

## Global Constraints

- Java 21; preserve the pinned Copilot SDK `1.0.14-preview.1`.
- Copilot remains disabled by default; real-provider acceptance is opt-in only when `MATECLAW_COPILOT_ACCEPTANCE=true`.
- The opted-in harness requires a newly created disposable root marked `.mateclaw-copilot-disposable` and a new H2 file database inside it.
- T034 implements the harness; only T043 executes authorized real-provider acceptance.
- Do not record prompts, secrets, or workspace fixture contents in acceptance evidence.

## Review Focus

- **Persistence boundary:** Partial text and user history must be committed through `ConversationService` before restart; the marker must be failed with `copilot.interrupted` afterward.
- **External write uncertainty:** A completed host write must remain on disk and must not be replayed after restart; assert the actual file and exact write count.
- **Process ownership:** Terminate only the child CLI PID published by the existing wrapper, never an unrelated process or the test JVM.
- **Disposable storage:** The app-recovery H2 database must be new and inside the disposable acceptance root, distinct from the evidence database.
- **Default safety:** Without explicit opt-in, no provider starts; when opted in, absent prerequisites fail setup rather than silently skipping.

---

### Task 1: Verify service-backed turn recovery deterministically

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/workspace/conversation/CopilotTurnPersistenceTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/workspace/conversation/CopilotTurnRecoveryTest.java`

**Interfaces:**
- Consumes: `ConversationService.saveMessage(String, String, String)`, `beginCopilotTurn(String, String, String, String)`, and `checkpointCopilotTurn(String, Long, String, String, String, String, String)`.
- Produces: H2-safe checkpoint/finalization ownership checks and an app-backed deterministic assertion that service-persisted partial output and an already-written artifact survive restart, while the orphaned marker becomes failed and no SDK session starts.

- [ ] Add failing persistence tests for rejecting a checkpoint from the wrong execution and recognizing H2 JSON-string metadata during finalization; keep assertions on the actual marker update and result.
- [ ] Normalize loaded metadata before checking execution ownership; have checkpoint and finalization validate the persisted execution ID before mutation and retain atomic marker-ID, conversation, role, and generating-status predicates.
- [ ] Add a focused recovery case that inserts only the conversation row, persists its user message and generating marker through `ConversationService`, checkpoints partial output, and writes a controlled artifact before closing the first app context.
- [ ] Restart `MateClawApplication` with the same H2 URL; assert the user and partial assistant history remain, the assistant marker is `failed` with `runtimeErrorCode=copilot.interrupted`, the artifact contents are unchanged, and the restarted `CopilotSdkClient.startAsync` is never called.
- [ ] Run `mvn -pl mateclaw-server -am '-Dtest=CopilotTurnPersistenceTest,CopilotTurnRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false test`; expect all persistence and restart tests to pass without provider credentials.

### Task 2: Add the opt-in app-backed process-loss scenario

**Files:**
- Modify: `mateclaw-server/src/test/java/vip/mate/agent/runtime/copilot/CopilotRealRuntimeAcceptanceTest.java`
- Modify: `specs/001-add-copilot-runtime/quickstart.md`

**Interfaces:**
- Consumes: Task 1's H2-safe `ConversationService` checkpointing; the existing `runtime(...)`, `session(...)`, `createOwnedCliWrapper(...)`, and `validateDatabaseUrl(...)` helpers; the `ConversationService` turn persistence methods; the existing `CopilotTurnRecoveryTest.RuntimeFixture` pattern; `ToolExecutionExecutor`, `AgentToolSet`, and scoped `ToolCallback` patterns from Copilot tool tests.
- Produces: `restartAfterGovernedWritePreservesPartialTurnWithoutReplay`, an opt-in acceptance test that persists its own app turn, loses its owned CLI before finalization, and verifies recovery on application restart.

- [ ] Add the gated scenario with a unique H2 application database under `caseRoot`, separate from `evidenceDb`; start a non-web `MateClawApplication` context with a mocked `CopilotSdkClient`, and create the conversation, user message, and generating marker through application services.
- [ ] Register only a test-scoped `write_file` callback for the interrupted turn; validate its target with `WorkspacePathGuard`, checkpoint streamed assistant deltas, and block the callback after the controlled write so the turn cannot finalize before process loss.
- [ ] Start the provider through the existing owned-CLI wrapper, wait for persisted partial output and the write, terminate only the recorded child PID, await terminal cleanup, and close the first application context without calling `finalizeCopilotTurn`.
- [ ] Reopen the application on the same database and assert the marker is failed with `copilot.interrupted`, partial text and user history remain, the artifact remains byte-for-byte unchanged, the write count is one, and startup makes no SDK start call. Run a later real-provider turn with only a scoped `read_file` callback to inspect the artifact without writing it again.
- [ ] Update quickstart section 8 to describe the implemented app-backed harness scenario and retain the explicit distinction that it is not live-provider evidence until T043 is separately authorized and run.
- [ ] Run `mvn -pl mateclaw-server -am '-Dtest=CopilotRealRuntimeAcceptanceTest,CopilotTurnPersistenceTest,CopilotTurnRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false test` with opt-in unset; expect the live class to be skipped and deterministic tests to pass. Do not set the opt-in variable or access real credentials. Run `git diff --check`.

**Execution note:** This task adds test coverage and documentation, not a production behavior change. The live test cannot be run red/green without the separately authorized provider; do not substitute a real credential or bypass the opt-in gate. Compile and default-skip behavior are the permitted validation for that test.
