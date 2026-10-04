<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { craneApi } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const cranes = ref([])
const selected = ref(null)
const shifts = ref([])
const dialog = ref(false)
const loading = ref(false)
const form = reactive({ driverName: '', shiftStart: '', shiftEnd: '', handledBoxes: 0, workHours: 1 })
const statusName = { WORKING: '作业', STANDBY: '待机', FAULT: '故障' }

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
onMounted(load)
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
.efficiency-track { height:7px;background:#e4ecea;margin:12px 0 17px;border-radius:8px; }.efficiency-track span { display:block;height:100%;background:linear-gradient(90deg,#00695c,#20aa98);border-radius:8px; }.efficiency-track i { position:absolute;top:-3px;width:2px;height:13px;background:#f0b92e; }
.sparkline-box { background:#f5f8f7;padding:9px 9px 5px;display:flex;align-items:center; }.sparkline-box div { width:90px; }.sparkline-box small,.sparkline-box b { display:block; }.sparkline-box small { font-size:9px;color:#728884; }.sparkline-box b { margin-top:5px;font-size:12px; }.sparkline-box svg { flex:1;height:54px; }
.machine-spec { display:flex;justify-content:space-between;margin-top:13px;font-size:9px;color:#78908b;border-top:1px solid #e4ecea;padding-top:10px; }.machine-spec b { color:#315c57; }
.console-status { background:#eef5f3;padding:11px;display:flex;align-items:center;justify-content:space-between;margin-bottom:13px; }.shift-form { display:grid;grid-template-columns:1fr 1fr;gap:0 12px; }.shift-form .el-form-item:first-child { grid-column:1 / -1; }.recent-shifts { display:flex;gap:6px;overflow:auto; }.recent-shifts span { min-width:105px;background:#163e3a;color:#c8dedb;padding:8px;font-size:9px; }.recent-shifts b { display:block;color:#fff;margin-bottom:3px; }
@media (max-width:1400px){.crane-grid{grid-template-columns:repeat(3,1fr)}}
</style>
