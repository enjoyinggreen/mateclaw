# Copilot Runtime Validation Guide

This guide covers the implemented, opt-in runtime and its release gates. The Java
integration is pinned to `com.github:copilot-sdk-java:1.0.14-preview.1`. The
deterministic server/UI checks ran on macOS arm64 with Java 21.0.9; no real CLI,
credential, or model was used. Therefore no CLI version or live-provider platform
is currently certified, and macOS arm64/Linux x64 acceptance remains outstanding.

The current focused gate ran 293 server tests with 0 failures; the 7
`CopilotRealRuntimeAcceptanceTest` cases were skipped because
`MATECLAW_COPILOT_ACCEPTANCE` was unset. A clean full server run passed 6,888
tests with 0 failures, 0 errors, and 69 skips. It ran with `DOCKER_HOST` unset
because the DSH supervisor fixture tests
its fake daemon-context selection. Copilot and adjacent UI regression tests
passed 82/82; the Snowflake precision check, Vue type-check, and full UI build
passed. Full-source ESLint still has three
existing errors in `ToolResultChart.test.ts:16` and `useAgentRunGroups.ts:185`;
those unrelated baselines were not changed. See `tasks.md` for the recorded gate
details. T043 live acceptance requires separate deployment-owner authorization
and has not been run.

See [runtime-provider.md](contracts/runtime-provider.md),
[management-api.md](contracts/management-api.md), and
[data-model.md](data-model.md) for exact expected behavior.

## Prerequisites

- Repository root as current directory; Java 21 and Maven available.
- UI dependencies restored with the repository's package manager when needed.
  The repository contains `mateclaw-ui/pnpm-lock.yaml`.
- A deployment-admin-provided Copilot CLI executable, explicit GitHub credential,
  and required entitlement. No automatic install or ambient CLI login is used.
- Pinned Java SDK `1.0.14-preview.1`; the executable must negotiate protocol 3.
  No CLI version is currently certified, and semantic-version compatibility must
  not be inferred from its executable name.
- A disposable application database and two test workspaces with distinct files,
  users, employee identities, and conversations. Never use production fixtures.
- Deployment owner approval before any real-model test sends task content.
- Copilot disabled by default; native and DSH regression fixtures remain available.

## 1. Build and dependency compatibility

The server consumes the SDK version already pinned by root dependency management.
Verify compilation and dependency alignment with:

```bash
mvn -pl mateclaw-server -am -DskipTests compile
mvn -pl mateclaw-server -am dependency:tree \
  -Dincludes=com.github:copilot-sdk-java,com.fasterxml.jackson.core:jackson-databind
```

Expected: Java 21 compilation passes against the selected exact SDK artifact and
the application retains its supported dependency alignment. Do not silently
upgrade framework versions to make the SDK compile. Report any conflict.

## 2. Deterministic contract and regression tests

Run the implemented Copilot contracts and the native/DSH persistence, recovery,
controller, channel, and executor regressions:

```bash
mvn -pl mateclaw-server -am \
  -Dtest='Copilot*Test,Runtime*Test,AgentRuntime*Test,AgentService*Test,CopilotTurnPersistenceTest,CopilotTurnRecoveryTest,ChatControllerCopilotTest,ChannelMessageRouterExecutionMetadataTest,AgentStreamAccumulatorStructuredResultTest,ToolExecutionExecutorCopilotApprovalTest,DshConversationHistoryTest,DshRuntimeServiceTest,DshRuntimeTeardownTest,ToolExecutionExecutorWorkspaceGuardContextTest,SystemSettingServiceCatalogTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Inspect Surefire counts rather than relying on a successful Maven exit alone.
Real-provider acceptance classes are opt-in and skipped unless their separate
authorization and explicit opt-in environment are present. Report these as
skips, not passes. Native/DSH selection, history, lifecycle, context, and secret
handling must remain unchanged.

Run the relevant UI tests and established checks:

```bash
cd mateclaw-ui
pnpm exec vitest run \
  src/components/chat/__tests__/MessageBubble.copilotUsage.test.ts \
  src/components/chat/__tests__/MessageBubble.structuredHistory.test.ts \
  src/components/live/__tests__/LiveBoard.providerAttribution.test.ts \
  src/composables/__tests__/copilotRuntimeMetadata.test.ts \
  src/composables/chat/__tests__/messageMetadata.test.ts \
  src/utils/__tests__/runtimeSelection.test.ts \
  src/views/Settings/Copilot/__tests__/index.test.ts \
  src/views/__tests__/CopilotRuntimeSelection.test.ts \
  src/views/__tests__/chatConsoleRuntimeReadiness.test.ts \
  src/views/__tests__/chatConsoleModelSeed.test.ts \
  src/views/__tests__/chatConsoleRouteHydration.test.ts
pnpm exec eslint src
pnpm run lint:precision
pnpm run build
```

Use non-fixing ESLint for validation rather than `pnpm lint`, whose existing
script rewrites files. The latest full-source lint run reported three existing
errors in `ToolResultChart.test.ts:16` and `useAgentRunGroups.ts:185`; retain
those failures in the handoff and do not change unrelated baselines. Broaden
tests only if shared changes warrant it.

## 3. Start with Copilot disabled

Run in the existing development setup using the disposable database:

```bash
mvn -pl mateclaw-server spring-boot:run
```

Expected: server starts without Copilot CLI/configuration, native employees work,
and DSH behavior is unchanged. Readiness reports disabled. Missing Copilot must
not become a startup dependency.

If running the UI separately:

```bash
cd mateclaw-ui
pnpm run dev -- --host 127.0.0.1
```

## 4. Configure and verify without a model prompt

Use the global-admin **Settings > Copilot** page to set the executable, explicit
default model, write-only token, and enabled flag. The page uses
`PUT /api/v1/admin/copilot/config` and
`POST /api/v1/admin/copilot/verify`. Do not put credentials in command history,
committed files, screenshots, logs, or acceptance reports.

The runtime is disabled by default. Environment configuration is also available
for deployment-managed secrets and opt-in settings:

| Variable | Default | Purpose |
|---|---|---|
| `MATECLAW_COPILOT_ENABLED` | `false` | Enable the provider only after required release evidence passes. |
| `MATECLAW_COPILOT_CLI_PATH` | empty | Absolute path to the admin-provided executable. |
| `MATECLAW_COPILOT_MODEL_NAME` | empty | Exact model ID to verify and use by default. |
| `MATECLAW_COPILOT_GITHUB_TOKEN` | empty | Explicit credential; inject through the deployment secret manager. |
| `MATECLAW_COPILOT_STATE_ROOT` | `${java.io.tmpdir}/mateclaw/copilot` | Parent for private per-turn state. |
| `MATECLAW_COPILOT_INITIALIZE_TIMEOUT_MS` | `30000` | Startup deadline. |
| `MATECLAW_COPILOT_IDLE_TIMEOUT_MS` | `180000` | No-progress deadline. |
| `MATECLAW_COPILOT_STOP_TIMEOUT_MS` | `5000` | Caller cleanup wait; not a hard OS process-reap guarantee. |

Expected:

- Configuration survives reload; token reads expose only `tokenConfigured`.
- Normal edits preserve the token; explicit clear removes it.
- Verify checks handshake, access, model, and isolated configuration without
  sending conversation content.
- Missing executable, invalid token/model, incompatible CLI, or unsafe ambient
  configuration reports unavailable with an actionable sanitized code.
- Non-admin requests are rejected and create no configuration change.
- Record SDK artifact version, CLI version, handshake outcome, platform, and
  timestamp in sanitized acceptance evidence. Passing on one CLI does not imply
  every CLI version is supported.

## 5. Employee selection, identity, and history

Create a Copilot employee with distinct instructions and an allowed read-only
tool. Save it, reopen the editor and wizard draft, then exchange two messages:
first establish a harmless unique fact, then ask for it in a follow-up.

Expected:

- `runtimeType=copilot` persists; badges/model selection do not fall back to native.
- Output streams through the existing conversation view, appears once, and retains
  the employee's instructions.
- Follow-up receives only permitted completed history with no current-message
  duplication. Reopen the conversation to confirm retained output.
- An invalid explicit model fails rather than falling back.
- Missing provider usage is "Unavailable", including after reload.
- Reasoning and provider usage are not simulated; approval continuation and
  delegation are unsupported and remain blocked.
- A plain text request sent through an existing synchronous/channel chat path
  follows the same runtime and finalization semantics.

## 6. Governed tools and isolation

Test an allowed workspace read, a disabled tool, a read-only write attempt, an
outside-workspace path (including a symlink), and a request requiring approval.
Inspect actual files/effects, not only tool messages.

Expected: allowed work succeeds; every prohibited request has zero unauthorized
effect. Approval-required work is explicitly blocked without a pending native
replay. Unknown/provider-native tools and delegated agents cannot execute.

Repeat with two users across two workspaces. Put intentionally conflicting
configuration, memory, MCP definitions, and agent files in the surrounding CLI
environment as disposable fixtures.

Expected: fresh isolated provider state prevents ambient tool/server startup and
cross-session context. Only application-registered tools are exposed. A fixture
that defeats containment fails readiness and prevents transmission of task content.
Strict member file isolation rejects Copilot startup before sending content.

## 7. Cancellation and five-conversation acceptance

The opt-in test class is an implementation deliverable:

```bash
MATECLAW_COPILOT_ACCEPTANCE=true \
  mvn -pl mateclaw-server -am \
  -Dtest=CopilotRealRuntimeAcceptanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The class is skipped unless the opt-in flag is exactly `true`. When enabled, it
requires `MATECLAW_COPILOT_ACCEPTANCE_CLI`, `..._CLI_VERSION`, `..._MODEL`, `..._TOKEN`,
`..._INVALID_TOKEN`, `..._QUOTA_MODEL`, `..._ROOT`, and `..._DB_URL`.
Provide the CLI/model/credentials through the deployment-approved secret
mechanism, never inline command arguments. The invalid token and quota model are
deliberate failure fixtures. The root must be a newly created disposable
directory containing `.mateclaw-copilot-disposable`; the database URL must be a
new H2 file database inside that root. The harness writes measured stop
durations, terminal outcomes, and sanitized SDK/CLI/platform/model metadata to
the evidence database; prompts, secrets, and workspace fixtures stay out of
reports.

The harness exercises real-provider turns with explicitly replayed history,
hostile project-config suppression, cross-workspace file denial, five concurrent
sessions with one cancelled, twenty measured member-stop/admin-recycle trials,
owned CLI process loss and next-turn recovery, plus authentication/quota failure
recovery. These are opt-in scenarios, not results from ordinary test runs.
Application-owned history and restart/persisted-marker recovery remain covered
deterministically by `CopilotConversationIntegrationTest` and
`CopilotTurnRecoveryTest`; platform-specific stop evidence must still be run and
reported separately for macOS arm64 and Linux x64.

Required evidence:

1. Twenty controlled long-running turns stopped using member stop and admin
   recycle. Each reaches a non-active final state within five seconds under the
   acceptance policy; owned resources exit and no new action begins afterward.
   The current harness waits for tracked-run finalization and private execution
   directory removal after SDK force-stop; it does not independently inspect OS
   process handles.
2. Five simultaneous independent conversations. Stop one; the other four complete
   with their own context and output.
3. Cancellation racing final text/tool dispatch, repeated stop, late completion,
   process loss, revoked authentication, and quota failure never produce two final
   outcomes or false success.
4. A startup or stop timeout releases owned resources. Unrelated processes and
   other conversations remain alive.

Report elapsed stop times and pass counts, not only a green test label. macOS
arm64 and Linux x64 require separate evidence before either is marked verified.

The pinned SDK's `forceStop()` immediately requests process kill but may wait up
to ten seconds for reap; graceful `stop()` can take longer. The five-second target
is therefore an empirical deployment release gate, not a verified guarantee.
Host callback entry is fenced against terminal publication: stopping interrupts an
active callback and waits for it to settle before publishing the final outcome. A
callback that ignores interruption can therefore delay terminalization; the controlled
stop trials must include this behavior and fail if the five-second target is exceeded.
No live stop trials have been run. Timing out the caller does not prove resources
exited: retain cleanup ownership, record the overrun, fail the trial, and keep the
runtime disabled if any required trial fails. A stricter guaranteed stop bound
would require a reviewed host-owned process design.

Deterministic server tests also pin the async stream lifecycle and reconnect
contract: active runs survive stale cleanup, a brief subscriber disconnect can
reattach within the orphan grace period, and an expired orphan is emergency-saved
before its claimed run state is disposed. Cleanup and late emitter callbacks are
generation-fenced so they cannot remove or close a replacement run. Reconnect
replays the continuation state after segment completion, while persisted
`awaiting_approval` status keeps queued input from draining prematurely. These
invariants are covered by `ChatStreamTrackerCleanupTest`,
`ChatStreamTrackerOrphanPolicyTest`, `ChatStreamTrackerEventIdTest`, and
`ChatControllerPersistStatusTest`; they do not substitute for the opt-in
provider lifecycle evidence above.

## 8. Restart, uncertainty, and rollback

In the disposable environment, start a Copilot turn, capture partial output or a
controlled host write, then terminate the specific test server/provider process.
Restart the application against the same test database.

Expected: the in-progress assistant row becomes failed with `copilot.interrupted`,
saved partial content and completed history remain, the UI is not stuck generating,
and the write is not replayed. A later turn can inspect the existing artifact
before deciding on further action.

**Implemented app-backed scenario:** `CopilotRealRuntimeAcceptanceTest` now
persists the user turn, generating marker, and streamed partial output through
`ConversationService`; a scoped test-only tool writes one controlled artifact,
then the harness terminates only the owned CLI child before turn finalization.
It closes and reopens `MateClawApplication` against a separate H2 file database
inside the disposable root and checks the failed `copilot.interrupted` marker,
retained history/artifact, no automatic SDK start or write replay, and a later
read-only turn that can inspect the artifact. `CopilotTurnRecoveryTest` also
covers the service-backed persistence/restart path without provider credentials.

The opt-in scenario remains skipped by default and has **not** been run with a
real provider; its presence is implementation coverage, not live acceptance
evidence. Only separately authorized T043 execution can produce that evidence.
The current deterministic build/test host was macOS arm64 with Java 21.0.9
Corretto; this verifies compilation and fixture tests only, not CLI compatibility
or platform support. Linux x64 has no acceptance evidence.

Disable Copilot and reassign the employee to its previous runtime. Expected:
new Copilot turns are rejected, native/DSH continue operating, and all prior
conversation history remains readable. No schema rollback, provider-state merge,
or deletion of completed messages is required.

## Release evidence

Do not mark the feature ready solely because mock tests pass. Retain sanitized
contract-test counts, dependency compatibility, SDK/CLI/platform pins, actual tool
effects, interruption evidence, all 20 stop timings, and five-conversation results.
State unavailable checks explicitly; keep the runtime disabled when required
governance or lifecycle evidence is missing.
