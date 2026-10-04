<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { containerApi, yardApi } from '../api'

const props = defineProps({ yardSelection: { type: Object, required: true }, zones: { type: Array, default: () => [] } })
const emit = defineEmits(['selection-change'])
const slots = ref([])
const occupancy = ref([])
const containers = ref([])
const loading = ref(false)
const placeVisible = ref(false)
const actionVisible = ref(false)
const clickedSlot = ref(null)
const placeForm = reactive({ containerNo: '' })
const moveForm = reactive({ yardZone: 'A', bay: 1, row: 1, tier: 1 })

const currentZone = computed(() => props.zones.find(z => z.zoneCode === props.yardSelection.yardZone) || { rowCount: 6, tierCount: 4 })
const rowNumbers = computed(() => Array.from({ length: currentZone.value.rowCount || 1 }, (_, i) => i + 1))
const tierNumbers = computed(() => Array.from({ length: currentZone.value.tierCount || 1 }, (_, i) => currentZone.value.tierCount - i))
const occupancyItem = computed(() => occupancy.value.find(o => o.yardZone === props.yardSelection.yardZone) || { occupancyRate: 0, occupiedSlots: 0, totalSlots: 0 })
const availableContainers = computed(() => containers.value.filter(item => !item.currentYardZone))
const occupiedContainer = computed(() => containers.value.find(item => item.containerNo === clickedSlot.value?.occupiedContainerNo))
const containerMap = computed(() => Object.fromEntries(containers.value.map(item => [item.containerNo, item])))

function getSlot(tier, row) {
  return slots.value.find(slot => slot.id.tier === tier && slot.id.row === row)
}
function statusText(status) {
  return ({ EMPTY: '空', OCCUPIED: '占用', RESERVED: '预留', DISABLED: '禁用' })[status] || status
}
function isWide(slot) {
  const box = containerMap.value[slot?.occupiedContainerNo]
  return box && ['40GP', '40HQ', '45HC'].includes(box.type)
}
function isWideLead(slot) {
  if (!isWide(slot)) return false
  const before = getSlot(slot.id.tier, slot.id.row - 1)
  return before?.occupiedContainerNo !== slot.occupiedContainerNo
}
function isWideTail(slot) {
  if (!isWide(slot)) return false
  const before = getSlot(slot.id.tier, slot.id.row - 1)
  return before?.occupiedContainerNo === slot.occupiedContainerNo
}
function slotClass(slot) {
  return [slot?.status?.toLowerCase(), {
    selected: slot && slot.id.row === props.yardSelection.row && slot.id.tier === props.yardSelection.tier,
    'wide-lead': isWideLead(slot), 'wide-tail': isWideTail(slot)
  }]
}
async function load() {
  loading.value = true
  try {
    const [matrixResult, occupancyResult, containerResult] = await Promise.all([
      yardApi.matrix(props.yardSelection.yardZone, props.yardSelection.bay),
      yardApi.occupancy(), containerApi.list()
    ])
    slots.value = matrixResult.data
    occupancy.value = occupancyResult.data
    containers.value = containerResult.data
  } finally { loading.value = false }
}
function chooseZone(zoneCode) {
  emit('selection-change', { yardZone: zoneCode, bay: 1, row: 1, tier: 1 })
}
function clickSlot(slot) {
  if (!slot) return
  clickedSlot.value = slot
  emit('selection-change', { yardZone: slot.id.yardZone, bay: slot.id.bay, row: slot.id.row, tier: slot.id.tier })
  if (slot.status === 'EMPTY') {
    placeForm.containerNo = availableContainers.value[0]?.containerNo || ''
    placeVisible.value = true
  } else if (slot.status === 'OCCUPIED') {
    Object.assign(moveForm, { yardZone: slot.id.yardZone, bay: slot.id.bay, row: slot.id.row, tier: Math.min(slot.id.tier + 1, currentZone.value.tierCount) })
    actionVisible.value = true
  } else {
    ElMessage.warning(`该箱位当前为${statusText(slot.status)}状态`)
  }
}
async function place() {
  if (!placeForm.containerNo) return ElMessage.warning('请选择待落位箱号')
  try {
    await yardApi.place({ containerNo: placeForm.containerNo, ...clickedSlot.value.id })
    ElMessage.success('放箱成功，箱位矩阵已刷新')
    placeVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
async function relocate() {
  try {
    await yardApi.relocate({ containerNo: clickedSlot.value.occupiedContainerNo, ...moveForm })
    ElMessage.success('翻倒移箱完成')
    actionVisible.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
async function pickup() {
  try {
    await ElMessageBox.confirm(`确认放行 ${clickedSlot.value.occupiedContainerNo}？系统将校验可提与付费查验标记。`, '提箱放行', { type: 'warning' })
    await yardApi.pickup(clickedSlot.value.occupiedContainerNo)
    ElMessage.success('提箱出场完成')
    actionVisible.value = false
    await load()
  } catch (error) { if (error !== 'cancel') ElMessage.error(error.message || error) }
}

watch(() => [props.yardSelection.yardZone, props.yardSelection.bay], load)
onMounted(load)
</script>

<template>
  <section class="page-panel yard-page" v-loading="loading">
    <div class="panel-head">
      <div><div class="kicker">YARD SLOT MATRIX / LIVE</div><h2>堆场箱位平面矩阵</h2><p>点击箱位直接发起落位、翻倒或提箱，坐标由左侧贝位塔联动锁定</p></div>
      <div class="zone-switch">
        <button v-for="zone in zones" :key="zone.zoneCode" :class="{ active: yardSelection.yardZone === zone.zoneCode }" @click="chooseZone(zone.zoneCode)">
          <b>{{ zone.zoneCode }}区</b><small>{{ zone.zoneType }}</small>
        </button>
      </div>
    </div>

    <div class="yard-summary glass-card">
      <div class="occupancy-dial"><span :style="{ '--rate': `${occupancyItem.occupancyRate * 3.6}deg` }"><b>{{ occupancyItem.occupancyRate.toFixed(1) }}%</b></span><small>箱区占用率</small></div>
      <div class="yard-ident"><strong>{{ yardSelection.yardZone }}区 · 贝 {{ String(yardSelection.bay).padStart(2, '0') }}</strong><span>{{ currentZone.containerTypeLimit }} · {{ currentZone.powerOutlet ? '配备冷藏电源' : '无冷藏电源' }}</span></div>
      <div class="legend"><i class="empty"></i>空 <i class="occupied"></i>占用 <i class="reserved"></i>预留 <i class="disabled"></i>禁用 <span class="snow">❄</span>冷藏位</div>
      <div class="slot-count"><b>{{ occupancyItem.occupiedSlots }}</b><span>/ {{ occupancyItem.totalSlots }} 箱位</span></div>
    </div>

    <div class="matrix-shell glass-card">
      <div class="axis-title vertical">层 TIER</div>
      <div class="matrix-content">
        <div class="column-axis"><span v-for="row in rowNumbers" :key="row">列 {{ String(row).padStart(2, '0') }}</span></div>
        <div class="slot-matrix" :style="{ gridTemplateColumns: `repeat(${rowNumbers.length}, minmax(92px, 1fr))` }">
          <template v-for="tier in tierNumbers" :key="tier">
            <button v-for="row in rowNumbers" :key="`${tier}-${row}`" :class="['slot-cell', slotClass(getSlot(tier,row))]" @click="clickSlot(getSlot(tier,row))">
              <template v-if="getSlot(tier,row)">
                <span class="slot-code">{{ yardSelection.yardZone }}{{ String(yardSelection.bay).padStart(2,'0') }}-{{ String(row).padStart(2,'0') }}-{{ tier }}</span>
                <b v-if="getSlot(tier,row).occupiedContainerNo">{{ getSlot(tier,row).occupiedContainerNo }}</b>
                <b v-else>{{ statusText(getSlot(tier,row).status) }}</b>
                <small v-if="getSlot(tier,row).refrigerated" class="snow">❄</small>
                <em v-if="isWideLead(getSlot(tier,row))">40'</em>
              </template>
            </button>
          </template>
        </div>
        <div class="tier-labels"><span v-for="tier in tierNumbers" :key="tier">T{{ tier }}</span></div>
      </div>
    </div>

    <el-dialog v-model="placeVisible" width="440" title="空箱位 · 放箱校验">
      <div class="target-coordinate">目标箱位 <b>{{ clickedSlot?.id.yardZone }} / {{ clickedSlot?.id.bay }} / {{ clickedSlot?.id.row }} / {{ clickedSlot?.id.tier }}</b></div>
      <el-form label-position="top">
        <el-form-item label="待落位集装箱">
          <el-select v-model="placeForm.containerNo" style="width:100%" placeholder="选择尚未落位的箱号">
            <el-option v-for="box in availableContainers" :key="box.containerNo" :value="box.containerNo" :label="`${box.containerNo} · ${box.type} · ${box.weight}t`" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="rule-note">系统将自动校验箱型限制、冷藏电源、危险品专区及 40 尺双箱位。</div>
      <template #footer><el-button @click="placeVisible=false">取消</el-button><el-button type="primary" @click="place">校验并放箱</el-button></template>
    </el-dialog>

    <el-dialog v-model="actionVisible" width="500" title="占用箱位 · 作业操作">
      <div class="container-ticket" v-if="occupiedContainer">
        <div><small>CONTAINER</small><b>{{ occupiedContainer.containerNo }}</b></div>
        <span>{{ occupiedContainer.type }}</span><span>{{ occupiedContainer.loadStatus === 'HEAVY' ? '重箱' : '空箱' }}</span><span>{{ occupiedContainer.weight }} t</span>
      </div>
      <el-divider content-position="left">翻倒目标箱位</el-divider>
      <div class="move-coordinate">
        <el-select v-model="moveForm.yardZone"><el-option v-for="z in zones" :key="z.zoneCode" :label="`${z.zoneCode}区`" :value="z.zoneCode" /></el-select>
        <el-input-number v-model="moveForm.bay" :min="1" :max="3" controls-position="right" />
        <el-input-number v-model="moveForm.row" :min="1" :max="6" controls-position="right" />
        <el-input-number v-model="moveForm.tier" :min="1" :max="4" controls-position="right" />
      </div>
      <div class="dialog-actions"><el-button type="primary" @click="relocate">执行翻倒移箱</el-button><el-button type="danger" plain @click="pickup">校验并提箱</el-button></div>
    </el-dialog>
  </section>
</template>

<style scoped>
.zone-switch { display: flex; gap: 7px; }
.zone-switch button { min-width: 86px; border: 1px solid #b9cfcb; background: #fff; color: #456762; padding: 7px 10px; border-radius: 4px; }
.zone-switch button b, .zone-switch button small { display: block; }
.zone-switch button small { font-size: 9px; margin-top: 2px; opacity: .7; }
.zone-switch button.active { background: #00695c; color: white; border-color: #00695c; }
.yard-summary { min-height: 86px; padding: 12px 18px; display: flex; align-items: center; gap: 19px; }
.occupancy-dial { display: flex; align-items: center; gap: 9px; }
.occupancy-dial > span { width: 58px; height: 58px; display: grid; place-items: center; border-radius: 50%; background: conic-gradient(#008f7e var(--rate), #e2ecea 0); position: relative; }
.occupancy-dial > span::after { content:""; position:absolute; inset:6px; border-radius:50%; background:white; }
.occupancy-dial b { z-index:1; font-size:12px; }
.occupancy-dial small { color:#738985; }
.yard-ident { flex: 1; padding-left: 18px; border-left: 1px solid #dce7e5; }
.yard-ident strong, .yard-ident span { display:block; }
.yard-ident strong { font-size:18px; }
.yard-ident span { color:#6a817d; font-size:11px; margin-top:5px; }
.legend { display:flex; gap:7px; align-items:center; font-size:11px; color:#617571; }
.legend i { width:13px; height:13px; display:inline-block; border-radius:2px; margin-left:7px; }
.legend .empty { background:#e5eae9; } .legend .occupied { background:#087a70; } .legend .reserved { background:#e8b53f; } .legend .disabled { background:repeating-linear-gradient(135deg,#9e3838 0 4px,#311f1f 4px 8px); }
.snow { color:#69c9ee; font-style:normal; }
.slot-count { text-align:right; min-width:100px; }.slot-count b { font-size:25px;color:#00695c; }.slot-count span { display:block;font-size:10px;color:#849793; }
.matrix-shell { margin-top:14px; padding:18px 20px 21px 50px; position:relative; overflow:hidden; background:linear-gradient(#fff,#f6faf9); }
.axis-title.vertical { position:absolute; left:13px; top:50%; transform:rotate(-90deg) translateX(-50%); transform-origin:left top; font:9px monospace; letter-spacing:2px;color:#78908c; }
.matrix-content { position:relative; padding-right:45px; }
.column-axis { display:grid; grid-template-columns:repeat(v-bind('rowNumbers.length'), minmax(92px,1fr)); gap:9px; margin-bottom:8px; }
.column-axis span { text-align:center; font:10px monospace; color:#728682; }
.slot-matrix { display:grid; gap:9px; position:relative; }
.slot-cell { height:76px; position:relative; overflow:visible; border:1px solid #cad5d3; border-radius:4px; background:#e7eceb; color:#70827f; text-align:left; padding:9px; }
.slot-cell:hover { transform:translateY(-2px); box-shadow:0 7px 14px rgba(28,78,71,.13); z-index:3; }
.slot-cell.selected { outline:3px solid #f5c451; outline-offset:2px; }
.slot-cell.occupied { background:linear-gradient(135deg,#00695c,#00897b); color:white; border-color:#00554c; }
.slot-cell.reserved { background:#f4d47d; color:#634b12; border-color:#d6a628; }
.slot-cell.disabled { background:repeating-linear-gradient(135deg,#713535 0 8px,#332626 8px 16px); color:#f1dcdc; border-color:#4e2b2b; }
.slot-cell.wide-lead { width:calc(200% + 9px); z-index:2; background:linear-gradient(90deg,#00695c,#0b9385); }
.slot-cell.wide-tail { opacity:0; pointer-events:none; }
.slot-code { display:block; font:9px monospace; opacity:.72; }
.slot-cell b { display:block; font:11px monospace; margin-top:14px; white-space:nowrap; }
.slot-cell small.snow { position:absolute; right:7px; top:6px; color:#b5efff; font-size:15px; }
.slot-cell em { position:absolute; right:10px; bottom:7px; color:#f7d469; font:bold 12px monospace; }
.tier-labels { position:absolute; right:0; top:18px; height:calc(100% - 18px); display:grid; grid-template-rows:repeat(v-bind('tierNumbers.length'),76px); gap:9px; }
.tier-labels span { display:grid; place-items:center; width:31px; background:#163e3a; color:#dcebea; font:10px monospace; border-radius:3px; }
.target-coordinate { background:#eef5f3; border-left:3px solid #00695c; padding:10px; margin-bottom:14px; }
.rule-note { color:#788d89;font-size:11px;background:#f7f9f8;padding:9px; }
.container-ticket { background:#0f3733;color:white;padding:14px;display:flex;gap:12px;align-items:center;border-radius:5px; }
.container-ticket div { flex:1; }.container-ticket small,.container-ticket b { display:block; }.container-ticket small { font:9px monospace;color:#82bdb5; }.container-ticket b { margin-top:4px;font:18px monospace; }.container-ticket span { background:#25514c;padding:6px 8px;font-size:11px; }
.move-coordinate { display:grid;grid-template-columns:1.3fr 1fr 1fr 1fr;gap:7px; }.dialog-actions { display:flex;justify-content:flex-end;margin-top:18px; }
</style>
