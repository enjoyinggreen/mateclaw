# Copilot Management and UI Contract

All paths are relative to the existing `/api/v1` prefix. Responses use the
established `R<T>` shape `{code, msg, data}` and application error conventions.
Do not assume HTTP 200 means business success. Existing authentication and
authorization interceptors remain mandatory.

## Global administration

All `/admin/copilot/*` operations require global administrator authorization
(`@RequireGlobalAdmin`), including read-only diagnostics. Readiness is not a public
health endpoint and never exposes user prompts, authentication secrets, child
environment, filesystem contents, or raw provider errors.

### GET /admin/copilot/status

Returns configuration and last checked readiness without starting a model turn.

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "type": "copilot",
    "enabled": false,
    "configuration": {
      "cliPath": "",
      "modelName": "",
      "tokenConfigured": false
    },
    "readiness": {
      "state": "disabled",
      "code": "copilot.disabled",
      "message": "Copilot runtime is disabled.",
      "checkedAt": null
    },
    "capabilities": {
      "cancellation": true,
      "approvals": false,
      "subagents": false,
      "contextUsage": true
    }
  }
}
```

`readiness.state`: `disabled`, `unchecked`, `ready`, or `unavailable`.
`contextUsage=true` means the integration can project provider-reported usage,
not that every model reports all counters. Detailed availability is per execution.
Use actual project success code/message localization; the envelope shown is illustrative.

### PUT /admin/copilot/config

Allowed body keys:

```json
{
  "enabled": true,
  "cliPath": "/absolute/path/to/copilot",
  "modelName": "deployment-approved-copilot-model",
  "githubToken": "<write-only token>",
  "clearToken": false
}
```

This is a partial update. Validate the merged effective settings and reject unknown
keys. `githubToken` absent, blank, or masked (`****...`) means preserve existing;
`clearToken=true` explicitly deletes the managed token. Clear plus a nonblank,
non-masked new token is invalid. No token, masked fragment, command line, or
environment dump is echoed. Responses have the status shape above.

Disabling and repairing configuration is allowed even if the CLI is absent.
Enabling requires valid local fields and explicit access configuration; readiness
still requires the bounded provider check below. Saving never sends a user prompt.
Mark cached readiness unchecked after any effective change.

### POST /admin/copilot/verify

No request body. Perform bounded validation of executable, isolated startup,
protocol handshake, explicit authentication, available model, and fail-closed
configuration. Do not ask a model to generate content or invoke tools. Return the
same status shape with updated readiness.

An installation/protocol may be syntactically valid but incompatible: return
unavailable, not ready. An unsupported or unrecognized requested model is an
explicit error, not a substitution. If the provider cannot verify a prerequisite
without a model call, report it unverified rather than inventing a passing check.
No install, upgrade, rollback, enable-all-permissions, arbitrary CLI argument,
remote endpoint, or task-probe endpoint is added.

## Employee and chat integration

Existing employee create/update endpoints accept `runtimeType="copilot"` and
`runtimeConfig` as a JSON string containing `{}` or `{"model":"..."}`.
Authorization stays at the existing workspace member/manager boundary.
Unknown runtimes and unknown Copilot override keys return an invalid-configuration
business error. A partial update preserves omitted runtime fields.

Existing `/chat/stream`, synchronous chat, conversation history, and member stop
endpoints retain their request shapes. Chat uses the explicit effective Copilot
model rule in [data-model.md](../data-model.md). Invalid native model pins must not
silently select a native runtime. Native-only orchestration or approval replay
against a Copilot employee returns `copilot.operation_unsupported`.

Management secrets are not accepted through employee or chat payloads.

## Live runtime contract

Existing `/admin/agent-runtime/snapshot` adds `runtimeProvider` to each `RunCard`,
captured at turn start (`native`, `dsh`, `copilot`). Preserve all existing fields.
Keep existing required workspace scope, admin authorization, stop/recycle paths,
and audit actions. Runtime configuration edits or employee runtime changes cannot
relabel an already running execution.

## Error vocabulary

| Code | Meaning / expected behavior |
|------|-----------------------------|
| `copilot.disabled` | Not enabled; select/configure intentionally |
| `copilot.config_invalid` | Malformed field, unknown key, or contradictory settings |
| `copilot.cli_unavailable` | Executable missing or unusable |
| `copilot.protocol_incompatible` | Installed CLI fails the SDK handshake/required isolation surface |
| `copilot.authentication_unavailable` | Explicit credential missing, invalid, expired, or revoked |
| `copilot.model_unsupported` | Explicit requested model cannot be used |
| `copilot.workspace_required` | Missing trusted workspace context |
| `copilot.scope_invalid` | Origin mismatch, absent working directory, or path outside authorized root |
| `copilot.member_isolation_unsupported` | Strict member file isolation blocks startup |
| `copilot.tool_denied` | Tool outside employee scope or blocked by policy |
| `copilot.approval_unsupported` | Approval required; action blocked without pending replay |
| `copilot.operation_unsupported` | Native-only orchestration/replay or ungovernable delegation requested |
| `copilot.start_timeout` | Initialization deadline exceeded |
| `copilot.idle_timeout` | Application progress deadline exceeded |
| `copilot.provider_unavailable` | Disconnect, process exit, or provider service failure |
| `copilot.quota_exceeded` | Provider rate/quota restriction |
| `copilot.interrupted` | Persisted in-progress turn failed during application restart |

Errors use translated, actionable messages. Raw stack traces, access tokens, SDK
request dumps, and sensitive filesystem paths do not enter conversation errors.

## UI behavior

- Employee editor and creation wizard retain and save `copilot`; neither converts
  it to native nor clears its allowed override during edit/draft hydration.
- Use Copilot-specific labels, hints, badges, and a filter alongside native/DSH.
  Keep JSON object validation where runtime overrides are edited.
- Model controls for a Copilot employee use Copilot model identifiers; do not offer
  a native model selector that silently replaces the provider.
- Add a global-admin-only Settings > Copilot page and route for configuration,
  status, and verify. No DSH installer/upgrade controls are copied.
- Live cards use the execution's provider. Usage missing from the provider displays
  "Unavailable", including after history reload, rather than a fabricated zero.
- Unsupported approval continuation and delegated execution are visibly disabled.
- Default and English server messages plus `en-US` and `zh-CN` UI strings follow
  existing localization patterns.
