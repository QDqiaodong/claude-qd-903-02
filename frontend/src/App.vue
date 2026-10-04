<script setup>
import { computed, onMounted, reactive, shallowRef } from 'vue'
import router from './router'
import { yardApi } from './api'
import YardMatrix from './views/YardMatrix.vue'

const currentRoute = shallowRef(router.resolve(window.location.pathname))
const zones = shallowRef([])
const selection = reactive({ yardZone: 'A', bay: 1, row: 1, tier: 1 })

const processNodes = [
  { path: '/berths', code: '01', title: '泊位船期', note: '靠泊计划' },
  { path: '/cranes', code: '02', title: '岸桥作业', note: '装卸效率' },
  { path: '/yard', code: '03', title: '堆场箱位', note: '落位翻倒' },
  { path: '/gates', code: '04', title: '闸口放行', note: '收提箱' },
  { path: '/takeover', code: '05', title: '故障接管', note: '机械接管台' }
]

const currentComponent = computed(() => currentRoute.value.matched.at(-1)?.components?.default || YardMatrix)
const selectedZone = computed(() => zones.value.find(item => item.zoneCode === selection.yardZone) || { bayCount: 1, rowCount: 1, tierCount: 1 })
const bays = computed(() => Array.from({ length: selectedZone.value.bayCount || 1 }, (_, i) => i + 1))
const rows = computed(() => Array.from({ length: selectedZone.value.rowCount || 1 }, (_, i) => i + 1))
const tiers = computed(() => Array.from({ length: selectedZone.value.tierCount || 1 }, (_, i) => i + 1))

router.afterEach(to => { currentRoute.value = to })

async function navigate(path, replace = false) {
  await (replace ? router.replace(path) : router.push(path))
}

function choose(level, value) {
  selection[level] = value
  if (level === 'yardZone') Object.assign(selection, { bay: 1, row: 1, tier: 1 })
  if (level === 'bay') Object.assign(selection, { row: 1, tier: 1 })
  if (level === 'row') selection.tier = 1
}

function applySelection(next) {
  Object.assign(selection, next)
}

onMounted(async () => {
  const result = await yardApi.zones()
  zones.value = result.data
  const path = window.location.pathname === '/' ? '/yard' : window.location.pathname
  await navigate(path, true)
})
</script>

<template>
  <div class="terminal-shell">
    <header class="command-header">
      <div class="brand-lockup">
        <div class="brand-mark"><span></span><span></span><span></span></div>
        <div>
          <div class="eyebrow">PORT OPERATIONS / TOS</div>
          <h1>集装箱码头 · 泊位与堆场箱位管理</h1>
        </div>
      </div>
      <div class="process-route" aria-label="生产链路导航">
        <template v-for="(item, index) in processNodes" :key="item.path">
          <button :class="['process-node', { active: currentRoute.path === item.path }]" @click="navigate(item.path)">
            <span class="node-code">{{ item.code }}</span>
            <span><b>{{ item.title }}</b><small>{{ item.note }}</small></span>
          </button>
          <span v-if="index < processNodes.length - 1" class="route-arrow">→</span>
        </template>
      </div>
      <div class="live-badge"><i></i> 实时生产态</div>
    </header>

    <div class="workspace">
      <aside class="bay-tower">
        <div class="tower-title"><span>贝位塔</span><small>BAY TOWER</small></div>

        <section class="tower-level">
          <label>01 / 箱区</label>
          <div class="vertical-buttons zone-buttons">
            <button v-for="zone in zones" :key="zone.zoneCode" :class="{ active: selection.yardZone === zone.zoneCode }" @click="choose('yardZone', zone.zoneCode)">
              {{ zone.zoneCode }}<small>{{ zone.powerOutlet ? '冷' : zone.zoneType.slice(0, 1) }}</small>
            </button>
          </div>
        </section>

        <section class="tower-level" :class="{ unlocked: selection.yardZone }">
          <label>02 / 贝位</label>
          <div class="vertical-buttons compact">
            <button v-for="bay in bays" :key="bay" :class="{ active: selection.bay === bay }" @click="choose('bay', bay)">{{ String(bay).padStart(2, '0') }}</button>
          </div>
        </section>

        <section class="tower-level" :class="{ unlocked: selection.bay }">
          <label>03 / 列</label>
          <div class="vertical-buttons compact grid-two">
            <button v-for="row in rows" :key="row" :class="{ active: selection.row === row }" @click="choose('row', row)">{{ String(row).padStart(2, '0') }}</button>
          </div>
        </section>

        <section class="tower-level" :class="{ unlocked: selection.row }">
          <label>04 / 层</label>
          <div class="vertical-buttons compact">
            <button v-for="tier in tiers" :key="tier" :class="{ active: selection.tier === tier }" @click="choose('tier', tier)">T{{ tier }}</button>
          </div>
        </section>
        <div class="tower-base">YARD · {{ selection.yardZone }}</div>
      </aside>

      <main class="content-stage">
        <div class="coordinate-bar">
          <div class="crumbs">
            <span>{{ selection.yardZone }}区</span><em>/</em>
            <span>贝 {{ String(selection.bay).padStart(2, '0') }}</span><em>/</em>
            <span>列 {{ String(selection.row).padStart(2, '0') }}</span><em>/</em>
            <strong>层 {{ selection.tier }}</strong>
          </div>
          <div class="view-name">{{ currentRoute.meta?.title }}</div>
          <div class="clock-code">TERMINAL 903</div>
        </div>
        <component :is="currentComponent" :yard-selection="selection" :zones="zones" @selection-change="applySelection" />
      </main>
    </div>
  </div>
</template>
