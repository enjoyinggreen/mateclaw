import type { AgentRuntimeType } from '@/types'

export function normalizeRuntimeType(runtimeType: unknown): AgentRuntimeType {
  if (typeof runtimeType !== 'string') return 'native'
  const normalized = runtimeType.trim().toLowerCase()
  return normalized === 'dsh' || normalized === 'copilot' ? normalized : 'native'
}

export function normalizeRuntimeConfig(
  runtimeType: AgentRuntimeType,
  runtimeConfig: string | null | undefined,
): string | null {
  if (runtimeType === 'native') return null
  if (!runtimeConfig?.trim()) return runtimeType === 'dsh' ? '{}' : null

  const parsed: unknown = JSON.parse(runtimeConfig)
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('Runtime configuration must be a JSON object')
  }

  if (runtimeType === 'dsh') {
    return JSON.stringify(parsed, null, 2)
  }

  const config = parsed as Record<string, unknown>
  const keys = Object.keys(config)
  if (keys.length === 0) return null
  if (keys.length !== 1 || keys[0] !== 'model'
      || typeof config.model !== 'string' || !config.model.trim()) {
    throw new Error('Copilot configuration accepts only a nonblank model override')
  }
  return JSON.stringify({ model: config.model.trim() })
}

export function copilotModelOverride(runtimeConfig: string | null | undefined): string | null {
  const normalized = normalizeRuntimeConfig('copilot', runtimeConfig)
  return normalized ? (JSON.parse(normalized) as { model: string }).model : null
}
