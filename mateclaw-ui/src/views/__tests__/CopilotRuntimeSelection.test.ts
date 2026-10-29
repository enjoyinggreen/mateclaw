// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { agentApi, agentBindingApi, liveApi, modelApi, skillApi, toolApi, wikiApi } from '@/api'
import AgentCreateWizard from '../AgentCreateWizard.vue'
import Agents from '../Agents.vue'

const api = vi.hoisted(() => ({
  agentApi: {
    list: vi.fn(),
    generate: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
  },
  agentBindingApi: {
    listSkills: vi.fn(),
    listTools: vi.fn(),
    listProviderPreferences: vi.fn(),
    listKbs: vi.fn(),
    setSkills: vi.fn(),
    setTools: vi.fn(),
    setProviderPreferences: vi.fn(),
    setKbs: vi.fn(),
  },
  liveApi: { dshDiagnostics: vi.fn() },
  modelApi: { listEnabled: vi.fn(), listProviderOptions: vi.fn() },
  skillApi: { listEnabled: vi.fn(), page: vi.fn() },
  toolApi: { listAvailable: vi.fn() },
  wikiApi: { listBindableKBs: vi.fn() },
}))

vi.mock('@/api', () => api)
vi.mock('@/api/index', () => api)
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
vi.mock('@/composables/useMcToast', () => ({
  mcToast: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
}))
vi.mock('@/components/common/useConfirm', () => ({ mcConfirm: vi.fn() }))
vi.mock('@/composables/useSkillName', () => ({
  useSkillName: () => ({ resolveSkillName: (skill: { name?: string }) => skill.name || '' }),
}))

const apps: Array<ReturnType<typeof createApp>> = []

function mount(component: Parameters<typeof createApp>[0]) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(component)
  app.use(createI18n({
    legacy: false,
    locale: 'en-US',
    messages: { 'en-US': {} },
    missingWarn: false,
    fallbackWarn: false,
  }))
  app.mount(host)
  apps.push(app)
  return host
}

async function eventually(assertion: () => void) {
  let lastError: unknown
  for (let i = 0; i < 30; i += 1) {
    await Promise.resolve()
    await new Promise(resolve => setTimeout(resolve, 0))
    try {
      assertion()
      return
    } catch (error) {
      lastError = error
    }
  }
  throw lastError
}

function setInputValue(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
}

const copilotAgent = {
  id: 'agent-copilot',
  name: 'Copilot helper',
  description: 'Existing description',
  agentType: 'react',
  runtimeType: 'copilot',
  runtimeConfig: '{"model":"gpt-4.1"}',
  systemPrompt: '',
  modelName: '',
  maxIterations: 10,
  icon: '',
  tags: '',
  enabled: true,
  primaryKbId: null,
}

describe('Copilot runtime selection in mounted agent views', () => {
  beforeEach(() => {
    vi.mocked(agentApi.list).mockResolvedValue({ data: [copilotAgent] } as never)
    vi.mocked(agentApi.generate).mockResolvedValue({
      data: {
        ...copilotAgent,
        tools: [],
        skillIds: [],
        tags: [],
        recommendedQuestions: [],
      },
    } as never)
    vi.mocked(agentApi.create).mockResolvedValue({ data: { ...copilotAgent } } as never)
    vi.mocked(agentApi.update).mockResolvedValue({ data: copilotAgent } as never)
    vi.mocked(modelApi.listEnabled).mockResolvedValue({
      data: [{ id: 'native-model', name: 'Native model', provider: 'openai', modelName: 'gpt-native' }],
    } as never)
    vi.mocked(modelApi.listProviderOptions).mockResolvedValue({ data: [] } as never)
    vi.mocked(skillApi.listEnabled).mockResolvedValue({ data: [] } as never)
    vi.mocked(skillApi.page).mockResolvedValue({ data: { records: [] } } as never)
    vi.mocked(toolApi.listAvailable).mockResolvedValue({ data: [] } as never)
    vi.mocked(wikiApi.listBindableKBs).mockResolvedValue({ data: [] } as never)
    vi.mocked(liveApi.dshDiagnostics).mockResolvedValue({ data: null } as never)
    vi.mocked(agentBindingApi.listSkills).mockResolvedValue({ data: [] } as never)
    vi.mocked(agentBindingApi.listTools).mockResolvedValue({ data: [] } as never)
    vi.mocked(agentBindingApi.listProviderPreferences).mockResolvedValue({ data: [] } as never)
    vi.mocked(agentBindingApi.listKbs).mockResolvedValue({ data: [] } as never)
    vi.mocked(agentBindingApi.setSkills).mockResolvedValue({ data: null } as never)
    vi.mocked(agentBindingApi.setTools).mockResolvedValue({ data: null } as never)
    vi.mocked(agentBindingApi.setProviderPreferences).mockResolvedValue({ data: null } as never)
    vi.mocked(agentBindingApi.setKbs).mockResolvedValue({ data: null } as never)
  })

  afterEach(() => {
    apps.splice(0).forEach(app => app.unmount())
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('hydrates a Copilot wizard draft and submits its model override without falling back to native', async () => {
    const host = mount(AgentCreateWizard)
    setInputValue(host.querySelector<HTMLTextAreaElement>('.wiz-textarea')!, 'Create a helper')
    await nextTick()
    host.querySelector<HTMLButtonElement>('.wiz-input-foot .wiz-btn-primary')!.click()

    await eventually(() => expect(agentApi.generate).toHaveBeenCalledWith('Create a helper'))
    await eventually(() => {
      expect(host.querySelector('.wiz-rail')).not.toBeNull()
    })

    const runtimeSelect = Array.from(host.querySelectorAll<HTMLSelectElement>('.wiz-grid select'))
      .find(select => Array.from(select.options).some(option => option.value === 'copilot'))
    expect(runtimeSelect).toBeDefined()
    expect(runtimeSelect?.value).toBe('copilot')
    expect(host.querySelector<HTMLTextAreaElement>('.runtime-config-editor')?.value)
      .toBe('{"model":"gpt-4.1"}')

    host.querySelector<HTMLButtonElement>('.wiz-actions .wiz-btn-primary')!.click()
    await eventually(() => expect(agentApi.create).toHaveBeenCalled())

    const payload = vi.mocked(agentApi.create).mock.calls[0][0] as Record<string, unknown>
    expect(payload.runtimeType).toBe('copilot')
    expect(JSON.parse(String(payload.runtimeConfig))).toEqual({ model: 'gpt-4.1' })
  })

  it('keeps Copilot model selection separate from native models and preserves it on partial edit', async () => {
    const host = mount(Agents)
    await eventually(() => expect(host.querySelector('.agent-card')).not.toBeNull())
    const copilotFilter = Array.from(host.querySelectorAll<HTMLButtonElement>('.filter-tab'))
      .find(button => button.textContent?.trim() === 'agents.tabs.copilot')
    expect(copilotFilter).toBeDefined()
    copilotFilter!.click()
    expect(host.querySelector('.agent-card .agent-runtime-badge--copilot')).not.toBeNull()

    host.querySelector<HTMLButtonElement>('[title="agents.actions.edit"]')!.click()
    await eventually(() => expect(host.querySelector('.modal-overlay')).not.toBeNull())

    const runtimeSelect = Array.from(host.querySelectorAll<HTMLSelectElement>('.form-grid select'))
      .find(select => Array.from(select.options).some(option => option.value === 'copilot'))
    expect(runtimeSelect?.value).toBe('copilot')
    expect(Array.from(host.querySelectorAll<HTMLOptionElement>('select option'))
      .some(option => option.value === 'gpt-native'))
      .toBe(false)

    setInputValue(host.querySelector<HTMLInputElement>('.form-grid input')!, 'Updated helper')
    host.querySelector<HTMLButtonElement>('.modal-footer .btn-primary')!.click()
    await eventually(() => expect(agentApi.update).toHaveBeenCalled())

    const payload = vi.mocked(agentApi.update).mock.calls[0][1] as Record<string, unknown>
    expect(payload.runtimeType).toBe('copilot')
    expect(JSON.parse(String(payload.runtimeConfig))).toEqual({ model: 'gpt-4.1' })
  })
})
