<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { craneApi, operationApi } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const router = useRouter()
const cranes = ref([])
const selected = ref(null)
const shifts = ref([])
const dialog = ref(false)
const loading = ref(false)
const form = reactive({ driverName: '', shiftStart: '', shiftEnd: '', handledBoxes: 0, workHours: 1 })
const statusName = { WORKING: '作业', STANDBY: '待机', FAULT: '故障' }

// ---------- 动态优先级派发队列 ----------
const TYPE_NAME = { UNLOAD: '卸船', LOAD: '装船', SHIFT: '翻倒', PICKUP: '提箱' }
const PRIORITY_NAME = { NORMAL: '普通', URGENT: '加急', VESSEL_CUTOFF: '截关' }
const CATEGORY_META = {
  PAUSED: { name: '已暂停', desc: '故障接管冻结中，需到故障接管台处置，不能派发' },
  FAULT_CRANE: { name: '归属机械故障', desc: '已停止派入新任务，存量指令需经故障接管台处置' },
  NO_SCHEDULE: { name: '船期缺失', desc: '无法评估离港风险，补录船期后自动回到推荐队列' },
  NO_CRANE: { name: '无匹配机械', desc: '当前没有可用机械（故障 / 负荷 / 区域 / 额定），恢复后自动回到队列' },
  IN_PROGRESS: { name: '执行中', desc: '机械作业中，无需再派' },
  COMPLETED: { name: '已完成', desc: '已完结留档，不参与派发' }
}

const queue = ref(null)
const queueLoading = ref(false)
const strategy = ref('DEPARTURE_FIRST')
const page = ref(1)
const size = ref(8)
const asOf = ref(null)        // 计算时点：翻页/切策略时回传，保证同一时点顺序稳定
const stale = ref(false)
const dispatchDialog = ref(false)
const dispatchItem = ref(null)
const dispatchCrane = ref('')
const dispatching = ref(false)
let pollTimer = null
let tickTimer = null

const classifiedGroups = computed(() => {
  if (!queue.value) return []
  return Object.keys(CATEGORY_META)
    .map(code => ({
      code,
      ...CATEGORY_META[code],
      items: queue.value.classified?.[code] || [],
      total: queue.value.classifiedTotals?.[code] || 0
    }))
    .filter(group => group.total > 0)
})
const chosenCandidate = computed(() =>
  dispatchItem.value?.candidates?.find(c => c.code === dispatchCrane.value) || null)

async function loadQueue(resetPoint = false) {
  if (resetPoint) asOf.value = null
  queueLoading.value = true
  try {
    const result = await operationApi.dispatchQueue(strategy.value, page.value - 1, size.value, asOf.value)
    const data = result.data
    if (queue.value && data.fingerprint !== queue.value.fingerprint && !resetPoint) {
      // 翻页 / 切策略途中船期、机械或指令已变化：标识过期并以新计算时点整体重算
      stale.value = true
      ElMessage.warning('船期 / 机械 / 指令状态已变化，推荐已过期，正在按新计算时点重算')
      return await loadQueue(true)
    }
    queue.value = data
    asOf.value = data.computedAt
    stale.value = false
  } catch (error) {
    ElMessage.error(error.message)
  } finally { queueLoading.value = false }
}
function switchStrategy() { page.value = 1; loadQueue() }
function changePage(next) { page.value = next; loadQueue() }
function changeSize(next) { size.value = next; page.value = 1; loadQueue() }

async function pollFingerprint() {
  if (!queue.value) return
  try {
    const result = await operationApi.dispatchFingerprint()
    if (result.data.fingerprint !== queue.value.fingerprint) {
      stale.value = true
      ElMessage.warning('检测到船期 / 机械 / 指令变化，推荐已过期，已重新计算')
      await loadQueue(true)
    }
  } catch { /* 轮询失败静默，下个周期再试 */ }
}

function openDispatch(item) {
  dispatchItem.value = item
  const eligible = item.candidates.filter(c => c.eligible)
  dispatchCrane.value = (eligible.find(c => c.current) || eligible[0])?.code || ''
  dispatchDialog.value = true
}
async function confirmDispatch() {
  if (!dispatchCrane.value) { ElMessage.warning('请选择派发机械'); return }
  dispatching.value = true
  try {
    const result = await operationApi.dispatch(dispatchItem.value.instructionId, {
      craneCode: dispatchCrane.value,
      expectedVersion: dispatchItem.value.version
    })
    ElMessage.success(`指令 ${dispatchItem.value.instructionNo} 已派往 ${result.data.craneCode}`)
    dispatchDialog.value = false
    await loadQueue(true)
    await load()
  } catch (error) {
    if (error.status === 409) {
      // 他人已先派发：响应携带最新指令，直接告知最新去向并刷新队列
      const latest = error.data
      ElMessageBox.alert(
        `该指令已被其他调度员更新${latest?.craneCode ? `，最新去向：${latest.craneCode}` : ''}。本次派发未生效，不会留下重复指派，队列已刷新为最新状态。`,
        '派发冲突',
        { confirmButtonText: '知道了', type: 'warning' }
      )
      dispatchDialog.value = false
      await loadQueue(true)
    } else {
      ElMessage.error(error.message)
      await loadQueue(true)
    }
  } finally { dispatching.value = false }
}

function timeText(value) { return value ? String(value).replace('T', ' ').slice(5, 16) : '--' }
function durationText(minutes) {
  if (minutes == null) return '--'
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}
function percent(weight) { return `${Math.round(Number(weight) * 100)}%` }
function candidateTitle(candidate) {
  const failed = candidate.checks.filter(check => !check.pass)
  return failed.length ? failed.map(check => `${check.name}：${check.note}`).join('\n') : '可派发'
}

async function load() {
  loading.value = true
  try { cranes.value = (await craneApi.efficiency()).data } finally { loading.value = false }
}
async function openCard(crane) {
  selected.value = crane
  shifts.value = (await craneApi.shifts(crane.id)).data
  const now = new Date(); const end = new Date(now.getTime() + 60 * 60 * 1000)
  Object.assign(form, { driverName: '', shiftStart: local(now), shiftEnd: local(end), handledBoxes: 28, workHours: 1 })
  dialog.value = true
}
function local(date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date - offset).toISOString().slice(0, 19)
}
function points(values) {
  const data = values?.length ? values : [0, 0]
  const max = Math.max(...data, 1); const step = 170 / Math.max(data.length - 1, 1)
  return data.map((v, index) => `${5 + index * step},${54 - v / max * 42}`).join(' ')
}
function efficiencyPercent(crane) { return Math.min(100, Number(crane.actualEfficiency || 0) / crane.ratedEfficiency * 100) }
async function register() {
  try {
    await craneApi.registerShift(selected.value.id, form)
    ElMessage.success('司机班次与作业量已登记')
    dialog.value = false
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
async function changeStatus(status) {
  try {
    await craneApi.setStatus(selected.value.id, status)
    selected.value.status = status
    ElMessage.success('机械状态已更新')
    if (status === 'FAULT') {
      ElMessage.warning('已生成故障接管方案并停止派入新任务，请前往「故障接管」台处置关联指令')
    }
    await load()
  } catch (error) { ElMessage.error(error.message) }
}
onMounted(() => {
  load()
  loadQueue(true)
  pollTimer = setInterval(pollFingerprint, 5000)   // 船期/机械/指令变化 → 标识过期并重算
  tickTimer = setInterval(() => loadQueue(true), 60000)  // 等待时长随时间推进，静默重算
})
onUnmounted(() => { clearInterval(pollTimer); clearInterval(tickTimer) })
</script>

<template>
  <section class="page-panel crane-page" v-loading="loading">
    <div class="panel-head">
      <div><div class="kicker">CRANE PRODUCTIVITY / 8H TREND</div><h2>岸桥与场桥作业效率</h2><p>自然箱/小时对比额定效率，点击机械卡登记司机班次和小时作业量</p></div>
      <div class="shift-banner"><span>当前工班</span><b>白班 · 08:00—20:00</b><i>生产调度中</i></div>
    </div>

    <div class="queue-console glass-card">
      <div class="queue-head">
        <div class="queue-title">
          <div class="kicker">DYNAMIC PRIORITY QUEUE</div>
          <h3>动态优先级派发队列</h3>
          <p>未完成作业指令 × 船舶离港时间 × 所需机械类型 × 实时可用机械；队列仅为建议，派发时系统重新核对指令阶段 / 机械状态 / 已接负荷</p>
        </div>
        <el-radio-group v-model="strategy" @change="switchStrategy">
          <el-radio-button value="DEPARTURE_FIRST">离港优先</el-radio-button>
          <el-radio-button value="FAIR_WAIT">公平等待</el-radio-button>
        </el-radio-group>
        <div class="queue-meta" v-if="queue">
          <span>计算时点 <b>{{ timeText(queue.computedAt) }}</b></span>
          <span>数据指纹 <b>{{ queue.fingerprint?.slice(0, 8) }}</b></span>
          <el-button size="small" @click="loadQueue(true)">刷新重算</el-button>
        </div>
      </div>
      <div class="strategy-note" v-if="queue">
        <span><b>{{ queue.strategyName }}</b>：{{ queue.strategyDescription }}</span>
        <span>权重：离港 {{ percent(queue.weights.departure) }} / 优先级 {{ percent(queue.weights.priority) }} / 等待 {{ percent(queue.weights.wait) }}</span>
        <span>{{ queue.tieBreak }}</span>
      </div>
      <div v-if="stale" class="stale-banner">推荐已过期：船期 / 机械 / 指令状态发生变化，正在按最新数据重算…</div>

      <div class="queue-list" v-loading="queueLoading">
        <div v-if="queue && !queue.items.length" class="empty-queue">当前没有可派发指令（其余指令已在下方分类说明中列出）</div>
        <article v-for="item in queue?.items || []" :key="item.instructionId" class="queue-row">
          <div :class="['rank-badge', { urgent: item.tier === 'URGENT' }]">{{ item.rank }}</div>
          <div class="qi-main">
            <div class="qi-top">
              <b>{{ item.instructionNo }}</b>
              <span class="tag">{{ TYPE_NAME[item.instructionType] }}</span>
              <span :class="['tag', 'prio', (item.priority || '').toLowerCase()]">{{ PRIORITY_NAME[item.priority] || item.priority }}</span>
              <span class="mono">{{ item.containerNo }}</span>
              <span class="need">需{{ item.requiredCraneType === 'QUAY' ? '岸桥' : '场桥' }}</span>
              <span v-if="item.craneCode" class="current-crane">当前 {{ item.craneCode }}</span>
            </div>
            <div class="qi-vessel">
              <span v-if="item.vesselName">{{ item.vesselName }} / {{ item.voyageNo }} · ETD {{ timeText(item.expectedDeparture) }}</span>
              <em :class="['risk', (item.riskBand || '').toLowerCase()]">船期风险 {{ item.riskBandName }}</em>
              <span class="wait">已等待 {{ durationText(item.waitMinutes) }}</span>
            </div>
            <div class="qi-reasons"><span v-for="(reason, i) in item.reasons" :key="i">{{ reason }}</span></div>
            <div class="qi-score">
              <div class="score-track"><span :style="{ width: `${Math.min(100, item.score)}%` }"></span></div>
              <small>加权 {{ item.score.toFixed(1) }}（离港 {{ item.scoreParts.departure.toFixed(0) }} · 优先级 {{ item.scoreParts.priority.toFixed(0) }} · 等待 {{ item.scoreParts.wait.toFixed(0) }}）</small>
            </div>
          </div>
          <div class="qi-candidates">
            <span v-for="c in item.candidates" :key="c.code" :class="['cand', { ok: c.eligible, current: c.current }]" :title="candidateTitle(c)">
              {{ c.code }}<i>{{ c.activeLoad }}/{{ c.maxLoad }}</i>
            </span>
          </div>
          <el-button type="primary" class="dispatch-btn" @click="openDispatch(item)">派发</el-button>
        </article>
        <el-pagination
          v-if="queue && queue.totalReady > 0"
          layout="total, sizes, prev, pager, next"
          :total="queue.totalReady"
          :page-size="size"
          :page-sizes="[8, 20, 50]"
          :current-page="page"
          @current-change="changePage"
          @size-change="changeSize" />
      </div>

      <div class="classified" v-if="classifiedGroups.length">
        <details v-for="group in classifiedGroups" :key="group.code" class="classified-group">
          <summary>
            <b>{{ group.name }}</b><span class="count">{{ group.total }} 条</span>
            <em>{{ group.desc }}</em>
            <a v-if="group.code === 'PAUSED' || group.code === 'FAULT_CRANE'" @click.stop.prevent="router.push('/takeover')">前往故障接管台 →</a>
          </summary>
          <div v-for="entry in group.items" :key="entry.instructionId" class="classified-row">
            <b>{{ entry.instructionNo }}</b>
            <span class="tag">{{ TYPE_NAME[entry.instructionType] }}</span>
            <span class="mono">{{ entry.containerNo }}</span>
            <span v-if="entry.craneCode" class="mono">{{ entry.craneCode }}</span>
            <span v-if="entry.vesselName" class="vessel">{{ entry.vesselName }}</span>
            <em>{{ entry.categoryNote }}</em>
            <span class="wait">等待 {{ durationText(entry.waitMinutes) }}</span>
          </div>
          <div v-if="group.total > group.items.length" class="more">其余 {{ group.total - group.items.length }} 条从略</div>
        </details>
      </div>
    </div>

    <div class="crane-grid">
      <article v-for="(crane, index) in cranes" :key="crane.id" class="crane-card glass-card" @click="openCard(crane)">
        <div class="card-index">{{ String(index + 1).padStart(2, '0') }}</div>
        <div class="card-top">
          <div><small>{{ crane.craneType === 'QUAY' ? '岸桥 / QUAY CRANE' : '场桥 / YARD CRANE' }}</small><h3>{{ crane.code }}</h3></div>
          <div class="machine-status"><i :class="['status-dot', crane.status]"></i><span>{{ statusName[crane.status] }}</span></div>
        </div>
        <div class="efficiency-number"><b>{{ crane.actualEfficiency }}</b><span>自然箱 / h</span><em>额定 {{ crane.ratedEfficiency }}</em></div>
        <div class="efficiency-track"><span :style="{ width: `${efficiencyPercent(crane)}%` }"></span><i :style="{ left: `${Math.min(98, efficiencyPercent(crane))}%` }"></i></div>
        <div class="sparkline-box">
          <div><small>近 8 小时作业量</small><b>{{ crane.handledBoxes }} 箱</b></div>
          <svg viewBox="0 0 180 60"><path d="M5 55 H175" stroke="#dce6e4" /><polyline :points="points(crane.sparkline)" fill="none" stroke="#00897b" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" /><circle v-for="(value, i) in crane.sparkline" :key="i" :cx="5 + i * (170 / Math.max(crane.sparkline.length - 1, 1))" :cy="54 - value / Math.max(...crane.sparkline, 1) * 42" r="2.5" fill="#f5c451" stroke="#00695c" /></svg>
        </div>
        <div class="machine-spec"><span>总工时 <b>{{ crane.workHours }}h</b></span><span>达成率 <b>{{ efficiencyPercent(crane).toFixed(0) }}%</b></span><span>点击登记 →</span></div>
      </article>
    </div>

    <el-dialog v-model="dialog" width="560" :title="`${selected?.code || ''} · 司机排班与作业量`">
      <div v-if="selected" class="machine-console">
        <div class="console-status"><span>机械状态</span><el-radio-group :model-value="selected.status" size="small" @change="changeStatus"><el-radio-button value="WORKING">作业</el-radio-button><el-radio-button value="STANDBY">待机</el-radio-button><el-radio-button value="FAULT">故障</el-radio-button></el-radio-group></div>
        <el-form label-position="top" class="shift-form">
          <el-form-item label="司机姓名"><el-input v-model="form.driverName" placeholder="输入司机姓名" /></el-form-item>
          <el-form-item label="开始时间"><el-date-picker v-model="form.shiftStart" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
          <el-form-item label="结束时间"><el-date-picker v-model="form.shiftEnd" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
          <el-form-item label="作业自然箱"><el-input-number v-model="form.handledBoxes" :min="0" style="width:100%" /></el-form-item>
          <el-form-item label="统计工时"><el-input-number v-model="form.workHours" :min="0.1" :step="0.5" style="width:100%" /></el-form-item>
        </el-form>
        <div class="recent-shifts"><span v-for="shift in shifts.slice(0,4)" :key="shift.id"><b>{{ shift.driverName }}</b>{{ shift.handledBoxes }} 箱 / {{ shift.workHours }}h</span></div>
      </div>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="register">登记工班数据</el-button></template>
    </el-dialog>

    <el-dialog v-model="dispatchDialog" width="540" :title="`派发确认 · ${dispatchItem?.instructionNo || ''}`">
      <div v-if="dispatchItem" class="dispatch-panel">
        <div class="dispatch-summary">
          <p><b>{{ TYPE_NAME[dispatchItem.instructionType] }}</b> · {{ dispatchItem.containerNo }} · 需{{ dispatchItem.requiredCraneType === 'QUAY' ? '岸桥' : '场桥' }}<template v-if="dispatchItem.craneCode"> · 当前 {{ dispatchItem.craneCode }}</template></p>
          <p v-if="dispatchItem.vesselName">{{ dispatchItem.vesselName }} / {{ dispatchItem.voyageNo }} · ETD {{ timeText(dispatchItem.expectedDeparture) }}</p>
          <p class="recheck">派发瞬间将重新核对：指令阶段（须待开工）、机械状态（故障即拒）、已接负荷（≤3 条）；两人同时派发以先提交者为准，失败方不留重复指派。</p>
        </div>
        <el-select v-model="dispatchCrane" style="width:100%" placeholder="选择派发机械">
          <el-option v-for="c in dispatchItem.candidates" :key="c.code" :value="c.code" :disabled="!c.eligible"
                     :label="`${c.code} · ${statusName[c.status]} · 负荷 ${c.activeLoad}/${c.maxLoad}${c.current ? ' · 当前归属' : ''}`" />
        </el-select>
        <div class="cand-checks" v-if="chosenCandidate">
          <div v-for="check in chosenCandidate.checks" :key="check.name" :class="['check', { fail: !check.pass }]">
            <i>{{ check.pass ? '✓' : '✗' }}</i>{{ check.name }}：{{ check.note }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dispatchDialog = false">取消</el-button>
        <el-button type="primary" :loading="dispatching" @click="confirmDispatch">核对无误，确认派发</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.shift-banner { background:#123f3a;color:#fff;padding:8px 13px;border-left:3px solid #f5c451; }.shift-banner span,.shift-banner b { display:block; }.shift-banner span { font-size:9px;color:#8bbab4; }.shift-banner b { font-size:13px;margin-top:3px; }.shift-banner i { font-size:9px;color:#f5c451;font-style:normal; }

/* 动态优先级派发队列 */
.queue-console { margin-bottom:14px;padding:14px 16px; }
.queue-head { display:flex;align-items:flex-start;gap:16px;flex-wrap:wrap; }
.queue-title { flex:1;min-width:320px; }.queue-title h3 { font-size:17px;color:#15443f;margin:2px 0 4px; }.queue-title p { font-size:11px;color:#6c8580;margin:0; }
.queue-meta { display:flex;align-items:center;gap:10px;font-size:10px;color:#6c8580; }.queue-meta b { color:#00695c;font-family:monospace;font-size:11px; }
.strategy-note { display:flex;gap:16px;flex-wrap:wrap;margin-top:10px;padding:8px 10px;background:#eef5f3;font-size:10px;color:#4e6c68; }.strategy-note b { color:#00695c; }
.stale-banner { margin-top:10px;padding:8px 12px;background:#fdf3e0;border-left:3px solid #e4a32e;color:#8a5a00;font-size:11px; }
.queue-list { margin-top:10px;min-height:80px; }
.empty-queue { padding:26px;text-align:center;color:#839591;font-size:12px;border:1px dashed #c3d4d0; }
.queue-row { display:flex;align-items:flex-start;gap:12px;padding:11px 10px;border-bottom:1px solid #e4ecea; }
.queue-row:hover { background:#f6faf9; }
.rank-badge { min-width:34px;height:34px;display:grid;place-items:center;background:#123f3a;color:#f5c451;font:bold 15px monospace;border-radius:4px; }
.rank-badge.urgent { background:#8c2f2f;color:#ffd9a0;box-shadow:0 0 0 3px rgba(216,78,78,.18); }
.qi-main { flex:1;min-width:0; }
.qi-top { display:flex;align-items:center;gap:8px;flex-wrap:wrap; }.qi-top b { font:13px monospace;color:#173e3a; }
.tag { background:#e5f1ef;color:#00695c;padding:2px 7px;font-size:10px;border-radius:2px; }
.tag.prio { background:#eceff1;color:#546e7a; }.tag.prio.urgent { background:#fdeed2;color:#9a6200; }.tag.prio.vessel_cutoff { background:#f8dddd;color:#a83232; }
.mono { font:11px monospace;color:#4e6c68; }
.need { font-size:10px;color:#8a6d1f;background:#fbf0d4;padding:2px 7px;border-radius:2px; }
.current-crane { font-size:10px;color:#00695c;border:1px solid #9cc7c0;padding:1px 6px;border-radius:2px; }
.qi-vessel { display:flex;align-items:center;gap:10px;margin-top:5px;font-size:11px;color:#41605b;flex-wrap:wrap; }
.risk { font-style:normal;font-size:10px;padding:2px 7px;border-radius:2px;background:#eceff1;color:#546e7a; }
.risk.overdue,.risk.critical { background:#f8dddd;color:#a83232;font-weight:700; }.risk.high { background:#fdeed2;color:#9a6200; }.risk.medium { background:#fff8e1;color:#8a6d1f; }.risk.low { background:#e5f1ef;color:#00695c; }
.wait { font-size:10px;color:#8a6d1f; }
.qi-reasons { display:flex;gap:6px;flex-wrap:wrap;margin-top:6px; }.qi-reasons span { font-size:10px;color:#4e6c68;background:#f0f6f4;border:1px solid #dde9e6;padding:2px 7px;border-radius:2px; }
.qi-score { display:flex;align-items:center;gap:10px;margin-top:7px; }
.score-track { width:150px;height:6px;background:#e4ecea;border-radius:6px;overflow:hidden; }.score-track span { display:block;height:100%;background:linear-gradient(90deg,#00695c,#20aa98); }
.qi-score small { font-size:10px;color:#78908b; }
.qi-candidates { display:flex;flex-direction:column;gap:4px;min-width:96px; }
.cand { display:flex;justify-content:space-between;gap:6px;font:10px monospace;color:#90a4a0;background:#f0f3f2;padding:3px 7px;border-radius:2px; }
.cand.ok { color:#00695c;background:#e5f1ef;font-weight:700; }.cand.current { box-shadow:inset 0 0 0 1px #00695c; }
.cand i { font-style:normal;color:#8aa29e; }
.dispatch-btn { align-self:center; }
.queue-list :deep(.el-pagination) { margin-top:12px;justify-content:flex-end; }
.classified { margin-top:12px;border-top:1px dashed #c3d4d0;padding-top:8px; }
.classified-group { margin-bottom:6px; }
.classified-group summary { display:flex;align-items:center;gap:10px;cursor:pointer;font-size:11px;color:#41605b;padding:6px 8px;background:#f4f8f7;border-radius:3px;list-style:none; }
.classified-group summary::-webkit-details-marker { display:none; }
.classified-group summary b { color:#15443f; }.classified-group summary .count { background:#dce8e5;color:#41605b;padding:1px 8px;border-radius:8px;font-size:10px; }
.classified-group summary em { font-style:normal;color:#8aa29e;font-size:10px; }
.classified-group summary a { margin-left:auto;color:#00695c;font-size:10px;text-decoration:none; }
.classified-row { display:flex;align-items:center;gap:9px;padding:6px 10px 6px 22px;font-size:11px;color:#41605b;border-bottom:1px dashed #e9f0ee;flex-wrap:wrap; }
.classified-row b { font:11px monospace; }.classified-row em { font-style:normal;color:#8a6d1f;font-size:10px; }
.classified-row .vessel { font-size:10px;color:#6c8580; }.classified-row .wait { margin-left:auto;font-size:10px;color:#9aa8a5; }
.more { padding:5px 22px;font-size:10px;color:#9aa8a5; }
.dispatch-panel .dispatch-summary { background:#f4f8f7;padding:10px 12px;margin-bottom:12px;font-size:12px;color:#41605b; }
.dispatch-panel .dispatch-summary p { margin:4px 0; }
.dispatch-panel .recheck { font-size:10px;color:#8a6d1f; }
.cand-checks { margin-top:12px;display:grid;grid-template-columns:1fr 1fr;gap:6px; }
.cand-checks .check { font-size:11px;color:#00695c;background:#eef7f4;padding:6px 9px;border-radius:3px; }
.cand-checks .check.fail { color:#a83232;background:#f8dddd; }
.cand-checks .check i { font-style:normal;margin-right:6px;font-weight:700; }

.crane-grid { display:grid;grid-template-columns:repeat(4,minmax(230px,1fr));gap:14px; }
.crane-card { position:relative;padding:17px;overflow:hidden;cursor:pointer;transition:.18s; }.crane-card:hover { transform:translateY(-3px);box-shadow:0 13px 28px rgba(24,66,60,.14);border-color:#8eb9b3; }.card-index { position:absolute;right:-5px;top:-17px;font:bold 76px monospace;color:#eff4f3;z-index:0; }.card-top,.efficiency-number,.efficiency-track,.sparkline-box,.machine-spec { position:relative;z-index:1; }
.card-top { display:flex;justify-content:space-between;align-items:flex-start; }.card-top small { color:#73908b;font:9px monospace;letter-spacing:1px; }.card-top h3 { margin:5px 0 0;font:bold 23px monospace;color:#15443f; }.machine-status { display:flex;align-items:center;gap:7px;font-size:11px; }
.efficiency-number { margin-top:17px;display:flex;align-items:baseline;gap:7px; }.efficiency-number b { color:#00695c;font-size:34px;line-height:1; }.efficiency-number span { font-size:10px;color:#718681; }.efficiency-number em { margin-left:auto;font-style:normal;font-size:10px;color:#8a9d99; }
.efficiency-track { height:7px;background:#e4ecea;margin:12px 0 17px;border-radius:8px; }.efficiency-track span { display:block;height:100%;background:linear-gradient(90deg,#00695c,#20aa98);border-radius:8px; }.efficiency-track i { position:absolute;top:-3px;width:2px;height:13px;background:#f0b92e; }
.sparkline-box { background:#f5f8f7;padding:9px 9px 5px;display:flex;align-items:center; }.sparkline-box div { width:90px; }.sparkline-box small,.sparkline-box b { display:block; }.sparkline-box small { font-size:9px;color:#728884; }.sparkline-box b { margin-top:5px;font-size:12px; }.sparkline-box svg { flex:1;height:54px; }
.machine-spec { display:flex;justify-content:space-between;margin-top:13px;font-size:9px;color:#78908b;border-top:1px solid #e4ecea;padding-top:10px; }.machine-spec b { color:#315c57; }
.console-status { background:#eef5f3;padding:11px;display:flex;align-items:center;justify-content:space-between;margin-bottom:13px; }.shift-form { display:grid;grid-template-columns:1fr 1fr;gap:0 12px; }.shift-form .el-form-item:first-child { grid-column:1 / -1; }.recent-shifts { display:flex;gap:6px;overflow:auto; }.recent-shifts span { min-width:105px;background:#163e3a;color:#c8dedb;padding:8px;font-size:9px; }.recent-shifts b { display:block;color:#fff;margin-bottom:3px; }
@media (max-width:1400px){.crane-grid{grid-template-columns:repeat(3,1fr)}}
</style>
