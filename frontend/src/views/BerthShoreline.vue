<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { berthApi, craneApi } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const shoreline = ref([])
const cranes = ref([])
const selected = ref(null)
const schedules = ref([])
const drawer = ref(false)
const selectedCraneCodes = ref([])
const berthStatus = ref('IDLE')
const loading = ref(false)

const totalLength = computed(() => shoreline.value.reduce((sum, item) => sum + Number(item.berth.shorelineLength), 0) || 1)
const segments = computed(() => {
  let cursor = 34
  return shoreline.value.map(item => {
    const width = Number(item.berth.shorelineLength) / totalLength.value * 1020
    const result = { ...item, x: cursor, width }
    cursor += width
    return result
  })
})
const quayCranes = computed(() => cranes.value.filter(item => item.craneType === 'QUAY'))

async function load() {
  loading.value = true
  try {
    const [line, machines] = await Promise.all([berthApi.shoreline(), craneApi.efficiency()])
    shoreline.value = line.data
    cranes.value = machines.data
  } finally { loading.value = false }
}
async function openBerth(segment) {
  selected.value = segment.berth
  berthStatus.value = segment.berth.status
  const result = await berthApi.schedules(segment.berth.id)
  schedules.value = result.data
  selectedCraneCodes.value = (schedules.value[0]?.assignedCranes || '').split(',').filter(Boolean)
  drawer.value = true
}
async function assign(schedule) {
  try {
    await berthApi.assignCranes(schedule.id, selectedCraneCodes.value.join(','))
    ElMessage.success('岸桥分配已同步到船期')
    await openBerth({ berth: selected.value })
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
async function changeStatus() {
  try {
    await berthApi.setStatus(selected.value.id, berthStatus.value)
    ElMessage.success('泊位状态已更新')
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
async function depart(schedule) {
  try {
    await berthApi.depart(schedule.id)
    ElMessage.success('离港校验通过，泊位已释放')
    await openBerth({ berth: selected.value })
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
function statusName(status) { return ({ IDLE: '空闲', BERTHED: '在泊', MAINTENANCE: '维护' })[status] || status }
function dateText(value) { return value ? value.replace('T', ' ').slice(5, 16) : '--' }
onMounted(load)
</script>

<template>
  <section class="page-panel berth-page" v-loading="loading">
    <div class="panel-head">
      <div><div class="kicker">BERTH PLANNING / SHORELINE</div><h2>泊位岸线平面图</h2><p>岸线按实际长度比例展开，点击泊位段进入船期与岸桥编组</p></div>
      <div class="berth-metrics"><span>总岸线 <b>{{ totalLength }}</b> m</span><span>在泊船舶 <b>{{ shoreline.filter(x => x.berth.status === 'BERTHED').length }}</b> 艘</span></div>
    </div>

    <div class="shore-card glass-card">
      <div class="north-mark">N<br><i>↑</i></div>
      <svg class="shore-svg" viewBox="0 0 1100 500" role="img" aria-label="码头泊位岸线图">
        <defs>
          <pattern id="water" width="28" height="14" patternUnits="userSpaceOnUse"><path d="M0 7 Q7 1 14 7 T28 7" fill="none" stroke="#78bcb8" stroke-width="1" opacity=".28" /></pattern>
          <filter id="shadow"><feDropShadow dx="0" dy="5" stdDeviation="5" flood-opacity=".2" /></filter>
        </defs>
        <rect x="0" y="0" width="1100" height="500" fill="#dceeed" />
        <rect x="0" y="180" width="1100" height="320" fill="url(#water)" />
        <path d="M25 180 H1075" stroke="#173e3a" stroke-width="11" />
        <path d="M25 166 H1075" stroke="#d9bd67" stroke-width="4" stroke-dasharray="20 7" />

        <g v-for="segment in segments" :key="segment.berth.id" class="berth-segment" @click="openBerth(segment)">
          <rect :x="segment.x" y="116" :width="segment.width - 5" height="58" rx="2" :class="['apron', segment.berth.status.toLowerCase()]" />
          <line :x1="segment.x" y1="106" :x2="segment.x" y2="186" stroke="#294f4b" stroke-width="2" />
          <text :x="segment.x + 10" y="138" class="berth-code">{{ segment.berth.berthCode }}</text>
          <text :x="segment.x + 10" y="156" class="berth-size">{{ segment.berth.shorelineLength }}m · -{{ segment.berth.waterDepth }}m</text>
          <text :x="segment.x + segment.width - 17" y="151" class="status-label" text-anchor="end">{{ statusName(segment.berth.status) }}</text>

          <g v-if="segment.berth.status === 'BERTHED' && segment.activeSchedules[0]" filter="url(#shadow)">
            <path :d="`M${segment.x+18} 216 Q${segment.x+30} 192 ${segment.x+65} 192 H${segment.x+segment.width-52} Q${segment.x+segment.width-17} 202 ${segment.x+segment.width-12} 231 L${segment.x+segment.width-28} 282 H${segment.x+42} L${segment.x+18} 250 Z`" fill="#0c625a" stroke="#fff" stroke-width="3" />
            <rect :x="segment.x+58" y="210" :width="Math.max(60,segment.width-120)" height="38" rx="3" fill="#f0ce70" opacity=".92" />
            <line v-for="n in 5" :key="n" :x1="segment.x+55+n*(segment.width-120)/6" y1="210" :x2="segment.x+55+n*(segment.width-120)/6" y2="248" stroke="#54706b" stroke-width="2" opacity=".45" />
            <text :x="segment.x+segment.width/2" y="305" class="ship-name" text-anchor="middle">{{ segment.activeSchedules[0].vesselName }} / {{ segment.activeSchedules[0].voyageNo }}</text>
          </g>
        </g>

        <g v-for="x in [125,255,385,515,645,775,905]" :key="x" class="gantry">
          <path :d="`M${x} 102 V46 H${x+23} V102 M${x+5} 47 L${x+43} 17 M${x+23} 47 L${x+43} 17`" fill="none" stroke="#0c5b54" stroke-width="5" />
        </g>
        <text x="40" y="470" class="water-label">EAST TERMINAL BASIN / 东港池</text>
      </svg>
      <div class="shore-legend"><span><i class="berthing"></i>在泊</span><span><i class="idle"></i>空闲</span><span><i class="maintenance"></i>维护</span></div>
    </div>

    <el-drawer v-model="drawer" size="480px" :title="`${selected?.berthCode || ''} 泊位调度面板`">
      <div v-if="selected" class="berth-detail">
        <div class="berth-specs"><div><small>岸线长度</small><b>{{ selected.shorelineLength }} m</b></div><div><small>水深</small><b>{{ selected.waterDepth }} m</b></div><div><small>靠泊能力</small><b>{{ selected.capacityTeu }} TEU</b></div></div>
        <div class="status-control"><span>泊位当前状态</span><el-select v-model="berthStatus" @change="changeStatus"><el-option label="空闲" value="IDLE" /><el-option label="在泊" value="BERTHED" /><el-option label="维护" value="MAINTENANCE" /></el-select></div>
        <h3>船期窗口</h3>
        <div v-for="schedule in schedules" :key="schedule.id" class="schedule-sheet">
          <div class="sheet-top"><div><small>{{ schedule.shippingCompany }}</small><b>{{ schedule.vesselName }}</b><span>{{ schedule.voyageNo }}</span></div><em>{{ schedule.operationType }}</em></div>
          <div class="time-window"><span>ETA {{ dateText(schedule.expectedArrival) }}</span><i></i><span>ETD {{ dateText(schedule.expectedDeparture) }}</span></div>
          <div class="handling"><b>{{ schedule.handlingTeu }}</b> TEU <span>{{ schedule.status }}</span></div>
          <el-select v-model="selectedCraneCodes" multiple collapse-tags placeholder="分配岸桥" style="width:100%">
            <el-option v-for="crane in quayCranes" :key="crane.code" :label="`${crane.code} · ${crane.status}`" :value="crane.code" :disabled="crane.status === 'FAULT'" />
          </el-select>
          <div class="sheet-actions"><el-button size="small" type="primary" @click="assign(schedule)">保存岸桥编组</el-button><el-button size="small" plain @click="depart(schedule)">完成校验并离港</el-button></div>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.berth-metrics { display:flex; gap:8px; }.berth-metrics span { background:#fff;border:1px solid #cbdad7;padding:9px 12px;font-size:11px; }.berth-metrics b { color:#00695c;font-size:18px;margin:0 4px; }
.shore-card { position:relative;overflow:hidden; padding:10px; }
.shore-svg { width:100%; height:calc(100vh - 245px); min-height:430px; display:block; }
.berth-segment { cursor:pointer; }.berth-segment:hover .apron { filter:brightness(1.08);stroke:#f5c451;stroke-width:3; }
.apron { fill:#90aaa6; }.apron.berthed { fill:#16776e; }.apron.maintenance { fill:#7b5653; }
.berth-code { fill:white;font:bold 16px monospace; }.berth-size { fill:rgba(255,255,255,.72);font:10px monospace; }.status-label { fill:#fff;font:10px sans-serif; }
.ship-name { fill:#143c38;font:bold 13px sans-serif; }.water-label { fill:#558b86;font:11px monospace;letter-spacing:3px; }
.north-mark { position:absolute;right:24px;top:22px;z-index:2;color:#215c56;font:11px monospace;text-align:center; }.north-mark i { font-size:24px;font-style:normal; }
.shore-legend { position:absolute;left:24px;bottom:22px;background:rgba(255,255,255,.88);padding:9px 12px;display:flex;gap:14px;font-size:10px; }.shore-legend i { display:inline-block;width:10px;height:10px;margin-right:5px; }.shore-legend .berthing { background:#16776e; }.shore-legend .idle { background:#90aaa6; }.shore-legend .maintenance { background:#7b5653; }
.berth-specs { display:grid;grid-template-columns:repeat(3,1fr);gap:8px; }.berth-specs div { background:#eef5f3;padding:10px; }.berth-specs small,.berth-specs b { display:block; }.berth-specs small { color:#6c837f;font-size:10px; }.berth-specs b { margin-top:5px;color:#174a44;font-size:14px; }
.status-control { display:flex;align-items:center;justify-content:space-between;margin:15px 0;border:1px solid #d9e5e3;padding:9px 12px; }.status-control span { font-size:12px; }
h3 { font-size:14px;border-left:3px solid #f5c451;padding-left:8px; }
.schedule-sheet { border:1px solid #cddbd8;margin-top:10px;padding:13px;border-radius:5px; }.sheet-top { display:flex;justify-content:space-between; }.sheet-top small,.sheet-top b,.sheet-top span { display:block; }.sheet-top small { color:#708682; }.sheet-top b { font-size:17px;margin:4px 0; }.sheet-top span { font:11px monospace;color:#4e6c68; }.sheet-top em { font-style:normal;color:#00695c;background:#e5f1ef;height:fit-content;padding:4px 8px;font-size:10px; }
.time-window { display:flex;align-items:center;gap:8px;margin:13px 0;font:10px monospace;color:#56736e; }.time-window i { flex:1;border-top:1px dashed #8aa29e; }
.handling { margin-bottom:10px;font-size:11px; }.handling b { font-size:20px;color:#00695c; }.handling span { float:right;background:#163e3a;color:#fff;padding:4px 7px; }
.sheet-actions { display:flex;justify-content:flex-end;margin-top:10px; }
</style>
