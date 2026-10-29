import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import { http } from '@/api'
import enUS from '@/i18n/locales/en-US'
import CopilotSettings from '../index.vue'

const workspace = vi.hoisted(() => ({ isGlobalAdmin: true }))
vi.mock('@/stores/useWorkspaceStore', () => ({
  useWorkspaceStore: () => workspace,
}))

let app: App | undefined
let host: HTMLDivElement

const readyStatus = {
  type: 'copilot',
  enabled: false,
  configuration: {
    cliPath: '',
    modelName: '',
    tokenConfigured: false,
  },
  readiness: {
    state: 'disabled',
    code: 'copilot.disabled',
    message: 'Copilot runtime is disabled.',
    checkedAt: null,
  },
  capabilities: {
    cancellation: true,
    approvals: false,
    subagents: false,
    contextUsage: true,
  },
}

function envelope(data: unknown) {
  return { code: 200, msg: 'success', data }
}

async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}

async function mountPage(messages: any = {}) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(CopilotSettings)
  app.use(createI18n({
    legacy: false,
    locale: 'en-US',
    messages: { 'en-US': messages },
    missingWarn: false,
    fallbackWarn: false,
  }))
  app.mount(host)
  await flush()
}

function button(matcher: RegExp) {
  return [...host.querySelectorAll('button')]
    .find((candidate) => matcher.test(candidate.textContent || '')) as HTMLButtonElement | undefined
}

function labeledInput(matcher: RegExp) {
  const label = [...host.querySelectorAll('label')]
    .find((candidate) => matcher.test(candidate.textContent || ''))
  return label?.querySelector('input') as HTMLInputElement | null
}

function changeInput(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  workspace.isGlobalAdmin = true
  vi.spyOn(http, 'get').mockResolvedValue(envelope(readyStatus) as any)
  vi.spyOn(http, 'put').mockResolvedValue(envelope(readyStatus) as any)
  vi.spyOn(http, 'post').mockResolvedValue(envelope(readyStatus) as any)
})

afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

it('does not expose Copilot management controls or fetch status to non-global admins', async () => {
  workspace.isGlobalAdmin = false
  await mountPage()

  expect(button(/save|保存/i)).toBeUndefined()
  expect(button(/verify|验证/i)).toBeUndefined()
  expect(http.get).not.toHaveBeenCalled()
})

it('shows status loading and a recoverable failure when the status request fails', async () => {
  const request = deferred<any>()
  vi.mocked(http.get).mockReturnValueOnce(request.promise)
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(CopilotSettings)
  app.use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': {} }, missingWarn: false, fallbackWarn: false }))
  app.mount(host)

  expect(host.textContent).toMatch(/loading|读取|加载/i)
  expect(http.get).toHaveBeenCalledWith('/admin/copilot/status')
  expect(`${http.defaults.baseURL}${vi.mocked(http.get).mock.calls[0][0]}`)
    .toBe('/api/v1/admin/copilot/status')
  request.reject(new Error('Status unavailable'))
  await flush()

  expect(host.textContent).toContain('Status unavailable')
  expect(button(/save|保存/i)).toBeUndefined()
})

it('uses the management config endpoint and keeps save disabled while saving, then shows failures', async () => {
  await mountPage()
  const cliPath = labeledInput(/executable|cli path|可执行文件|CLI 路径/i)
  const modelName = labeledInput(/model name|模型/i)
  expect(cliPath).not.toBeNull()
  expect(modelName).not.toBeNull()
  changeInput(cliPath!, '/opt/copilot')
  changeInput(modelName!, 'approved-model')

  const request = deferred<any>()
  vi.mocked(http.put).mockReturnValueOnce(request.promise)
  button(/save|保存/i)!.click()
  await flush()

  expect(http.put).toHaveBeenCalledWith('/admin/copilot/config', expect.objectContaining({
    cliPath: '/opt/copilot',
    modelName: 'approved-model',
  }))
  expect(`${http.defaults.baseURL}${vi.mocked(http.put).mock.calls[0][0]}`)
    .toBe('/api/v1/admin/copilot/config')
  expect(button(/save|保存/i)?.disabled).toBe(true)
  request.reject(new Error('Configuration could not be saved'))
  await flush()

  expect(host.textContent).toContain('Configuration could not be saved')
})

it('uses the bodyless management verify endpoint and shows verify loading and failure', async () => {
  await mountPage()
  const request = deferred<any>()
  vi.mocked(http.post).mockReturnValueOnce(request.promise)
  button(/verify|验证/i)!.click()
  await flush()

  expect(http.post).toHaveBeenCalledWith('/admin/copilot/verify')
  expect(`${http.defaults.baseURL}${vi.mocked(http.post).mock.calls[0][0]}`)
    .toBe('/api/v1/admin/copilot/verify')
  expect(button(/verify|验证/i)?.disabled).toBe(true)
  request.reject(new Error('Verification unavailable'))
  await flush()

  expect(host.textContent).toContain('Verification unavailable')
})

it('shows token presence without a secret and preserves it when the write-only field is blank', async () => {
  vi.mocked(http.get).mockResolvedValueOnce(envelope({
    ...readyStatus,
    configuration: { ...readyStatus.configuration, tokenConfigured: true },
  }) as any)
  await mountPage()

  const tokenInput = host.querySelector('input[type="password"]') as HTMLInputElement | null
  expect(tokenInput).not.toBeNull()
  expect(tokenInput?.value).toBe('')
  expect(host.textContent).toMatch(/token.*configured|configured.*token|已配置/i)

  button(/save|保存/i)!.click()
  await flush()
  expect(http.put).toHaveBeenCalledWith('/admin/copilot/config', expect.objectContaining({
    clearToken: false,
  }))
  const savedConfig = vi.mocked(http.put).mock.calls[0][1] as Record<string, unknown>
  expect(savedConfig.githubToken).toBeUndefined()
  expect(JSON.stringify(host.textContent)).not.toContain('githubToken')
})

it('sends an explicit clear request only after the administrator chooses to clear the managed token', async () => {
  vi.mocked(http.get).mockResolvedValueOnce(envelope({
    ...readyStatus,
    configuration: { ...readyStatus.configuration, tokenConfigured: true },
  }) as any)
  await mountPage()

  const clearToken = labeledInput(/clear.*token|remove.*token|清除.*令牌|删除.*令牌/i)
  expect(clearToken).not.toBeNull()
  clearToken!.click()
  button(/save|保存/i)!.click()
  await flush()

  expect(http.put).toHaveBeenCalledWith('/admin/copilot/config', expect.objectContaining({
    clearToken: true,
  }))
  const savedConfig = vi.mocked(http.put).mock.calls[0][1] as Record<string, unknown>
  expect(savedConfig.githubToken).toBeUndefined()
})

it('keeps invalid configuration repairable and displays translated remediation', async () => {
  const remediation = 'Copilot configuration is invalid. Set an absolute executable path and an approved model.'
  vi.mocked(http.get).mockResolvedValueOnce(envelope({
    ...readyStatus,
    readiness: {
      state: 'unavailable',
      code: 'copilot.config_invalid',
      message: remediation,
      checkedAt: '2026-10-28T12:00:00Z',
    },
  }) as any)
  await mountPage()

  expect(host.textContent).toContain(remediation)
  expect(button(/save|保存/i)).toBeDefined()
  expect(button(/save|保存/i)?.disabled).toBe(false)
})

it('displays translated CLI remediation instead of exposing raw provider diagnostics', async () => {
  const remediation = 'Copilot could not be started. Check the configured executable path.'
  vi.mocked(http.get).mockResolvedValueOnce(envelope({
    ...readyStatus,
    readiness: {
      state: 'unavailable',
      code: 'copilot.cli_unavailable',
      message: remediation,
      checkedAt: '2026-10-28T12:00:00Z',
    },
  }) as any)
  await mountPage()

  expect(host.textContent).toContain(remediation)
  expect(host.textContent).not.toContain('ENOENT: private provider diagnostic')
})

it('renders readiness state using the localized Copilot catalog', async () => {
  vi.mocked(http.get).mockResolvedValueOnce(envelope({
    ...readyStatus,
    readiness: {
      state: 'unavailable',
      code: 'copilot.cli_unavailable',
      message: 'Copilot executable unavailable. Check the configured path.',
      checkedAt: '2026-10-28T12:00:00Z',
    },
  }) as any)
  await mountPage(enUS)

  expect(host.textContent).toContain(enUS.settings.copilot.unavailable)
  expect(host.textContent).toContain('Copilot executable unavailable. Check the configured path.')
})

it('keeps approval and delegated execution unavailable when the runtime does not support them', async () => {
  await mountPage()
  const unsupportedControls = [...host.querySelectorAll('button, input, [role="button"]')]
    .filter((element) => /approval|subagent|delegat|审批|委派/i.test(
      `${element.getAttribute('aria-label') || ''} ${element.textContent || ''}`,
    ))

  expect(unsupportedControls.length).toBeGreaterThan(0)
  expect(unsupportedControls.every((element) =>
    (element as HTMLButtonElement).disabled || element.getAttribute('aria-disabled') === 'true',
  )).toBe(true)
})
