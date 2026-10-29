import { describe, expect, it } from 'vitest'

import { normalizeRuntimeConfig, normalizeRuntimeType } from '../runtimeSelection'

describe('Copilot runtime selection helpers', () => {
  it('keeps saved Copilot selection and its allowed model override during hydration', () => {
    expect(normalizeRuntimeType('copilot')).toBe('copilot')
    expect(JSON.parse(normalizeRuntimeConfig('copilot', '{"model":"gpt-4.1"}')!))
      .toEqual({ model: 'gpt-4.1' })
  })

  it('defaults missing runtime metadata to native without a provider override', () => {
    expect(normalizeRuntimeType(undefined)).toBe('native')
    expect(normalizeRuntimeConfig('native', null)).toBeNull()
  })

  it('retains existing DSH settings while normalizing its JSON object', () => {
    expect(normalizeRuntimeConfig('dsh', '{"command":"dsh","args":["run"]}'))
      .toBe('{\n  "command": "dsh",\n  "args": [\n    "run"\n  ]\n}')
    expect(normalizeRuntimeConfig('dsh', null)).toBe('{}')
  })

  it.each([
    ['malformed JSON', '{'],
    ['an unknown provider key', '{"provider":"openai"}'],
    ['a blank model override', '{"model":"  "}'],
  ])('rejects %s in Copilot runtime configuration', (_label, config) => {
    expect(() => normalizeRuntimeConfig('copilot', config)).toThrow()
  })
})
