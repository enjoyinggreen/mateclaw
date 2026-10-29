# Feature Specification: Copilot Runtime

**Feature Branch**: `feature/copilot_runtime`
**Created**: 2026-10-29
**Status**: Draft
**Input**: User requests bringing all Copilot runtime changes from `../copilotclaw.bak` into the current repository while preserving current-folder-only work.

Add GitHub Copilot as an optional execution engine for MateClaw digital employees.
Users should retain the familiar conversation, tool governance, cancellation, and
runtime-monitoring experience when choosing Copilot instead of DSH. Existing
employees must continue working without configuration changes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Choose Copilot and have a conversation (Priority: P1)

An authorized employee manager selects Copilot for an employee. A workspace member
then sends a message and receives a progressively displayed answer in the existing
conversation experience, with the employee's identity and permitted context intact.

**Why this priority**: Selecting Copilot and completing a turn is the minimum useful
outcome; no separate chat application should be needed.

**Independent Test**: Configure one employee for Copilot, exchange two related
messages, reopen the conversation, and confirm the answer and history remain visible.

**Acceptance Scenarios**:

1. **Given** a valid Copilot configuration and authorized manager, **When** the
   manager saves an employee with Copilot selected, **Then** the selection persists
   and subsequent turns use Copilot without changing the employee's identity.
2. **Given** a Copilot employee, **When** a member sends a message, **Then** the
   existing conversation progressively displays the answer and records one completed
   turn; any available reasoning is presented separately from the answer.
3. **Given** a completed exchange, **When** the member sends a follow-up that refers
   to it, **Then** the employee receives permitted earlier conversation context,
   without duplicating the current message or including other conversations.
4. **Given** existing native and DSH employees, **When** Copilot is made available
   but not selected for them, **Then** their selection and conversation behavior
   remain unchanged, including when Copilot is not configured.

---

### User Story 2 - Use tools without bypassing workspace controls (Priority: P1)

A member asks a Copilot employee to perform work using its allowed tools. The same
workspace permissions, read-only restrictions, approval decisions, and visible tool
activity apply as for other runtimes.

**Why this priority**: A useful external runtime must not gain greater authority
than the employee or expose another member's data.

**Independent Test**: Exercise an allowed read, a denied write, and an action
requiring approval; verify actual effects as well as the displayed activity.

**Acceptance Scenarios**:

1. **Given** an allowed tool and permitted workspace target, **When** Copilot requests
   the action, **Then** it executes with the employee's authority and its outcome
   appears with the corresponding tool activity.
2. **Given** a disabled tool, read-only restriction, or target outside the permitted
   scope, **When** Copilot requests the action, **Then** no prohibited effect occurs
   and the denial is visible.
3. **Given** an action requiring approval, **When** Copilot requests it, **Then** it
   cannot execute before approval; rejection prevents execution. If approval
   continuation is unsupported, the action is explicitly blocked, not auto-approved.
4. **Given** strict member file isolation is enabled, **When** a Copilot turn is
   attempted without an enforceable member-scoped execution boundary, **Then** the
   turn is rejected before any task content is sent or tools execute.

---

### User Story 3 - Monitor, stop, and recover a turn (Priority: P1)

A member can distinguish working, completed, failed, and cancelled work, and stop an
unwanted turn. Administrators can identify Copilot-owned activity in the runtime
console and recycle stuck execution using existing lifecycle controls.

**Why this priority**: External execution must remain observable and controllable,
especially when the provider fails or work has side effects.

**Independent Test**: Cancel a controlled long-running turn and separately interrupt
the provider connection; inspect conversation status, runtime status, and a later turn.

**Acceptance Scenarios**:

1. **Given** a running Copilot turn, **When** the member cancels it, **Then** further
   work stops, the turn becomes cancelled, and late responses cannot change it to
   completed or trigger new actions.
2. **Given** an active turn whose provider disconnects or times out, **When** progress
   cannot continue, **Then** it becomes failed with a useful sanitized explanation;
   partial output is not presented as a successful completed answer.
3. **Given** a running turn, **When** an authorized administrator inspects and
   force-recycles it, **Then** the console identifies Copilot as its owner and the
   application releases that execution's resources without interrupting other turns.
4. **Given** a cancelled, failed, or interrupted turn, **When** the member starts a
   later turn, **Then** completed history remains available and uncertain external
   actions are not automatically repeated.

---

### User Story 4 - Configure and diagnose Copilot availability (Priority: P2)

An administrator configures the Copilot runtime and checks its readiness before
assigning it to employees. Members see a clear explanation when Copilot cannot
run, rather than a hung request or silent substitution of another runtime.

**Why this priority**: Administrators need a supported way to enable the runtime
and distinguish configuration problems from conversation failures.

**Independent Test**: Check readiness with a working configuration, missing
authentication, an unavailable provider, and an unsupported requested model.

**Acceptance Scenarios**:

1. **Given** valid configuration and provider access, **When** an administrator
   checks readiness, **Then** Copilot is reported as ready with its supported
   capabilities and configured model selection.
2. **Given** missing prerequisites, invalid settings, unavailable authentication,
   or an unsupported requested model, **When** readiness or turn startup is checked,
   **Then** a specific remediation-oriented error is shown without exposing secrets,
   and no turn is reported as successful.
3. **Given** the provider supplies usage or delegated-work progress, **When** a turn
   runs, **Then** those details appear in existing runtime views; unavailable
   information is explicitly identified rather than invented.
4. **Given** an unauthorized user, **When** they attempt to change configuration,
   inspect another workspace's execution, or cancel its turn, **Then** access is denied.

### User Story 5 - Bring the runtime changes into the current repository (Priority: P1)

As a maintainer, I need the approved Copilot runtime changes from the backup workspace
in the current repository without losing current-only work, so the feature can be
continued and reviewed in the intended working copy.

**Why this priority**: The runtime implementation is the purpose of this transfer;
losing target-only work or omitting intentional feature files would make it unsafe
to continue.

**Independent Test**: Compare the approved source-change inventory with the resulting
current repository and verify both source and target-only changes.

**Acceptance Scenarios**:

1. **Given** the approved Copilot runtime changes in the backup workspace, **When**
   they are integrated into the current repository, **Then** every intentional
   feature source, test, configuration, specification, and documentation change is
   accounted for in the current repository.
2. **Given** changes that exist only in the current repository, **When** the runtime
   changes are integrated, **Then** those changes remain intact; conflicts are
   surfaced rather than silently overwritten.
3. **Given** machine-local credentials or state, **When** the changes are integrated,
   **Then** credentials, local databases, logs, caches, generated build output, and
   local overrides are not imported.

### Edge Cases

- Missing, expired, or revoked authentication prevents startup or produces an explicit
  failed turn; it never exposes credentials or disables other runtimes.
- An unavailable working directory, invalid model, or unsupported mandatory capability
  fails validation rather than silently selecting a different directory, model, or runtime.
- Long history is bounded by the existing conversation policy; the current request
  remains intact, and omitted older history cannot expand access to other data.
- Concurrent conversations, including identical employee names in different workspaces,
  do not share context, files, cancellation state, or execution authority.
- Cancellation racing with completion, repeated stop requests, and late or duplicate
  notifications produce one final outcome and no post-terminal tool execution.
- A tool fails after possibly causing an external effect: expose the uncertainty and
  require reconciliation or an idempotent retry, not blind replay.
- The application restarts during a turn: preserve completed history, expose the
  interrupted turn as non-successful, and do not silently resume external actions.
- Provider quota limits, connection loss, and unresponsive execution terminate within
  the configured lifecycle deadlines and leave subsequent turns usable.
- Provider-native file, shell, integration, or delegation capabilities must not bypass
  MateClaw controls; any capability that cannot be governed is disabled or rejected.

## Open Questions

| # | Question | Status | Resolution |
|---|----------|--------|------------|
| Q1 | What does "Coplit" identify? | Resolved | Interpret as GitHub Copilot; the user chose defaults after this interpretation was stated. |
| Q2 | How much DSH parity is required? | Resolved | User chose defaults: core runtime selection, conversations/history, governed tools, cancellation, usage/status, and configuration/readiness. DSH-specific installation, upgrades, and rollback are excluded. |

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Authorized managers MUST be able to select and persist Copilot as an
  employee's runtime alongside native and DSH, without changing existing defaults.
- **FR-002**: Copilot turns MUST preserve employee identity, authorized workspace
  context, applicable instructions, and the configured working scope.
- **FR-003**: Copilot MUST deliver progressive assistant output through existing
  conversations and runtime views, separating available reasoning, tool activity,
  and delegated-work activity from the final answer.
- **FR-004**: Completed exchanges MUST be retained in application-owned conversation
  history and supplied as bounded, authorized context for follow-up turns according
  to existing history policy; the current message MUST NOT be duplicated.
- **FR-005**: Every turn MUST have one final outcome: completed, failed, or cancelled.
  Unexpected interruption, incomplete output, and provider errors MUST NOT appear
  successful, and late notifications MUST NOT change a final outcome.
- **FR-006**: Member cancellation and authorized administrative recycling MUST stop
  the relevant execution, prevent new actions, and release owned resources within
  the configured stop deadline without affecting other executions.
- **FR-007**: Every tool or delegated action MUST remain subject to application-owned
  permissions, workspace boundaries, read-only rules, and approval decisions.
  Unsupported governance MUST block the action rather than grant additional access.
- **FR-008**: Tool activity MUST identify the requested action and pair its outcome
  with that action, distinguishing success, denial, approval required, and failure.
- **FR-009**: Workspace and member authorization MUST protect configuration,
  conversation context, files, runtime status, and lifecycle actions. Strict member
  file isolation MUST reject Copilot execution unless that boundary can be enforced.
- **FR-010**: Administrators MUST be able to configure access and model selection and
  check readiness. Invalid configuration, missing prerequisites, authentication
  failure, and unsupported model selection MUST produce distinct actionable errors.
- **FR-011**: Runtime views MUST identify Copilot ownership, execution state,
  supported capabilities, and usage when supplied; unavailable usage, reasoning,
  approvals, or delegated-work support MUST NOT be represented as supported or zero usage.
- **FR-012**: Configuration diagnostics, conversation errors, and operational records
  MUST NOT reveal authentication secrets or content outside the viewer's authority.
- **FR-013**: Copilot outages or absent configuration MUST NOT prevent application
  startup or native/DSH operation. The system MUST NOT silently substitute runtimes.
- **FR-014**: Application restart MUST preserve completed history and identify
  interrupted work as non-successful. Recovery and retries MUST NOT automatically
  repeat externally visible actions with uncertain outcomes.
- **FR-015**: Startup, idle-progress, and stop deadlines MUST use the application's
  lifecycle policy; expired deadlines MUST result in an explicit failure or
  cancellation and cleanup, not indefinite active status.
- **FR-016**: The current repository MUST account for every intentional Copilot
  runtime source, test, configuration, specification, and documentation change from
  the approved source workspace, including intentional untracked project files.
- **FR-017**: Integration MUST preserve changes that exist only in the current
  repository and MUST surface conflicts instead of silently overwriting them.
- **FR-018**: Integration MUST exclude machine-local credentials, databases, logs,
  caches, generated build output, dependency directories, and local overrides.

### Key Entities *(include if feature involves data)*

- **Employee runtime selection**: The execution engine chosen for a digital employee,
  associated with its workspace, identity, and permitted configuration.
- **Runtime configuration and readiness**: Administrator-owned access settings,
  model selection, prerequisite availability, supported capabilities, and health.
- **Conversation context**: Authorized prior exchanges and employee instructions,
  bounded by conversation policy and linked to one workspace and conversation.
- **Runtime execution**: One employee turn, including ownership, working scope,
  progress, final outcome, usage availability, and cancellation state.
- **Governed action**: A tool or delegated action linked to its execution, permission
  decision, approval state where supported, outcome, and any uncertain external effect.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With prerequisites available, an authorized manager can select Copilot
  for an employee and complete its first conversation within five minutes, without
  leaving the existing employee and conversation experience.
- **SC-002**: All defined acceptance scenarios pass, including a two-turn contextual
  conversation and reopening its retained history; users need no separate chat tool.
- **SC-003**: In 20 controlled stop trials, every cancelled or recycled turn reaches
  a non-active final state within five seconds under the acceptance stop policy,
  and no new action begins after that final state.
- **SC-004**: In paired tests across two workspaces and two members, zero unauthorized
  reads, writes, status disclosures, or cross-conversation context disclosures occur.
- **SC-005**: Every tested missing-access, invalid-configuration, provider-loss,
  quota-limit, and interrupted-turn case produces a visible non-success outcome,
  with zero false successful completions or secret disclosures.
- **SC-006**: Existing native and DSH runtime acceptance scenarios pass unchanged
  both with Copilot disabled and with Copilot configured.
- **SC-007**: With five concurrent conversations, each receives its own output,
  history, and final state; stopping one leaves the other four able to complete.
- **SC-008**: 100% of approved Copilot runtime changes are accounted for in the
  current repository, with zero current-only changes lost and zero excluded
  machine-local items imported.

## Assumptions

- "Coplit" means GitHub Copilot. The user's explicit integration constraint is the
  GitHub Copilot Java SDK. This constraint does not prescribe implementation
  structure; supported versions, authentication modes, and deployment prerequisites
  must be verified during planning rather than assumed available.
- The user chose the proposed default scope. Parity means the core user experience
  and governance, not copying every DSH feature or its internal execution model.
- Existing runtime contracts, conversation persistence, permission controls, and
  runtime views are the baseline. This feature does not redesign native or DSH.
- Administrators supply authorized provider access and any required entitlement.
  Account provisioning, subscription purchasing, and a new authentication service
  are out of scope.
- DSH-specific package installation, generation management, automatic upgrades,
  rollback, and additional deployment modes are out of scope.
- Native-only planning modes, durable Goals, and Team Run orchestration are not
  promised by runtime parity. Delegated-work progress is exposed only when supported
  and governable; adding a new orchestration system is out of scope.
- Provider capabilities may differ. Missing optional usage, reasoning, delegation,
  or approval continuation is disclosed, never simulated. Required governance and
  core conversation/cancellation behavior cannot be waived.
- Only deployment-approved services may receive task content. Existing retention,
  secret management, authorization, and audit policies remain authoritative.
- The five-second stop target applies to controlled acceptance tests configured
  with that deadline; production deployments retain their existing lifecycle policy.
- The migration includes all intentional Copilot runtime project changes, including
  source, tests, configuration, specifications, supporting skills, and
  documentation, plus the backup's project workflow settings. Current-folder-only
  changes remain intact. Machine-local
  credentials, databases, logs, caches, dependency/build output, and `pom.xml.local`
  are excluded from the portable feature scope.
- For inventory, compare the backup worktree with its own HEAD. Include modified or
  deleted tracked project files and intentional untracked project files, subject to
  the exclusions above. This is the planning baseline; it does not prescribe a
  merge or copy mechanism.

## Brainstorm Log

### Session 2026-10-29
**Focus**: Define the scope for bringing backup changes into the current repository.
**Key insights**:
- The backup already contains a Copilot Runtime specification covering the product
  behavior; use it as the source for this feature spec.
- Include all intentional project changes related to the runtime, preserve
  current-only work, and exclude machine-local data and credentials.
- Inventory the backup worktree against its own HEAD, including intentional
  untracked project files.
**Spec updates**: Added a migration scenario and explicit source-inventory,
preservation, conflict, and local-artifact boundaries.
