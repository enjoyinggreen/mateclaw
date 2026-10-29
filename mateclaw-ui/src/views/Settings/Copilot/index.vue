<template>
  <section v-if="isGlobalAdmin" class="copilot-settings">
    <header class="section-header">
      <div>
        <div class="mc-page-kicker">Runtime management</div>
        <h2 class="section-title">{{ t('settings.copilot.title', 'GitHub Copilot runtime') }}</h2>
        <p class="section-desc">{{ t('settings.copilot.description', 'Configure and verify the isolated Copilot runtime. Verification does not send a prompt.') }}</p>
      </div>
      <span v-if="status" class="state-pill" :class="`state-${status.readiness.state}`">
        {{ t(`settings.copilot.${status.readiness.state}`, status.readiness.state) }}
      </span>
    </header>

    <p v-if="error" class="settings-card error-card" role="alert">{{ error }}</p>
    <p v-if="loading" class="settings-card loading-card" role="status">
      {{ t('settings.copilot.loading', 'Loading Copilot status...') }}
    </p>

    <div v-else-if="status" class="settings-card">
      <h3>Runtime configuration</h3>
      <p v-if="status.readiness.message" role="status">{{ status.readiness.message }}</p>
      <form @submit.prevent="save">
        <label>
          <input v-model="form.enabled" type="checkbox" />
          {{ t('settings.copilot.enabled', 'Enable Copilot runtime') }}
        </label>
        <label>
          <span>{{ t('settings.copilot.cliPath', 'Executable path') }}</span>
          <input v-model="form.cliPath" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('settings.copilot.modelName', 'Model name') }}</span>
          <input v-model="form.modelName" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('settings.copilot.githubToken', 'GitHub token') }}</span>
          <input
            v-model="form.githubToken"
            type="password"
            autocomplete="new-password"
            :placeholder="t('settings.copilot.tokenPreserved', 'Leave blank to preserve the managed token.')"
            @input="form.clearToken = false"
          />
        </label>
        <p v-if="status.configuration.tokenConfigured">
          {{ t('settings.copilot.tokenConfigured', 'Token configured') }}
        </p>
        <label v-if="status.configuration.tokenConfigured">
          <input v-model="form.clearToken" type="checkbox" />
          {{ t('settings.copilot.clearToken', 'Clear managed token') }}
        </label>

        <fieldset>
          <legend>Unsupported capabilities</legend>
          <label>
            <input
              type="checkbox"
              disabled
              :aria-label="t('settings.copilot.approvalsUnavailable', 'Approvals unavailable')"
            />
            {{ t('settings.copilot.approvalsUnavailable', 'Approvals unavailable') }}
          </label>
          <label>
            <input
              type="checkbox"
              disabled
              :aria-label="t('settings.copilot.delegationUnavailable', 'Delegated execution unavailable')"
            />
            {{ t('settings.copilot.delegationUnavailable', 'Delegated execution unavailable') }}
          </label>
        </fieldset>

        <div class="actions">
          <button type="submit" :disabled="busy !== ''">
            {{ t('settings.copilot.save', 'Save configuration') }}
          </button>
          <button type="button" :disabled="busy !== ''" @click="verify">
            {{ t('settings.copilot.verify', 'Verify readiness') }}
          </button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { copilotApi, type CopilotRuntimeStatus } from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const workspace = useWorkspaceStore()
const isGlobalAdmin = computed(() => workspace.isGlobalAdmin)
const loading = ref(true)
const busy = ref<'save' | 'verify' | ''>('')
const error = ref('')
const status = ref<CopilotRuntimeStatus | null>(null)
const form = reactive({
  enabled: false,
  cliPath: '',
  modelName: '',
  githubToken: '',
  clearToken: false,
})

function applyStatus(response: { data?: CopilotRuntimeStatus } | CopilotRuntimeStatus) {
  const data = 'data' in response && response.data ? response.data : response as CopilotRuntimeStatus
  status.value = data
  form.enabled = data.enabled
  form.cliPath = data.configuration.cliPath
  form.modelName = data.configuration.modelName
  form.githubToken = ''
  form.clearToken = false
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    applyStatus(await copilotApi.status())
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

async function save() {
  busy.value = 'save'
  error.value = ''
  const payload = {
    enabled: form.enabled,
    cliPath: form.cliPath,
    modelName: form.modelName,
    clearToken: form.clearToken,
    ...(form.githubToken.trim() ? { githubToken: form.githubToken.trim() } : {}),
  }
  try {
    applyStatus(await copilotApi.saveConfig(payload))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    busy.value = ''
  }
}

async function verify() {
  busy.value = 'verify'
  error.value = ''
  try {
    applyStatus(await copilotApi.verify())
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    busy.value = ''
  }
}

onMounted(() => {
  if (isGlobalAdmin.value) void load()
})
</script>

<style scoped>
.copilot-settings { display: grid; gap: 16px; }
.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.section-title { margin: 0; }
.section-desc { color: var(--mc-text-secondary); }
.settings-card { display: grid; gap: 14px; }
form, fieldset { display: grid; gap: 12px; }
form > label { display: grid; gap: 6px; }
fieldset { border: 1px solid var(--mc-border-light); border-radius: 8px; }
.actions { display: flex; gap: 10px; }
</style>
