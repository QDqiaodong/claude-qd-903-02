<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { craneApi, dispatchApi } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const cranes = ref([])
const selected = ref(null)
const shifts = ref([])
const dialog = ref(false)
const loading = ref(false)
const form = reactive({ driverName: '', shiftStart: '', shiftEnd: '', handledBoxes: 0, workHours: 1 })
const statusName = { WORKING: '作业', STANDBY: '待机', FAULT: '故障' }

// ============ 动态优先级队列 ============
const dispatcher = ref(localStorage.getItem('dispatch-dispatcher') || '')
const strategy = ref(localStorage.getItem('dispatch-strategy') || 'DEPARTURE_FIRST')
const queue = ref(null)
const queueLoading = ref(false)
const page = ref(1)
const pageSize = 8
const anchorAt = ref(null)
const anchorVersion = ref(null)
const showExcluded = reactive({ NO_SCHEDULE: false, NO_MATCHING: false, IN_PROGRESS: false, PAUSED: false, FINISHED: false })
const dispatchTarget = ref(null)
const dispatchCrane = ref('')
const dispatching = ref(false)
let timer = null

const counts = computed(() => queue.value?.counts || {})
const items = computed(() => queue.value?.items || [])
const stale = computed(() => queue.value?.stale === true)
const riskClass = level => ({ OVERDUE: 'overdue', CRITICAL: 'critical', TIGHT: 'tight', NORMAL: 'normal' }[level] || 'none')
const excludedMeta = [
  { key: 'NO_SCHEDULE', title: '船期缺失', desc: '装卸指令未关联船期或船期记录缺失，无法评估离港风险，不能派发' },
  { key: 'NO_MATCHING', title: '无匹配机械', desc: '同类型机械全部故障/区域不符/能力不足或负荷已满，不能派发' },
  { key: 'IN_PROGRESS', title: '执行中', desc: '已派发并在执行中的指令，无需重复派发' },
  { key: 'PAUSED', title: '已暂停', desc: '故障接管冻结中的指令，需在故障接管台处置' },
  { key: 'FINISHED', title: '已完成 / 已取消', desc: '完结指令不参与派发' }
]

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
    // 机械状态变化：当前推荐立即标记过期并重算（不静默重排，先拉一次让横幅提示）
    if (anchorAt.value) await fetchQueue(true)
  } catch (error) { ElMessage.error(error.message) }
}

// ---- 队列拉取：同一计算时点（anchorAt）内翻页/切策略/轮询顺序冻结；recalculate 开启新计算时点 ----
async function fetchQueue(recalculate = false) {
  if (recalculate) {
    anchorAt.value = null
    anchorVersion.value = null
  }
  queueLoading.value = true
  try {
    const result = await dispatchApi.queue({
      strategy: strategy.value,
      page: page.value,
      size: pageSize,
      anchorAt: anchorAt.value || undefined,
      expectedVersion: anchorVersion.value || undefined
    })
    const data = result.data
    if (!anchorAt.value) {
      anchorAt.value = data.computedAt
      anchorVersion.value = data.anchorVersion
    }
    queue.value = data
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    queueLoading.value = false
  }
}

function switchStrategy(next) {
  if (strategy.value === next) return
  strategy.value = next
  localStorage.setItem('dispatch-strategy', next)
  page.value = 1
  // 切策略保持同一计算时点：只重排、不重算（用同一 anchorAt，版本不变化）
  fetchQueue(false)
}

function changePage(next) {
  page.value = next
  fetchQueue(false)
}

function recalculate() {
  page.value = 1
  fetchQueue(true)
}

function openDispatch(item) {
  dispatchTarget.value = item
  const preferred = item.candidates.find(c => c.preferred && c.eligible) || item.candidates.find(c => c.eligible)
  dispatchCrane.value = preferred?.code || ''
}

async function confirmDispatch() {
  if (!dispatchTarget.value || !dispatchCrane.value) return
  const item = dispatchTarget.value
  const crane = item.candidates.find(c => c.code === dispatchCrane.value)
  const actor = dispatcher.value.trim() || '调度员'
  try {
    await ElMessageBox.confirm(
      `派发前最后核对：指令 ${item.instructionNo}（${item.instructionTypeName} / 阶段「${item.workStageName}」）`
      + ` → 机械 ${crane.code}（${crane.statusName}，已接负荷 ${crane.activeLoad}/${crane.maxLoad}）。\n`
      + '推荐仅供参考，确认后指令将进入对位阶段，是否继续？',
      '确认派发（锁内复核指令阶段/机械状态/负荷）',
      { confirmButtonText: '确认派发', cancelButtonText: '再核对一下', type: 'warning' }
    )
  } catch {
    return
  }
  dispatching.value = true
  try {
    const result = await dispatchApi.dispatch({
      instructionId: item.instructionId,
      craneCode: crane.code,
      dispatcher: actor,
      anchorAt: anchorAt.value || undefined,
      expectedVersion: anchorVersion.value || undefined
    })
    ElMessage.success(`派发成功：${result.data.instructionNo} → ${result.data.craneCode}，已进入对位阶段`)
    dispatchTarget.value = null
    // 刷新后看到最新去向：开启新计算时点重算（旧推荐已过期）
    page.value = 1
    await fetchQueue(true)
  } catch (error) {
    if (error.status === 409) {
      // 并发失败（他人先派发 / 机械转故障/满载）：本次未产生任何指派，直接同步最新队列
      ElMessageBox.alert(
        `${error.message}\n\n页面已同步为最新数据版本，请刷新查看该指令与机械的最新去向，本失败不会留下重复指派。`,
        '派发未生效',
        { confirmButtonText: '知道了', type: 'warning' }
      )
      await fetchQueue(true)
    } else {
      ElMessage.error(error.message)
    }
  } finally {
    dispatching.value = false
  }
}

function fmtTime(value) { return value ? String(value).replace('T', ' ').slice(5, 16) : '--' }
function excludedList(key) { return queue.value?.excluded?.[key] || [] }

onMounted(async () => {
  await load()
  await fetchQueue(true)
  // 轮询只读同一计算时点：数据变化时 stale 转 true，旧顺序保持冻结，由调度员显式重算
  timer = setInterval(() => { if (!dialog.value && !dispatching.value) fetchQueue(false) }, 8000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section class="page-panel crane-page" v-loading="loading">
    <div class="panel-head">
      <div><div class="kicker">CRANE PRODUCTIVITY / 8H TREND</div><h2>岸桥与场桥作业效率</h2><p>自然箱/小时对比额定效率，点击机械卡登记司机班次和小时作业量</p></div>
      <div class="shift-banner"><span>当前工班</span><b>白班 · 08:00—20:00</b><i>生产调度中</i></div>
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

    <!-- ============ 动态优先级队列 ============ -->
    <div class="dispatch-deck glass-card" v-loading="queueLoading">
      <div class="deck-head">
        <div>
          <div class="kicker">DYNAMIC PRIORITY QUEUE / ADVISORY ONLY</div>
          <h2>动态优先级派发队列 <em>仅推荐，不自动派发</em></h2>
          <p>全量未完成指令 × 船舶离港时间 × 所需机械类型 × 实时可用机械，在服务端统一排序后分页；跨页指令与故障机械均纳入计算</p>
        </div>
        <div class="deck-side">
          <label>当班调度员<input v-model="dispatcher" placeholder="签到姓名" @change="localStorage.setItem('dispatch-dispatcher', dispatcher)" /></label>
          <el-button size="small" @click="recalculate">重算最新</el-button>
        </div>
      </div>

      <div class="strategy-bar">
        <el-radio-group :model-value="strategy" @change="switchStrategy">
          <el-radio-button value="DEPARTURE_FIRST">离港优先（分层排序）</el-radio-button>
          <el-radio-button value="FAIR_WAIT">公平等待（加权合并·防饥饿）</el-radio-button>
        </el-radio-group>
        <div class="strategy-note">
          <template v-if="strategy === 'DEPARTURE_FIRST'">取舍：紧迫船期先进 T1/T2 层（层差 100 万/10 万），普通指令无法跨层插队；层内再按优先级/等待/船期压力/机械就绪加权</template>
          <template v-else>取舍：等待时长占 60 分主轴（24h 封顶），等待 ≥4h 饥饿保护 +25；船期风险最多加 30 分，紧迫船提前但普通指令不会长期挨饿</template>
        </div>
        <div class="anchor-mark">
          <span>计算时点 {{ queue?.computedAtText || '--' }}</span>
          <i :class="{ live: !stale }">{{ stale ? '推荐已过期' : '版本一致' }}</i>
          <em v-if="!stale">v{{ anchorVersion?.slice(0, 10) }}</em>
          <em v-else class="stale-v">当前 v{{ queue?.liveVersion?.slice(0, 10) }}</em>
        </div>
      </div>

      <el-alert
        v-if="stale"
        type="warning"
        show-icon
        :closable="false"
        class="stale-banner"
        title="当前展示的是旧计算时点的冻结顺序，船期或机械已发生变化，推荐已过期"
      >
        <template #default>
          <div class="stale-detail">
            <el-button type="warning" size="small" @click="recalculate">立即重算为最新顺序</el-button>
            <div class="change-list">
              <span v-for="(change, i) in queue?.changes" :key="i">• {{ change }}</span>
              <span v-if="!queue?.changes?.length">• 数据版本已变化（变化项超出本地快照窗口），请重算</span>
            </div>
          </div>
        </template>
      </el-alert>

      <div class="queue-counts">
        <span class="qc ready">可派发推荐 <b>{{ counts.ready || 0 }}</b></span>
        <span class="qc">船期缺失 <b>{{ counts.noSchedule || 0 }}</b></span>
        <span class="qc">无匹配机械 <b>{{ counts.noMatching || 0 }}</b></span>
        <span class="qc">执行中 <b>{{ counts.inProgress || 0 }}</b></span>
        <span class="qc">已暂停 <b>{{ counts.paused || 0 }}</b></span>
        <span class="qc">已完成/取消 <b>{{ counts.finished || 0 }}</b></span>
      </div>

      <div class="rank-list">
        <article v-for="item in items" :key="item.instructionId" class="rank-row" :class="riskClass(item.riskLevel)">
          <div class="rank-no">
            <b>#{{ item.rank }}</b>
            <small :class="riskClass(item.riskLevel)">{{ item.riskText }}</small>
          </div>
          <div class="rank-main">
            <div class="rank-title">
              <b>{{ item.instructionNo }}</b>
              <span class="tag">{{ item.instructionTypeName }}</span>
              <span class="tag" :class="'p-' + String(item.priority || '').toLowerCase()">{{ item.priorityName }}</span>
              <span v-if="item.vessel" class="tag vessel">{{ item.vessel.vesselName }} · ETD {{ item.vessel.expectedDepartureText }}</span>
              <span v-else class="tag muted">无船期</span>
            </div>
            <div class="rank-reason">
              <strong>排名原因：</strong>{{ item.reasonPrimary }}
            </div>
            <div class="rank-factors">
              <span v-for="(factor, i) in item.reasonFactors" :key="i">{{ factor }}</span>
            </div>
          </div>
          <div class="rank-meta">
            <div><small>箱号</small><b>{{ item.containerNo }}</b></div>
            <div><small>等待时长</small><b>{{ item.waitText }}</b></div>
            <div><small>当前阶段</small><b>{{ item.workStageName }}</b></div>
          </div>
          <div class="rank-cranes">
            <small>候选机械 {{ item.readyCandidateCount }}/{{ item.candidateCount }} 台可立即派发</small>
            <div class="crane-chips">
              <span v-for="candidate in item.candidates" :key="candidate.code"
                    :class="['crane-chip', { down: !candidate.eligible, chosen: candidate.code === dispatchCrane && dispatchTarget?.instructionId === item.instructionId }]"
                    :title="candidate.checks.map(c => `${c.pass ? '✓' : '✗'} ${c.name}：${c.note}`).join('\n')">
                {{ candidate.code }}<i v-if="candidate.preferred">原归属</i>
              </span>
            </div>
            <el-button type="primary" size="small" :disabled="stale" @click="openDispatch(item)">核对并派发</el-button>
            <small v-if="stale" class="stale-hint">推荐已过期，请重算后再派发</small>
          </div>
        </article>
        <div v-if="!items.length" class="rank-empty">
          当前没有可派发的待执行指令——请展开下方分类查看船期缺失、无匹配机械、暂停或执行中的指令
        </div>
      </div>

      <div class="pager">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="queue?.total || 0"
          :page-size="pageSize"
          :current-page="page"
          @current-change="changePage"
        />
        <span class="pager-note">服务端全量排序后分页：翻页不改变同一计算时点的全局名次</span>
      </div>

      <div class="excluded-deck">
        <div v-for="group in excludedMeta" :key="group.key" class="excluded-group">
          <button class="excluded-head" @click="showExcluded[group.key] = !showExcluded[group.key]">
            <span>{{ group.title }}（{{ excludedList(group.key).length }}）</span>
            <small>{{ group.desc }}</small>
            <i>{{ showExcluded[group.key] ? '收起 ▲' : '展开 ▼' }}</i>
          </button>
          <div v-if="showExcluded[group.key]" class="excluded-body">
            <div v-for="row in excludedList(group.key)" :key="row.instructionId" class="excluded-row">
              <b>{{ row.instructionNo }}</b>
              <span class="tag">{{ row.instructionTypeName }}</span>
              <span class="tag">{{ row.statusName }}<template v-if="row.craneCode"> · {{ row.craneCode }}</template></span>
              <span v-if="row.vesselName" class="tag vessel">{{ row.vesselName }}</span>
              <em class="excluded-reason">{{ row.reason }}</em>
              <small>等待 {{ row.waitText }}</small>
            </div>
            <div v-if="!excludedList(group.key).length" class="excluded-empty">该分类暂无指令</div>
          </div>
        </div>
      </div>

      <div class="dispatch-log" v-if="queue?.recentDispatches?.length">
        <div class="log-title">最近派发去向（刷新后仍可见，防止重复指派）</div>
        <div class="log-list">
          <span v-for="log in queue.recentDispatches" :key="log.id">
            {{ fmtTime(log.dispatchedAt) }} <b>{{ log.instructionNo }}</b> → {{ log.craneCode }}
            <em>{{ log.dispatcher }} · {{ log.reason }}</em>
          </span>
        </div>
      </div>
    </div>

    <!-- 派发确认弹窗：候选机械 + 逐项核对 -->
    <el-dialog v-model="dispatchTarget" width="640px" :show-close="true" title="派发前核对（推荐仅供建议）">
      <div v-if="dispatchTarget" class="dispatch-dialog">
        <div class="verify-line">
          <div><small>指令</small><b>{{ dispatchTarget.instructionNo }}</b></div>
          <div><small>类型 / 优先级</small><b>{{ dispatchTarget.instructionTypeName }} · {{ dispatchTarget.priorityName }}</b></div>
          <div><small>当前阶段</small><b>{{ dispatchTarget.workStageName }}</b></div>
          <div><small>等待时长</small><b>{{ dispatchTarget.waitText }}</b></div>
        </div>
        <div v-if="dispatchTarget.vessel" class="verify-vessel">
          船期：{{ dispatchTarget.vessel.vesselName }} / {{ dispatchTarget.vessel.voyageNo }}
          · ETD {{ dispatchTarget.vessel.expectedDepartureText }} · {{ dispatchTarget.riskText }}
        </div>
        <el-radio-group v-model="dispatchCrane" class="crane-radio">
          <label v-for="candidate in dispatchTarget.candidates" :key="candidate.code"
                 :class="['candidate-pick', { disabled: !candidate.eligible }]">
            <el-radio :value="candidate.code" :disabled="!candidate.eligible">{{ candidate.code }}</el-radio>
            <div class="candidate-meta">
              <span>{{ candidate.craneType === 'QUAY' ? '岸桥' : '场桥' }} · {{ candidate.statusName }}
                <template v-if="candidate.yardZone"> · 固定 {{ candidate.yardZone }} 区</template>
                <template v-if="candidate.preferred"> · <b>原归属</b></template>
              </span>
              <div class="check-chips">
                <span v-for="check in candidate.checks" :key="check.name" :class="{ fail: !check.pass }">
                  {{ check.pass ? '✓' : '✗' }} {{ check.name }}<small>{{ check.note }}</small>
                </span>
              </div>
            </div>
          </label>
        </el-radio-group>
        <p class="dispatch-warning">确认瞬间后端将再次核对指令阶段、机械状态与已接负荷：若他人已抢先派发或机械刚转故障，本次派发将整体失败且不留下重复指派。</p>
      </div>
      <template #footer>
        <el-button @click="dispatchTarget = null">再核对一下</el-button>
        <el-button type="primary" :loading="dispatching" :disabled="!dispatchCrane" @click="confirmDispatch">确认派发到 {{ dispatchCrane }}</el-button>
      </template>
    </el-dialog>

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
  </section>
</template>

<style scoped>
.shift-banner { background:#123f3a;color:#fff;padding:8px 13px;border-left:3px solid #f5c451; }.shift-banner span,.shift-banner b { display:block; }.shift-banner span { font-size:9px;color:#8bbab4; }.shift-banner b { font-size:13px;margin-top:3px; }.shift-banner i { font-size:9px;color:#f5c451;font-style:normal; }
.crane-grid { display:grid;grid-template-columns:repeat(4,minmax(230px,1fr));gap:14px; }
.crane-card { position:relative;padding:17px;overflow:hidden;cursor:pointer;transition:.18s; }.crane-card:hover { transform:translateY(-3px);box-shadow:0 13px 28px rgba(24,66,60,.14);border-color:#8eb9b3; }.card-index { position:absolute;right:-5px;top:-17px;font:bold 76px monospace;color:#eff4f3;z-index:0; }.card-top,.efficiency-number,.efficiency-track,.sparkline-box,.machine-spec { position:relative;z-index:1; }
.card-top { display:flex;justify-content:space-between;align-items:flex-start; }.card-top small { color:#73908b;font:9px monospace;letter-spacing:1px; }.card-top h3 { margin:5px 0 0;font:bold 23px monospace;color:#15443f; }.machine-status { display:flex;align-items:center;gap:7px;font-size:11px; }
.efficiency-number { margin-top:17px;display:flex;align-items:baseline;gap:7px; }.efficiency-number b { color:#00695c;font-size:34px;line-height:1; }.efficiency-number span { font-size:10px;color:#718681; }.efficiency-number em { margin-left:auto;font-style:normal;font-size:10px;color:#8a9d99; }
.efficiency-track { height:7px;background:#e4ecea;margin:12px 0 17px;border-radius:8px;position:relative; }.efficiency-track span { display:block;height:100%;background:linear-gradient(90deg,#00695c,#20aa98);border-radius:8px; }.efficiency-track i { position:absolute;top:-3px;width:2px;height:13px;background:#f0b92e; }
.sparkline-box { background:#f5f8f7;padding:9px 9px 5px;display:flex;align-items:center; }.sparkline-box div { width:90px; }.sparkline-box small,.sparkline-box b { display:block; }.sparkline-box small { font-size:9px;color:#728884; }.sparkline-box b { margin-top:5px;font-size:12px; }.sparkline-box svg { flex:1;height:54px; }
.machine-spec { display:flex;justify-content:space-between;margin-top:13px;font-size:9px;color:#78908b;border-top:1px solid #e4ecea;padding-top:10px; }.machine-spec b { color:#315c57; }
.console-status { background:#eef5f3;padding:11px;display:flex;align-items:center;justify-content:space-between;margin-bottom:13px; }.shift-form { display:grid;grid-template-columns:1fr 1fr;gap:0 12px; }.shift-form .el-form-item:first-child { grid-column:1 / -1; }.recent-shifts { display:flex;gap:6px;overflow:auto; }.recent-shifts span { min-width:105px;background:#163e3a;color:#c8dedb;padding:8px;font-size:9px; }.recent-shifts b { display:block;color:#fff;margin-bottom:3px; }
@media (max-width:1400px){.crane-grid{grid-template-columns:repeat(3,1fr)}}

/* ===== 动态优先级队列 ===== */
.dispatch-deck { margin-top:16px;padding:16px 18px 14px; }
.deck-head { display:flex;justify-content:space-between;align-items:flex-start;gap:16px; }
.deck-head h2 { font-size:18px;margin:4px 0 3px;color:#123f3a;display:flex;align-items:center;gap:10px; }
.deck-head h2 em { font-style:normal;font-size:10px;background:#fff4d6;color:#9a6b00;padding:3px 8px;font-weight:400; }
.deck-head p { margin:0;font-size:11px;color:#6d837f; }
.deck-side { display:flex;gap:8px;align-items:center; }
.deck-side label { font-size:10px;color:#5c7470;display:flex;flex-direction:column;gap:3px; }
.deck-side input { width:120px;padding:5px 8px;border:1px solid #cbdad7;font-size:12px; }
.strategy-bar { display:flex;align-items:center;gap:14px;margin:12px 0 10px;flex-wrap:wrap; }
.strategy-note { flex:1;min-width:300px;font-size:10px;color:#7a5a00;background:#fff8e6;border:1px solid #f0dfa8;padding:6px 10px; }
.anchor-mark { display:flex;align-items:center;gap:7px;font:10px monospace;color:#6d837f;background:#f2f7f6;padding:6px 10px; }
.anchor-mark i { font-style:normal;padding:2px 7px;background:#e6eceb;color:#5c7470; }
.anchor-mark i.live { background:#dff3ec;color:#0d7a5c; }
.anchor-mark .stale-v, .anchor-mark em { color:#b3382f; }
.stale-banner { margin:6px 0 12px; }
.stale-detail { display:flex;gap:12px;align-items:flex-start; }
.change-list { display:flex;flex-direction:column;font-size:10px;color:#8a6d3b;gap:2px; }
.queue-counts { display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px; }
.qc { font-size:10px;color:#5c7470;background:#f1f6f5;border:1px solid #d7e3e0;padding:5px 9px; }
.qc b { color:#174a44;font-size:14px;margin-left:4px; }
.qc.ready { background:#123f3a;color:#cfe3df;border-color:#123f3a; }
.qc.ready b { color:#f5c451; }
.rank-list { display:flex;flex-direction:column;gap:9px; }
.rank-row { display:grid;grid-template-columns:74px minmax(0,1fr) 150px 230px;gap:14px;border:1px solid #d9e5e3;border-left:4px solid #9fb5b1;background:#fbfdfd;padding:12px 14px;align-items:center; }
.rank-row.overdue, .rank-row.critical { border-left-color:#c23b3b; }
.rank-row.tight { border-left-color:#e6a23c; }
.rank-row.normal { border-left-color:#3c9c8c; }
.rank-no { display:flex;flex-direction:column;gap:6px; }
.rank-no b { font:bold 24px monospace;color:#15443f; }
.rank-no small { font-size:9px;padding:2px 6px;width:fit-content;background:#e6efed;color:#5c7470; }
.rank-no small.overdue, .rank-no small.critical { background:#fbe9e7;color:#b2322a; }
.rank-no small.tight { background:#fdf0d5;color:#9a6b00; }
.rank-no small.normal { background:#e2f3ec;color:#0d7a5c; }
.rank-title { display:flex;align-items:center;gap:7px;flex-wrap:wrap; }
.rank-title > b { font:bold 13px monospace;color:#173c38; }
.tag { font-size:9px;background:#e6efed;color:#3c5c57;padding:2px 7px; }
.tag.p-urgent { background:#fdecea;color:#b3382f; }
.tag.p-vessel_cutoff { background:#7b1f1f;color:#fff; }
.tag.vessel { background:#dff0ee;color:#00695c; }
.tag.muted { background:#f0f3f2;color:#8a9d99; }
.rank-reason { margin-top:7px;font-size:11px;color:#33524d; }
.rank-reason strong { color:#00695c; }
.rank-factors { display:flex;flex-wrap:wrap;gap:5px;margin-top:6px; }
.rank-factors span { font-size:9px;background:#f1f6f5;border:1px solid #dde8e5;color:#58706b;padding:2px 7px; }
.rank-meta { display:grid;grid-template-columns:1fr;gap:5px; }
.rank-meta div { display:flex;justify-content:space-between;font-size:10px;border-bottom:1px dashed #e3ecea;padding-bottom:3px; }
.rank-meta small { color:#8aa39e; }
.rank-meta b { color:#234944;font-size:11px; }
.rank-cranes { display:flex;flex-direction:column;gap:7px; }
.rank-cranes > small { font-size:9px;color:#7a8f8b; }
.crane-chips { display:flex;gap:5px;flex-wrap:wrap; }
.crane-chip { font:10px monospace;background:#e2f3ec;color:#0d6b52;padding:3px 7px;position:relative; }
.crane-chip i { font-style:normal;margin-left:4px;font-size:8px;background:#0d6b52;color:#fff;padding:1px 4px; }
.crane-chip.down { background:#f0e3e2;color:#8a3a34;text-decoration:line-through; }
.stale-hint { font-size:9px;color:#b3382f; }
.rank-empty { padding:22px;text-align:center;color:#7a8f8b;font-size:12px;border:1px dashed #d5e1df; }
.pager { display:flex;align-items:center;gap:14px;margin-top:12px; }
.pager-note { font-size:10px;color:#8aa39e; }
.excluded-deck { margin-top:12px;border-top:1px dashed #d5e1df;padding-top:10px;display:flex;flex-direction:column;gap:6px; }
.excluded-head { width:100%;display:flex;align-items:center;gap:12px;background:#f4f8f7;border:1px solid #dde8e5;padding:7px 11px;text-align:left; }
.excluded-head span { font-size:12px;font-weight:700;color:#33524d; }
.excluded-head small { font-size:10px;color:#8aa39e;flex:1; }
.excluded-head i { font-style:normal;font-size:10px;color:#00695c; }
.excluded-body { padding:4px 11px;display:flex;flex-direction:column;gap:4px; }
.excluded-row { display:flex;align-items:center;gap:8px;font-size:10px;background:#fbfdfd;border:1px solid #e7eeec;padding:6px 9px;flex-wrap:wrap; }
.excluded-row b { font-family:monospace;color:#6a4a44; }
.excluded-reason { color:#b3382f;font-style:normal;flex:1;min-width:220px; }
.excluded-row small { color:#93a8a4;margin-left:auto; }
.excluded-empty { font-size:10px;color:#9db0ac;padding:6px 11px; }
.dispatch-log { margin-top:10px;background:#f7faf9;border:1px solid #d9e5e3;padding:8px 11px; }
.log-title { font-size:11px;font-weight:700;color:#174a44;margin-bottom:5px; }
.log-list { display:flex;flex-direction:column;gap:3px;font-size:10px;color:#5c7470;max-height:96px;overflow:auto; }
.log-list b { font-family:monospace;color:#234944; }
.log-list em { font-style:normal;color:#8aa39e;margin-left:6px; }

/* 派发弹窗 */
.dispatch-dialog { display:flex;flex-direction:column;gap:12px; }
.verify-line { display:grid;grid-template-columns:repeat(4,1fr);gap:8px; }
.verify-line div { background:#eef5f3;padding:8px 10px; }
.verify-line small, .verify-line b { display:block; }
.verify-line small { font-size:9px;color:#6c837f; }
.verify-line b { font-size:12px;color:#174a44;margin-top:4px; }
.verify-vessel { background:#fff7e6;border:1px solid #f0dfa8;color:#8a6d3b;font-size:11px;padding:8px 11px; }
.crane-radio { display:flex;flex-direction:column;gap:8px; }
.candidate-pick { display:flex;align-items:flex-start;gap:8px;border:1px solid #d9e5e3;padding:9px 11px;cursor:pointer; }
.candidate-pick.disabled { opacity:.6;background:#f6f8f8;cursor:not-allowed; }
.candidate-meta { font-size:10px;color:#5c7470; }
.candidate-meta .check-chips { display:flex;flex-wrap:wrap;gap:5px;margin-top:5px; }
.candidate-meta .check-chips span { background:#e2f3ec;color:#0d6b52;padding:2px 6px;font-size:9px; }
.candidate-meta .check-chips span.fail { background:#f0e3e2;color:#8a3a34; }
.candidate-meta .check-chips small { margin-left:4px;opacity:.85; }
.dispatch-warning { margin:0;font-size:10px;color:#b3382f;background:#fdf2f1;border:1px solid #f0d2cf;padding:7px 10px; }
</style>
