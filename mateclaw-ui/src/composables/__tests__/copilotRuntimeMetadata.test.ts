// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const streamMock = vi.hoisted(() => ({
  handlers: new Map<string, Set<(data: any) => void>>(),
  connect: vi.fn().mockResolvedValue(undefined),
  disconnect: vi.fn(),
  send: vi.fn(),
  setConversationId: vi.fn(),
  on: vi.fn((event: string, handler: (data: any) => void) => {
    const listeners = streamMock.handlers.get(event) ?? new Set()
    listeners.add(handler)
    streamMock.handlers.set(event, listeners)
    return () => listeners.delete(handler)
  }),
}))

vi.mock('@/composables/chat/useStream', () => ({
  useStream: () => streamMock,
}))

import { useChat } from '@/composables/chat/useChat'

function emit(event: string, data: Record<string, unknown>) {
  streamMock.handlers.get(event)?.forEach(handler => handler(data))
}

describe('Copilot runtime metadata in chat', () => {
  beforeEach(() => {
    streamMock.handlers.clear()
    setActivePinia(createPinia())
    streamMock.connect.mockClear()
    streamMock.disconnect.mockClear()
    streamMock.send.mockClear()
    streamMock.setConversationId.mockClear()
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('projects final usage availability, run-start provider and model into live and history messages', async () => {
    const chat = useChat({ baseUrl: '/api' })
    await chat.sendMessage('hello', { conversationId: 'conv-1', agentId: 'agent-1' })

    emit('_usage_final', {
      conversationId: 'conv-1',
      runtimeProviderId: 'copilot',
      runtimeModelName: 'actual-copilot-model',
      promptTokens: 0,
      completionTokens: 0,
      contextTokens: 500,
      contextWindow: 128000,
      usageAvailability: {
        promptTokens: false,
        completionTokens: false,
        contextTokens: true,
        contextWindow: true,
      },
    })
    emit('done', {
      conversationId: 'conv-1',
      status: 'completed',
      assistantMessageId: 91,
      runtimeExecutionId: 'run-1',
      runtimeOutcome: 'completed',
      runtimeProviderId: 'copilot',
      runtimeModelName: 'actual-copilot-model',
      usageAvailability: {
        promptTokens: false,
        completionTokens: false,
        contextTokens: true,
        contextWindow: true,
      },
      contextTokens: 500,
      contextWindow: 128000,
    })

    const assistant = chat.messages.value.find(message => message.role === 'assistant')
    expect(assistant).toMatchObject({
      id: 91,
      status: 'completed',
      runtimeProvider: 'copilot',
      runtimeModel: 'actual-copilot-model',
      metadata: {
        runtimeExecutionId: 'run-1',
        usageAvailability: {
          promptTokens: false,
          completionTokens: false,
          contextTokens: true,
          contextWindow: true,
        },
        contextTokens: 500,
        contextWindow: 128000,
      },
    })
    expect(assistant?.promptTokens).toBeUndefined()
    expect(assistant?.completionTokens).toBeUndefined()
    expect(chat.contextUsage.value).toMatchObject({
      runtimeProviderId: 'copilot',
      usedTokens: 500,
      windowTokens: 128000,
      usageAvailability: {
        contextTokens: true,
        contextWindow: true,
      },
    })
  })

  it('keeps only available measured counters from a live Copilot completion', async () => {
    const chat = useChat({ baseUrl: '/api' })
    await chat.sendMessage('hello', { conversationId: 'conv-measured', agentId: 'agent-1' })

    emit('done', {
      conversationId: 'conv-measured',
      status: 'completed',
      assistantMessageId: 92,
      runtimeOutcome: 'completed',
      runtimeProviderId: 'copilot',
      runtimeModelName: 'actual-copilot-model',
      promptTokens: 42,
      completionTokens: 0,
      usageAvailability: {
        promptTokens: true,
        completionTokens: false,
        contextTokens: false,
        contextWindow: false,
      },
    })

    const assistant = chat.messages.value.find(message => message.role === 'assistant')
    expect(assistant?.promptTokens).toBe(42)
    expect(assistant?.completionTokens).toBeUndefined()
    expect(assistant?.metadata).toMatchObject({
      promptTokens: 42,
      usageAvailability: {
        promptTokens: true,
        completionTokens: false,
        contextTokens: false,
        contextWindow: false,
      },
    })
    expect(assistant?.metadata).not.toHaveProperty('completionTokens')
  })

  it('marks a live Copilot completion with missing availability metadata as unavailable', async () => {
    const chat = useChat({ baseUrl: '/api' })
    await chat.sendMessage('hello', { conversationId: 'conv-missing', agentId: 'agent-1' })

    emit('done', {
      conversationId: 'conv-missing',
      status: 'completed',
      runtimeProviderId: 'copilot',
      runtimeModelName: 'actual-copilot-model',
      promptTokens: 0,
      completionTokens: 0,
    })

    const assistant = chat.messages.value.find(message => message.role === 'assistant')
    expect(assistant?.promptTokens).toBeUndefined()
    expect(assistant?.completionTokens).toBeUndefined()
    expect(assistant?.metadata?.usageAvailability).toEqual({
      promptTokens: false,
      completionTokens: false,
      contextTokens: false,
      contextWindow: false,
    })
    expect(chat.contextUsage.value).toMatchObject({
      runtimeProviderId: 'copilot',
      usageAvailability: {
        contextTokens: false,
        contextWindow: false,
      },
    })
  })

  it.each([
    ['failed', 'failed'],
    ['cancelled', 'stopped'],
  ] as const)('does not turn a Copilot %s runtime outcome into a successful completion', async (outcome, status) => {
    const onStreamEnd = vi.fn()
    const chat = useChat({ baseUrl: '/api', onStreamEnd })
    await chat.sendMessage('hello', { conversationId: `conv-${outcome}`, agentId: 'agent-1' })

    emit('done', {
      conversationId: `conv-${outcome}`,
      status: 'completed',
      runtimeOutcome: outcome,
      runtimeProviderId: 'copilot',
    })

    const assistant = chat.messages.value.find(message => message.role === 'assistant')
    expect(assistant?.status).toBe(status)
    expect(assistant?.metadata?.runtimeOutcome).toBe(outcome)
    expect(onStreamEnd).toHaveBeenCalledWith(expect.objectContaining({ reason: status }))
  })
})
