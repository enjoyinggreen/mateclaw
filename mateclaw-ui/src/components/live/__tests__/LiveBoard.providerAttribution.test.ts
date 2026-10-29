import { createApp, defineComponent } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LiveRunCard, LiveSummary } from '@/api'

vi.mock('@/components/common/SkillIcon.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}))
vi.mock('@/composables/useLiveAgent', () => ({
  useLiveAgent: () => ({
    avatarLetter: () => 'A',
    avatarBgStyle: () => ({}),
    ringClass: () => '',
    humanSentence: () => 'Working',
    formatAge: () => '1m',
  }),
}))

import LiveBoard from '../LiveBoard.vue'

const apps: Array<ReturnType<typeof createApp>> = []

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('LiveBoard provider attribution', () => {
  it('displays the provider captured for the active run', () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const run: LiveRunCard = {
      conversationId: 'conv-1',
      agentId: 7,
      agentName: 'Configured employee',
      agentIcon: null,
      runtimeProvider: 'copilot',
      username: 'alice',
      currentPhase: 'streaming',
      runningToolName: null,
      waitingReason: null,
      done: false,
      stopRequested: false,
      firstTokenReceived: true,
      subscriberCount: 1,
      queueLen: 0,
      ageMs: 60000,
      msSinceLastEvent: 1000,
      stuckReason: null,
      orphan: false,
      subagentCount: 0,
    }
    const summary: LiveSummary = { running: 1, stuck: 0, orphan: 0, queued: 0, subagentsActive: 0 }
    const app = createApp(LiveBoard, {
      runs: [run],
      summary,
      goalByConv: {},
      doneGoals: [],
      failedGoals: [],
    })
    app.use(createI18n({
      legacy: false,
      missingWarn: false,
      fallbackWarn: false,
      locale: 'en',
      messages: { en: { live: { board: { running: 'Running', attention: 'Attention', done: 'Done', failed: 'Failed', emptyCol: 'No runs' } } } },
    }))
    app.mount(host)
    apps.push(app)

    expect(host.querySelector('.lb-card__provider')?.textContent).toBe('copilot')
  })
})
