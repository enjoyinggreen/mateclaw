import { afterEach, describe, expect, it } from 'vitest'
import { effectScope, nextTick, ref, watch } from 'vue'
import ts from 'typescript'
import chatConsoleSource from '../ChatConsole.vue?raw'

// Execute the view's actual readiness logic without mounting its chat/rendering integrations.
const script = chatConsoleSource.split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const parsed = ts.createSourceFile('ChatConsole.ts', script, ts.ScriptTarget.Latest, true)
const readinessFunction = parsed.statements.find(statement =>
  ts.isFunctionDeclaration(statement) && statement.name?.text === 'recomputePromptFlags',
)!
const loadFunction = parsed.statements.find(statement =>
  ts.isFunctionDeclaration(statement) && statement.name?.text === 'loadModelState',
)!
const readinessWatcher = parsed.statements.find(statement =>
  ts.isExpressionStatement(statement)
  && ts.isCallExpression(statement.expression)
  && statement.expression.expression.getText(parsed) === 'watch'
  && statement.expression.arguments[1]?.getText(parsed) === 'recomputePromptFlags',
)!
const javascript = ts.transpileModule(
  [readinessFunction, loadFunction, readinessWatcher].map(node => node.getText(parsed)).join('\n'),
  { compilerOptions: { target: ts.ScriptTarget.ESNext, module: ts.ModuleKind.ESNext } },
).outputText

const scopes: ReturnType<typeof effectScope>[] = []
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()))

function readiness(runtimeType = 'copilot') {
  const state = {
    currentAgentRuntimeType: ref(runtimeType),
    activeModels: ref<{ activeLlm: { providerId: string; model: string } } | null>({
      activeLlm: { providerId: 'copilot', model: 'gpt-6-luna' },
    }),
    providers: ref<Array<{ id: string; liveness: string; models: Array<{ id: string }> }>>([]),
    providersUnavailable: ref(false),
    blockingPrompt: ref(false),
    recoverablePrompt: ref(false),
    recoverableDismissed: ref(false),
    workspaceStore: { isGlobalAdmin: true },
    modelApi: {
      getDefault: () => Promise.reject(new Error('Native model configuration unavailable')),
      getActive: () => Promise.resolve({ data: null }),
      listEnabled: () => Promise.resolve({ data: [] }),
    },
    mcToast: { error: () => {} },
    t: (key: string) => key,
  }
  const scope = effectScope()
  scopes.push(scope)
  const handlers = scope.run(() => new Function(
    ...Object.keys(state), 'watch',
    `${javascript}\nreturn { recomputePromptFlags, loadModelState }`,
  )(...Object.values(state), watch)) as {
    recomputePromptFlags: () => void
    loadModelState: () => Promise<void>
  }
  handlers.recomputePromptFlags()
  return { ...state, ...handlers }
}

describe('chat runtime readiness', () => {
  it('allows a resolved Copilot model without any native providers', () => {
    const state = readiness()
    expect(state.blockingPrompt.value).toBe(false)
    expect(state.recoverablePrompt.value).toBe(false)
  })

  it('does not re-block Copilot when loading native model configuration fails', async () => {
    const state = readiness()
    await state.loadModelState()
    expect(state.blockingPrompt.value).toBe(false)
    expect(state.recoverablePrompt.value).toBe(false)
  })

  it('rechecks provider requirements when switching runtime with the same model snapshot', async () => {
    const state = readiness('native')
    expect(state.blockingPrompt.value).toBe(true)
    state.currentAgentRuntimeType.value = 'copilot'
    await nextTick()
    expect(state.blockingPrompt.value).toBe(false)
    state.currentAgentRuntimeType.value = 'native'
    await nextTick()
    expect(state.blockingPrompt.value).toBe(true)
  })

  it('still blocks Copilot until a runtime model has resolved', async () => {
    const state = readiness()
    state.activeModels.value = null
    await nextTick()
    expect(state.blockingPrompt.value).toBe(true)
  })

  it('keeps native provider fallback warnings and blocking behavior', async () => {
    const state = readiness('native')
    expect(state.blockingPrompt.value).toBe(true)
    state.providers.value = [{ id: 'openai', liveness: 'LIVE', models: [{ id: 'native-model' }] }]
    await nextTick()
    expect(state.blockingPrompt.value).toBe(false)
    expect(state.recoverablePrompt.value).toBe(true)
    state.activeModels.value = { activeLlm: { providerId: 'openai', model: 'native-model' } }
    await nextTick()
    expect(state.recoverablePrompt.value).toBe(false)
    await state.loadModelState()
    expect(state.blockingPrompt.value).toBe(true)
  })
})
