import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '@/types'

vi.mock('/logo/mateclaw_logo_s.png', () => ({ default: '/logo/mateclaw_logo_s.png' }))
vi.mock('@/composables/useStreamingMarkdown', () => ({
  useStreamingMarkdown: (getContent: unknown) => ({
    html: typeof getContent === 'function' ? getContent() : getContent,
    copyCode: vi.fn(),
  }),
}))
vi.mock('@/composables/useAuthenticatedAttachment', () => ({
  useAuthenticatedAttachment: () => ({
    blobUrls: {}, loadAllImages: vi.fn(), loadAllVideos: vi.fn(), loadAllAudios: vi.fn(),
    loadAllModels: vi.fn(), downloadFile: vi.fn(), openImage: vi.fn(),
    getDisplayUrl: (url: string) => url, revokeAll: vi.fn(),
  }),
}))
vi.mock('@/composables/useMcToast', () => ({
  mcToast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))
vi.mock('@/composables/useToolLabel', () => ({
  useToolLabel: () => ({ getToolLabel: (name: string) => name }),
}))
vi.mock('@/api', () => ({ http: { get: vi.fn(), post: vi.fn() }, fetchAuthenticatedBlob: vi.fn() }))
vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn() }))
vi.mock('@/utils/generatedFileLinks', () => ({
  buildGeneratedFileNameMap: vi.fn(() => new Map()),
  linkifyGeneratedFileUrls: vi.fn((content: string) => content),
}))
vi.mock('@/utils/lazyModelViewer', () => ({ ensureModelViewer: vi.fn() }))
vi.mock('../preview/previewKind', () => ({ previewKindOf: vi.fn(() => null) }))
vi.mock('../preview/previewBus', () => ({ openFilePreview: vi.fn() }))
vi.mock('@/components/goal/GoalAvatarRing.vue', () => ({
  default: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
}))
vi.mock('../ThinkingSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.thinkingText) }),
}))
vi.mock('../ContentSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.text) }),
}))
vi.mock('../PlanStepsPanel.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../BrowserTimeline.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../TypingCursor.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../UserMessageContent.vue', () => ({
  default: defineComponent({ props: ['content'], setup: props => () => h('div', props.content) }),
}))

import MessageBubble from '../MessageBubble.vue'
import ContextUsagePanel from '../ContextUsagePanel.vue'

const apps: Array<ReturnType<typeof createApp>> = []
const i18n = createI18n({
  legacy: false,
  missingWarn: false,
  fallbackWarn: false,
  locale: 'en',
  messages: {
    en: {
      chat: {
        replyModel: 'Reply model: {model}',
        stopped: 'Stopped',
        interrupted: 'Interrupted',
        usageDetail: {
          title: 'Usage detail', total: 'Total', input: 'Input', output: 'Output',
          cacheHit: 'Cache hit', cacheMiss: 'Cache miss', cacheWrite: 'Cache write',
          reasoning: 'Reasoning', reply: 'Reply', hitRate: 'Hit rate', unavailable: 'Unavailable',
        },
        contextUsage: {
          title: 'Context used', chipTooltip: 'Context usage', system: 'System prompt',
          tools: 'Tools', history: 'History', current: 'Current input',
          willCompact: 'Near limit', estimated: 'estimated',
          providerReportedTooltip: 'Provider-reported context',
        },
      },
    },
  },
})

function mountBubble(message: Message) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(MessageBubble, { message })
  app.use(createPinia())
  app.use(i18n)
  app.component('el-icon', { template: '<span><slot /></span>' })
  app.component('el-popover', defineComponent({
    setup: (_, { slots }) => () => h('div', [slots.reference?.(), slots.default?.()]),
  }))
  app.component('model-viewer', { template: '<span />' })
  app.mount(host)
  apps.push(app)
  return host
}

function mountContextUsage(usage: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(ContextUsagePanel, { usage: usage as never })
  app.use(i18n)
  app.component('el-icon', { template: '<span><slot /></span>' })
  app.component('el-popover', defineComponent({
    setup: (_, { slots }) => () => h('div', [slots.reference?.(), slots.default?.()]),
  }))
  app.mount(host)
  apps.push(app)
  return host
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('Copilot usage display', () => {
  it('shows Unavailable instead of a zero token total when Copilot reports no counters', () => {
    const host = mountBubble({
      id: 1, conversationId: 'conv', role: 'assistant', content: 'partial',
      contentParts: [], status: 'completed',
      metadata: {
        runtimeProviderId: 'copilot',
        runtimeModelName: 'actual-copilot-model',
        usageAvailability: {
          promptTokens: false, completionTokens: false, contextTokens: false, contextWindow: false,
        },
      },
    } as Message)

    expect(host.querySelector('.action-tokens')?.textContent).toContain('Unavailable')
    expect(host.textContent).not.toContain('Σ 0 tok')
    expect(host.querySelector('.action-model')?.getAttribute('title')).toContain('(copilot)')
  })

  it('totals only measured Copilot counters and labels missing breakdowns Unavailable', () => {
    const host = mountBubble({
      id: 2, conversationId: 'conv', role: 'assistant', content: 'answer',
      contentParts: [], status: 'completed', runtimeProvider: 'copilot',
      promptTokens: 0, completionTokens: 0,
      metadata: {
        promptTokens: 42,
        usageAvailability: {
          promptTokens: true, completionTokens: false, contextTokens: false, contextWindow: false,
        },
      },
    } as Message)

    expect(host.querySelector('.action-tokens')?.textContent).toContain('Σ 42 tok')
    expect(host.querySelector('.mc-usage-panel')?.textContent).toContain('Unavailable')
    expect(host.querySelector('.mc-usage-total')?.textContent).toBe('42')
  })

  it('shows provider-reported context on a reloaded reply without treating it as input/output usage', () => {
    const host = mountBubble({
      id: 5, conversationId: 'conv', role: 'assistant', content: 'answer',
      contentParts: [], status: 'completed',
      metadata: {
        runtimeProviderId: 'copilot',
        runtimeModelName: 'actual-copilot-model',
        contextTokens: 500,
        contextWindow: 128000,
        usageAvailability: {
          promptTokens: false, completionTokens: false, contextTokens: true, contextWindow: true,
        },
      },
    } as Message)

    expect(host.querySelector('.ctx-chip')).not.toBeNull()
    expect(host.querySelector('.ctx-panel__totals')?.textContent?.replace(/\s+/g, ' ').trim())
      .toBe('500 / 128.0K')
    expect(host.querySelector('.action-tokens')?.textContent).toContain('Unavailable')
  })

  it('preserves legacy numeric totals and displays the provider captured for the reply', () => {
    const host = mountBubble({
      id: 3, conversationId: 'conv', role: 'assistant', content: 'answer',
      contentParts: [], status: 'completed', runtimeProvider: 'openai', runtimeModel: 'actual-model',
      promptTokens: 80, completionTokens: 20,
    } as Message)

    expect(host.querySelector('.action-tokens')?.textContent).toContain('Σ 100 tok')
    expect(host.querySelector('.action-model')?.getAttribute('title')).toContain('(openai)')
  })

  it.each([
    ['failed', '.error-card'],
    ['stopped', '.stopped-indicator'],
  ] as const)('keeps partial output visibly %s instead of completed', async (status, indicator) => {
    const host = mountBubble({
      id: 4, conversationId: 'conv', role: 'assistant', content: 'partial answer',
      contentParts: [], status,
    } as Message)
    await nextTick()

    expect(host.querySelector('.msg-content')?.textContent).toContain('partial answer')
    expect(host.querySelector(indicator)).not.toBeNull()
    expect(host.querySelector('.message-wrapper')?.getAttribute('data-status')).toBe(status)
  })

  it('renders provider-reported context-only usage without estimated source breakdowns', () => {
    const host = mountContextUsage({
      runtimeProviderId: 'copilot',
      usedTokens: 500,
      windowTokens: 128000,
      systemTokens: 0,
      toolsTokens: 0,
      historyTokens: 0,
      currentTokens: 0,
      usageAvailability: {
        promptTokens: false, completionTokens: false, contextTokens: true, contextWindow: true,
      },
    })

    expect(host.querySelector('.ctx-panel__totals')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('500 / 128.0K')
    expect(host.querySelector('.ctx-panel__rows')).toBeNull()
    expect(host.querySelector('.ctx-panel__estimate')).toBeNull()
  })

  it('shows Unavailable when Copilot has no provider-reported context measurement', () => {
    const host = mountContextUsage({
      runtimeProviderId: 'copilot',
      usedTokens: 0,
      windowTokens: 0,
      systemTokens: 0,
      toolsTokens: 0,
      historyTokens: 0,
      currentTokens: 0,
      usageAvailability: {
        promptTokens: false, completionTokens: false, contextTokens: false, contextWindow: false,
      },
    })

    expect(host.textContent).toContain('Unavailable')
  })
})
