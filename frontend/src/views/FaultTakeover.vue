<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { takeoverApi } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const STAGES = ['AWAITING_START', 'POSITIONING', 'LIFTING', 'LANDING']
const STAGE_NAME = { AWAITING_START: '待开工', POSITIONING: '对位中', LIFTING: '吊运中', LANDING: '落箱确认' }
const STATUS_NAME = { PENDING: '待执行', EXECUTING: '执行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' }
const DECISION_NAME = { NONE: '待处置', PAUSED: '已暂停', WAIT_RECOVERY: '等待原机械恢复', TRANSFERRED: '已转交', RESUMED: '已恢复原机械', RELEASED: '随恢复解除' }
const TYPE_NAME = { UNLOAD: '卸船', LOAD: '装船', SHIFT: '翻倒', PICKUP: '提箱' }
const PRIORITY_NAME = { NORMAL: '普通', URGENT: '加急', VESSEL_CUTOFF: '截关' }
const EVENT_NAME = {
  PLAN_CREATED: '方案创建', ITEM_ATTACHED: '指令归集', BATCH_PAUSE: '整批暂停', PAUSE: '暂停',
  WAIT_RECOVERY: '等待恢复', TRANSFER: '转交', TRANSFER_FAILED: '转交失败',
  RESUME_CONFIRM: '恢复确认', RELEASED: '恢复解除', PLAN_CLOSED: '方案关闭'
}

const dispatcher = ref(localStorage.getItem('takeover-dispatcher') || '')
watch(dispatcher, value => localStorage.setItem('takeover-dispatcher', value))

const overview = ref([])
const detail = ref(null)
const selectedPlanId = ref(null)
const loading = ref(false)
const submitting = ref(false)
const outcomes = ref([])
const choiceItemId = ref(null)
const checked = ref([])

const transferDialog = ref(false)
const transferItems = ref([])
const candidateMap = ref({})
const chosenCrane = ref('')

const plan = computed(() => detail.value?.plan || {})
const items = computed(() => detail.value?.items || [])
const recovered = computed(() => plan.value.craneStatus && plan.value.craneStatus !== 'FAULT')
const undecidedCount = computed(() => items.value.filter(item => item.decision === 'NONE').length)
const pausedItems = computed(() => items.value.filter(item => ['PAUSED', 'WAIT_RECOVERY'].includes(item.decision)))
const checkedItems = computed(() => items.value.filter(item => checked.value.includes(item.itemId)))
const firstCandidates = computed(() => candidateMap.value[transferItems.value[0]?.itemId] || [])

const actionable = item => ['NONE', 'PAUSED', 'WAIT_RECOVERY'].includes(item.decision)
  && !['COMPLETED', 'CANCELLED'].includes(item.liveStatus)

function actor() { return dispatcher.value.trim() || '调度员' }
function fmt(value) { return value ? String(value).replace('T', ' ').slice(5, 16) : '--' }

async function loadOverview() {
  try {
    const result = await takeoverApi.overview()
    overview.value = result.data
    if (!selectedPlanId.value && overview.value.length) await selectPlan(overview.value[0])
  } catch (error) { ElMessage.error(error.message) }
}

async function selectPlan(card) {
  selectedPlanId.value = card.planId
  checked.value = []
  choiceItemId.value = null
  outcomes.value = []
  await loadPlan(card.planId)
}

async function loadPlan(planId, silent = false) {
  if (!silent) loading.value = true
  try {
    const result = await takeoverApi.plan(planId)
    detail.value = result.data
    checked.value = checked.value.filter(id => result.data.items.some(item => item.itemId === id && actionable(item)))
  } catch (error) {
    if (!silent) ElMessage.error(error.message)
  } finally { loading.value = false }
}

function applyDetail(data) {
  detail.value = data
  outcomes.value = (data.outcomes || []).map(outcome => ({
    ...outcome,
    instructionNo: data.items.find(item => item.itemId === outcome.itemId)?.instructionNo
  }))
  checked.value = checked.value.filter(id => data.items.some(item => item.itemId === id && actionable(item)))
  if (choiceItemId.value && !data.items.some(item => item.itemId === choiceItemId.value && actionable(item))) {
    choiceItemId.value = null
  }
  loadOverview()
  const failed = (data.outcomes || []).filter(outcome => !outcome.ok)
  if (failed.length) {
    ElMessage.warning(`${failed.length} 条指令未处置成功，原因已保留在列表与留痕中`)
  }
}

function handleError(error) {
  if (error.status === 409) {
    // 他人已先提交：直接渲染冲突响应携带的最新方案，让后提交的人看到方案变化与剩余指令
    if (error.data && error.data.plan) applyDetail(error.data)
    ElMessageBox.alert(
      `方案已被其他调度员更新，页面已同步为最新方案。剩余可处置指令 ${detail.value?.remaining ?? 0} 条，请基于最新方案继续。`,
      '方案已变化',
      { confirmButtonText: '知道了', type: 'warning' }
    )
  } else {
    ElMessage.error(error.message)
  }
}

async function submitDecisions(decisions) {
  submitting.value = true
  try {
    const result = await takeoverApi.decide(plan.value.id, {
      expectedVersion: plan.value.version, dispatcher: actor(), decisions
    })
    applyDetail(result.data)
    ElMessage.success('处置已提交并留痕')
    return true
  } catch (error) {
    handleError(error)
    return false
  } finally { submitting.value = false }
}

function decideOne(item, action) {
  return submitDecisions([{ itemId: item.itemId, action }]).then(ok => {
    if (ok) choiceItemId.value = null
    return ok
  })
}

async function pauseAll() {
  try {
    const result = await takeoverApi.pauseAll(plan.value.id, {
      expectedVersion: plan.value.version, dispatcher: actor()
    })
    applyDetail(result.data)
    ElMessage.success('已整批暂停，等待逐条处置')
  } catch (error) { handleError(error) }
}

function batchPause() {
  return submitDecisions(checkedItems.value.map(item => ({ itemId: item.itemId, action: 'PAUSE' })))
}

async function resumeItems(itemIds) {
  try {
    const result = await takeoverApi.resume(plan.value.id, {
      expectedVersion: plan.value.version, dispatcher: actor(), itemIds
    })
    applyDetail(result.data)
    ElMessage.success('恢复确认已提交')
  } catch (error) { handleError(error) }
}

async function openTransfer(list) {
  transferItems.value = list
  chosenCrane.value = ''
  candidateMap.value = {}
  transferDialog.value = true
  try {
    const map = {}
    for (const item of list) {
      map[item.itemId] = (await takeoverApi.candidates(item.itemId)).data
    }
    candidateMap.value = map
  } catch (error) { ElMessage.error(error.message) }
}

async function confirmTransfer() {
  if (!chosenCrane.value) return
  const ok = await submitDecisions(transferItems.value.map(item => ({
    itemId: item.itemId, action: 'TRANSFER', takeoverCraneCode: chosenCrane.value
  })))
  if (ok) {
    transferDialog.value = false
    choiceItemId.value = null
  }
}

function nodeClass(item, index) {
  const liveIndex = STAGES.indexOf(item.liveStage)
  const confirmedIndex = STAGES.indexOf(item.liveConfirmedStage)
  const classes = []
  if (index < liveIndex) classes.push('done')
  if (index === liveIndex) {
    classes.push(item.liveStatus === 'EXECUTING' ? 'current' : item.liveStatus === 'PAUSED' ? 'frozen' : 'done')
  }
  if (index <= confirmedIndex) classes.push('confirmed')
  return classes
}

function stageTip(item) {
  return `当前阶段「${STAGE_NAME[item.liveStage] || item.liveStage}」，已确认到「${STAGE_NAME[item.liveConfirmedStage] || item.liveConfirmedStage}」`
}

async function refreshAll() {
  await loadOverview()
  if (selectedPlanId.value) await loadPlan(selectedPlanId.value)
}

let timer = null
onMounted(async () => {
  await loadOverview()
  timer = setInterval(() => {
    if (transferDialog.value || submitting.value) return
    loadOverview()
    if (selectedPlanId.value && detail.value?.plan?.status === 'OPEN') loadPlan(selectedPlanId.value, true)
  }, 8000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section class="page-panel takeover-page" v-loading="loading">
    <div class="panel-head">
      <div>
        <div class="kicker">FAULT TAKEOVER / DISPATCH CONSOLE</div>
        <h2>机械故障接管台</h2>
        <p>机械转故障即汇总关联指令并停止派新；处置全程留痕，并发提交以方案版本为准，后到者自动同步最新方案</p>
      </div>
      <div class="dispatcher-box">
        <label>当班调度员</label>
        <el-input v-model="dispatcher" size="small" placeholder="签到姓名" style="width: 130px" />
        <el-button size="small" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <div class="fault-strip">
      <article v-for="card in overview" :key="card.planId" class="fault-card glass-card"
               :class="{ active: card.planId === selectedPlanId, recovered: card.craneStatus !== 'FAULT' }"
               @click="selectPlan(card)">
        <div class="fc-top">
          <div>
            <small>{{ card.craneType === 'QUAY' ? '岸桥' : '场桥' }} / {{ card.planNo }}</small>
            <h3>{{ card.craneCode }}</h3>
          </div>
          <span class="fc-status" :class="card.craneStatus === 'FAULT' ? 'fault' : 'ok'">
            {{ card.craneStatus === 'FAULT' ? '故障中' : '已恢复' }}
          </span>
        </div>
        <div class="fc-counts">
          <span>待处置 <b>{{ card.pending }}</b></span>
          <span>暂停中 <b>{{ card.paused }}</b></span>
          <span>已转交 <b>{{ card.transferred }}</b></span>
          <span>已恢复 <b>{{ card.resumed }}</b></span>
        </div>
        <div class="fc-foot">方案版本 v{{ card.version }} · 共 {{ card.total }} 条指令</div>
      </article>
      <div v-if="!overview.length" class="no-fault glass-card">
        <b>当前没有待处置的故障机械</b>
        <p>在「岸桥作业」页将机械置为故障后，这里会立即生成接管方案并汇总关联指令；机械恢复后可在此发起恢复确认</p>
      </div>
    </div>

    <div v-if="detail" class="takeover-grid">
      <div class="plan-main glass-card">
        <div class="plan-head">
          <div>
            <small>接管方案 {{ plan.planNo }} · 版本 v{{ plan.version }}</small>
            <h3>
              {{ plan.craneCode }}
              <em>{{ plan.craneType === 'QUAY' ? '岸桥' : '场桥' }}<template v-if="plan.yardZone"> · 固定 {{ plan.yardZone }} 区</template> · 额定 {{ plan.craneRatedLoad }}t</em>
            </h3>
          </div>
          <div class="plan-actions">
            <span class="plan-state" :class="plan.status === 'OPEN' ? 'open' : 'closed'">{{ plan.status === 'OPEN' ? '处置中' : '已关闭' }}</span>
            <el-button size="small" @click="refreshAll">刷新</el-button>
            <el-button size="small" type="warning" plain :disabled="plan.status !== 'OPEN' || !undecidedCount" @click="pauseAll">
              整批暂停（{{ undecidedCount }}）
            </el-button>
          </div>
        </div>

        <div v-if="recovered && plan.status === 'OPEN'" class="recover-banner">
          <div>
            <b>原机械已恢复</b>
            <p v-if="pausedItems.length">仍有 {{ pausedItems.length }} 条暂停指令待恢复确认；已转交指令留在接管机械，不可抢回。</p>
            <p v-else>无暂停中的指令；已转交指令留在接管机械，不可抢回。</p>
          </div>
          <el-button v-if="pausedItems.length" size="small" type="primary"
                     @click="resumeItems(pausedItems.map(item => item.itemId))">一键恢复确认</el-button>
        </div>

        <div v-if="detail.vessels?.length" class="vessel-strip">
          <div v-for="vessel in detail.vessels" :key="vessel.scheduleId" class="vessel-chip" :class="{ risk: vessel.remaining > 0 }">
            <b>{{ vessel.vesselName }}</b>
            <span>{{ vessel.voyageNo }}</span>
            <em>剩余 {{ vessel.remaining }}/{{ vessel.total }} 条</em>
            <small>ETD {{ fmt(vessel.expectedDeparture) }}</small>
          </div>
        </div>

        <div v-if="outcomes.length" class="outcome-panel">
          <div v-for="(outcome, index) in outcomes" :key="index" class="outcome" :class="{ fail: !outcome.ok }">
            <span>{{ outcome.ok ? '✓' : '✗' }}</span>
            <b>{{ outcome.instructionNo || ('#' + outcome.itemId) }}</b>
            <p>{{ outcome.message }}</p>
          </div>
          <el-button text size="small" @click="outcomes = []">清除</el-button>
        </div>

        <div v-if="checkedItems.length" class="batch-bar">
          <span>已选 {{ checkedItems.length }} 条</span>
          <el-button size="small" @click="batchPause">批量暂停</el-button>
          <el-button size="small" type="primary" @click="openTransfer(checkedItems)">批量转交…</el-button>
        </div>

        <el-checkbox-group v-model="checked">
          <div class="item-list">
            <div v-for="item in detail.items" :key="item.itemId" class="takeover-item"
                 :class="[{ terminal: !actionable(item) }, 'dec-' + item.decision.toLowerCase().replace('_', '-')]">
              <div class="item-row">
                <div class="item-check">
                  <el-checkbox v-if="actionable(item) && plan.status === 'OPEN'" :value="item.itemId" />
                </div>
                <div class="item-id">
                  <b>{{ item.instructionNo }}</b>
                  <div class="tags">
                    <span class="tag">{{ TYPE_NAME[item.instructionType] || item.instructionType }}</span>
                    <span class="tag" :class="'p-' + String(item.priority || '').toLowerCase()">{{ PRIORITY_NAME[item.priority] || item.priority }}</span>
                    <span v-if="item.vesselName" class="tag vessel">{{ item.vesselName }}</span>
                  </div>
                </div>
                <div class="item-box">
                  <b>{{ item.containerNo }}</b>
                  <small>{{ item.containerType || '—' }} · {{ item.containerWeight ?? '?' }}t</small>
                </div>
                <div class="item-stage">
                  <div class="stage-line">
                    <template v-for="(stage, index) in STAGES" :key="stage">
                      <span class="stage-node" :class="nodeClass(item, index)" :title="stageTip(item)">{{ STAGE_NAME[stage] }}</span>
                      <i v-if="index < STAGES.length - 1" class="stage-link"></i>
                    </template>
                  </div>
                  <small class="stage-note">
                    故障时：{{ STATUS_NAME[item.statusSnapshot] }} / {{ STAGE_NAME[item.stageSnapshot] }}
                    <template v-if="item.liveCraneCode && item.liveCraneCode !== plan.craneCode"> · 现属 {{ item.liveCraneCode }}</template>
                  </small>
                </div>
                <div class="item-state">
                  <span class="status-pill" :class="String(item.liveStatus || '').toLowerCase()">{{ STATUS_NAME[item.liveStatus] || item.liveStatus }}</span>
                  <span class="decision-pill" :class="item.decision.toLowerCase().replace('_', '-')">
                    {{ DECISION_NAME[item.decision] }}<template v-if="item.decision === 'TRANSFERRED'"> → {{ item.takeoverCraneCode }}</template>
                  </span>
                  <small v-if="item.failureReason" class="fail-reason">{{ item.failureReason }}</small>
                </div>
                <div class="item-actions">
                  <template v-if="plan.status === 'OPEN'">
                    <template v-if="item.decision === 'NONE'">
                      <el-button v-if="item.statusSnapshot === 'EXECUTING'" size="small" type="primary" plain
                                 @click="choiceItemId = choiceItemId === item.itemId ? null : item.itemId">选择处置</el-button>
                      <template v-else>
                        <el-button size="small" @click="decideOne(item, 'PAUSE')">暂停</el-button>
                        <el-button size="small" type="primary" plain @click="openTransfer([item])">转交…</el-button>
                      </template>
                    </template>
                    <template v-else-if="['PAUSED', 'WAIT_RECOVERY'].includes(item.decision)">
                      <el-button size="small" type="primary" plain @click="openTransfer([item])">改派…</el-button>
                      <el-button v-if="recovered" size="small" type="success" plain @click="resumeItems([item.itemId])">恢复确认</el-button>
                    </template>
                  </template>
                </div>
              </div>

              <div v-if="choiceItemId === item.itemId && item.impact" class="choice-panel">
                <div class="choice-card">
                  <h4>方案 A · 等待原机械短时恢复</h4>
                  <p><label>船舶进度</label>{{ item.impact.waitVessel }}</p>
                  <p><label>重复操作风险</label>{{ item.impact.waitDuplicate }}</p>
                  <el-button size="small" :loading="submitting" @click="decideOne(item, 'WAIT_RECOVERY')">选择等待恢复</el-button>
                </div>
                <div class="choice-card alt">
                  <h4>方案 B · 从已确认阶段转交</h4>
                  <p><label>船舶进度</label>{{ item.impact.transferVessel }}</p>
                  <p><label>重复操作风险</label>{{ item.impact.transferDuplicate }}</p>
                  <el-button size="small" type="primary" :loading="submitting" @click="openTransfer([item])">选择接管机械</el-button>
                </div>
              </div>
            </div>
            <div v-if="!detail.items.length" class="no-items">该机械故障时没有关联的待执行 / 执行中指令，新任务已停止派入</div>
          </div>
        </el-checkbox-group>
      </div>

      <aside class="event-rail glass-card">
        <div class="rail-title"><span>处置留痕</span><small>每次决定均落库</small></div>
        <div class="event-list">
          <div v-for="event in detail.events" :key="event.id" class="event-item">
            <div class="ev-head">
              <b>{{ EVENT_NAME[event.eventType] || event.eventType }}</b>
              <time>{{ fmt(event.createdAt) }}</time>
            </div>
            <p>{{ event.detail }}</p>
            <span class="ev-actor">{{ event.actor }}</span>
          </div>
          <div v-if="!detail.events?.length" class="no-events">暂无留痕</div>
        </div>
      </aside>
    </div>

    <el-dialog v-model="transferDialog" width="680" :title="`选择接管机械 · ${transferItems.length} 条指令`">
      <div class="candidate-list" v-loading="!firstCandidates.length">
        <div v-for="candidate in firstCandidates" :key="candidate.code" class="candidate"
             :class="{ disabled: !candidate.eligible, chosen: chosenCrane === candidate.code }"
             @click="candidate.eligible && (chosenCrane = candidate.code)">
          <div class="candidate-head">
            <b>{{ candidate.code }}</b>
            <span>{{ candidate.status === 'WORKING' ? '作业中' : candidate.status === 'STANDBY' ? '待机' : '故障' }}</span>
            <em :class="{ no: !candidate.eligible }">{{ candidate.eligible ? '可接收' : '不可接收' }}</em>
          </div>
          <div class="check-chips">
            <span v-for="check in candidate.checks" :key="check.name" :class="{ pass: check.pass }">
              {{ check.pass ? '✓' : '✗' }} {{ check.name }}<small>{{ check.note }}</small>
            </span>
          </div>
        </div>
        <div v-if="!firstCandidates.length" class="no-candidate">正在校验同类型机械…</div>
      </div>
      <p v-if="transferItems.length > 1" class="batch-note">
        将对 {{ transferItems.length }} 条指令逐条校验：不满足区域 / 能力 / 负荷条件的指令不会被强行转走，会冻结并保留原因，可稍后改派。
      </p>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!chosenCrane" :loading="submitting" @click="confirmTransfer">确认转交</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.dispatcher-box { display: flex; align-items: center; gap: 8px; background: #fff; border: 1px solid #cbdad7; padding: 8px 12px; }
.dispatcher-box label { font-size: 11px; color: #5c7470; white-space: nowrap; }

.fault-strip { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 12px; margin-bottom: 14px; }
.fault-card { padding: 14px 16px; cursor: pointer; border-top: 3px solid #d54747; transition: .15s; }
.fault-card:hover { transform: translateY(-2px); }
.fault-card.active { border-color: #f5c451; box-shadow: 0 10px 26px rgba(213, 71, 71, .16); }
.fault-card.recovered { border-top-color: #1ca879; }
.fc-top { display: flex; justify-content: space-between; align-items: flex-start; }
.fc-top small { font: 9px monospace; color: #7a8f8b; letter-spacing: 1px; }
.fc-top h3 { margin: 4px 0 0; font: bold 22px monospace; color: #15443f; }
.fc-status { font-size: 10px; padding: 4px 8px; }
.fc-status.fault { background: #fbe9e7; color: #c23b3b; }
.fc-status.ok { background: #e2f3ec; color: #0d7a5c; }
.fc-counts { display: flex; gap: 10px; margin-top: 12px; font-size: 10px; color: #6d837f; }
.fc-counts b { color: #b3382f; font-size: 15px; margin-left: 2px; }
.fc-foot { margin-top: 10px; padding-top: 8px; border-top: 1px dashed #dde7e5; font: 9px monospace; color: #8ba09c; }
.no-fault { grid-column: 1 / -1; padding: 26px; text-align: center; color: #6d837f; }
.no-fault b { color: #2c4f4a; }
.no-fault p { margin: 8px 0 0; font-size: 11px; }

.takeover-grid { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; align-items: start; }
.plan-main { padding: 16px 18px; }
.plan-head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #e2eae8; padding-bottom: 12px; }
.plan-head small { font: 10px monospace; color: #7a8f8b; }
.plan-head h3 { margin: 5px 0 0; font-size: 20px; color: #143c38; }
.plan-head h3 em { font-style: normal; font-size: 11px; color: #6d837f; margin-left: 8px; }
.plan-actions { display: flex; align-items: center; gap: 8px; }
.plan-state { font-size: 11px; padding: 5px 10px; }
.plan-state.open { background: #fff4d6; color: #9a6b00; }
.plan-state.closed { background: #e6eceb; color: #5c7470; }

.recover-banner { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 12px; background: #e2f3ec; border: 1px solid #9fd4c2; border-left: 4px solid #1ca879; padding: 10px 14px; }
.recover-banner b { color: #0d6b52; font-size: 13px; }
.recover-banner p { margin: 4px 0 0; font-size: 11px; color: #3c6a5e; }

.vessel-strip { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.vessel-chip { border: 1px solid #cfddda; background: #f6faf9; padding: 8px 12px; display: grid; grid-template-columns: auto auto; gap: 2px 10px; font-size: 10px; color: #6d837f; }
.vessel-chip b { color: #174a44; font-size: 12px; }
.vessel-chip em { font-style: normal; color: #00695c; font-weight: 700; }
.vessel-chip.risk em { color: #b3382f; }
.vessel-chip small { grid-column: 1 / -1; font-family: monospace; color: #8ba09c; }

.outcome-panel { margin-top: 12px; border: 1px solid #e2d9c2; background: #fffcf3; padding: 8px 12px; }
.outcome { display: flex; align-items: baseline; gap: 8px; font-size: 11px; padding: 3px 0; }
.outcome span { color: #0d7a5c; font-weight: 700; }
.outcome.fail span { color: #c23b3b; }
.outcome b { font-family: monospace; font-size: 10px; }
.outcome p { margin: 0; color: #5c7470; }

.batch-bar { display: flex; align-items: center; gap: 10px; margin-top: 12px; background: #123f3a; color: #cfe3df; padding: 8px 12px; font-size: 12px; }

.item-list { margin-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.takeover-item { border: 1px solid #d9e5e3; border-left: 4px solid #8aa29e; background: #fbfdfd; }
.takeover-item.dec-none { border-left-color: #d54747; }
.takeover-item.dec-paused, .takeover-item.dec-wait-recovery { border-left-color: #e6a23c; }
.takeover-item.dec-transferred, .takeover-item.dec-resumed { border-left-color: #1ca879; }
.takeover-item.terminal { background: #f4f7f6; }
.item-row { display: grid; grid-template-columns: 30px 1.35fr 1fr 1.5fr 1.15fr auto; gap: 12px; align-items: center; padding: 12px 14px; }
.item-check { display: flex; justify-content: center; }
.item-id b { font: bold 12px monospace; color: #173c38; display: block; }
.tags { display: flex; gap: 4px; margin-top: 5px; flex-wrap: wrap; }
.tag { font-size: 9px; background: #e6efed; color: #3c5c57; padding: 2px 6px; }
.tag.p-urgent { background: #fdecea; color: #b3382f; }
.tag.p-vessel_cutoff { background: #7b1f1f; color: #fff; }
.tag.vessel { background: #dff0ee; color: #00695c; }
.item-box b { font: bold 12px monospace; color: #2c4f4a; display: block; }
.item-box small { font-size: 9px; color: #7a8f8b; }
.stage-line { display: flex; align-items: center; gap: 3px; }
.stage-node { font-size: 9px; padding: 3px 6px; background: #e9efee; color: #8ba09c; white-space: nowrap; }
.stage-node.done { background: #cfe4df; color: #2c5f58; }
.stage-node.current { background: #00695c; color: #fff; }
.stage-node.frozen { background: #e6a23c; color: #fff; }
.stage-node.confirmed { box-shadow: inset 0 -2px #f5c451; }
.stage-link { width: 8px; border-top: 1px dashed #b9cbc8; }
.stage-note { display: block; margin-top: 5px; font-size: 9px; color: #8ba09c; }
.item-state { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.status-pill { font-size: 10px; padding: 2px 8px; background: #e6efed; color: #3c5c57; }
.status-pill.executing { background: #d8ecec; color: #00695c; }
.status-pill.paused { background: #fdf0d5; color: #9a6b00; }
.status-pill.completed { background: #e2f3ec; color: #0d7a5c; }
.decision-pill { font-size: 10px; padding: 2px 8px; background: #f0e3e2; color: #8a3a34; }
.decision-pill.paused, .decision-pill.wait-recovery { background: #fdf0d5; color: #9a6b00; }
.decision-pill.transferred, .decision-pill.resumed { background: #e2f3ec; color: #0d7a5c; }
.decision-pill.released { background: #e6eceb; color: #5c7470; }
.fail-reason { font-size: 9px; color: #b3382f; max-width: 210px; }
.item-actions { display: flex; gap: 6px; }
.no-items { padding: 22px; text-align: center; color: #7a8f8b; font-size: 12px; border: 1px dashed #d5e1df; }

.choice-panel { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding: 0 14px 14px; }
.choice-card { border: 1px solid #d9e5e3; background: #fff; padding: 12px 14px; }
.choice-card.alt { border-color: #9fc4be; background: #f4faf9; }
.choice-card h4 { margin: 0 0 8px; font-size: 12px; color: #174a44; }
.choice-card p { display: flex; gap: 8px; margin: 6px 0; font-size: 11px; color: #4c6661; }
.choice-card label { flex: 0 0 74px; font-size: 9px; color: #8ba09c; padding-top: 2px; }
.choice-card .el-button { margin-top: 8px; }

.event-rail { padding: 14px; max-height: calc(100vh - 220px); overflow: auto; }
.rail-title { display: flex; justify-content: space-between; align-items: baseline; border-bottom: 1px solid #e2eae8; padding-bottom: 9px; }
.rail-title span { font-weight: 700; color: #174a44; font-size: 13px; }
.rail-title small { font-size: 9px; color: #8ba09c; }
.event-list { margin-top: 10px; display: flex; flex-direction: column; gap: 9px; }
.event-item { border-left: 3px solid #9fc4be; background: #f7fbfa; padding: 8px 10px; }
.ev-head { display: flex; justify-content: space-between; align-items: baseline; }
.ev-head b { font-size: 11px; color: #174a44; }
.ev-head time { font: 9px monospace; color: #8ba09c; }
.event-item p { margin: 5px 0; font-size: 10px; color: #4c6661; line-height: 1.5; }
.ev-actor { font-size: 9px; background: #123f3a; color: #cfe3df; padding: 2px 6px; }
.no-events { text-align: center; color: #8ba09c; font-size: 11px; padding: 18px 0; }

.candidate-list { display: flex; flex-direction: column; gap: 10px; min-height: 120px; }
.candidate { border: 1px solid #d5e1df; padding: 11px 13px; cursor: pointer; transition: .12s; }
.candidate:hover { border-color: #8eb9b3; }
.candidate.chosen { border-color: #00695c; box-shadow: 0 0 0 2px rgba(0, 105, 92, .16); }
.candidate.disabled { opacity: .62; cursor: not-allowed; background: #f4f7f6; }
.candidate-head { display: flex; align-items: center; gap: 10px; }
.candidate-head b { font: bold 16px monospace; color: #15443f; }
.candidate-head span { font-size: 10px; color: #7a8f8b; }
.candidate-head em { margin-left: auto; font-style: normal; font-size: 10px; background: #e2f3ec; color: #0d7a5c; padding: 3px 8px; }
.candidate-head em.no { background: #f0e3e2; color: #8a3a34; }
.check-chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 9px; }
.check-chips span { font-size: 9px; padding: 3px 7px; background: #f0e3e2; color: #8a3a34; }
.check-chips span.pass { background: #e2f3ec; color: #0d6b52; }
.check-chips small { margin-left: 5px; opacity: .75; }
.no-candidate { text-align: center; color: #8ba09c; font-size: 11px; padding: 30px 0; }
.batch-note { margin: 12px 0 0; font-size: 11px; color: #9a6b00; background: #fff7e0; padding: 8px 10px; }

@media (max-width: 1500px) {
  .takeover-grid { grid-template-columns: 1fr; }
  .event-rail { max-height: none; }
}
</style>
