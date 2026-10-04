<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { containerApi, operationApi } from '../api'

defineProps({ yardSelection: Object, zones: Array })

const gates = ref([])
const containers = ref([])
const instructions = ref([])
const containerNo = ref('')
const lifecycle = ref(null)
const activeNode = ref(0)
const loading = ref(false)

const lanes = computed(() => {
  const byLane = new Map()
  gates.value.forEach(gate => { if (!byLane.has(gate.laneNo)) byLane.set(gate.laneNo, gate) })
  return [...byLane.values()]
})
const currentInstructions = computed(() => instructions.value.filter(item => item.containerNo === containerNo.value))

async function load() {
  loading.value = true
  try {
    const [gateResult, containerResult, instructionResult] = await Promise.all([
      operationApi.gates(), containerApi.list(), operationApi.instructions()
    ])
    gates.value = gateResult.data
    containers.value = containerResult.data
    instructions.value = instructionResult.data
    containerNo.value = containers.value.find(item => item.containerNo === 'CAXU8801203')?.containerNo || containers.value[0]?.containerNo || ''
  } finally { loading.value = false }
}
async function queryLifecycle() {
  if (!containerNo.value) return
  lifecycle.value = (await containerApi.lifecycle(containerNo.value)).data
  activeNode.value = Math.max(0, lifecycle.value.nodes.length - 1)
}
function congestionClass(value) { return value === '拥堵' ? 'jam' : value === '缓行' ? 'slow' : 'clear' }
function time(value) { return value?.replace('T', ' ').slice(5, 16) || '--' }
function instructionName(value) { return ({ UNLOAD:'卸船', LOAD:'装船', SHIFT:'翻倒', PICKUP:'提箱' })[value] || value }
watch(containerNo, queryLifecycle)
onMounted(load)
</script>

<template>
  <section class="page-panel gate-page" v-loading="loading">
    <div class="panel-head">
      <div><div class="kicker">GATE FLOW / CONTAINER JOURNEY</div><h2>闸口车道流水 + 箱生命周期</h2><p>上方监看车道，下方按箱号追溯从进场、落位、翻倒到装船或提箱出场</p></div>
      <div class="gate-counter"><b>{{ lanes.length }}</b><span>条车道在线</span></div>
    </div>

    <div class="lane-strip">
      <article v-for="lane in lanes" :key="lane.laneNo" :class="['lane-capsule', congestionClass(lane.congestion)]" @click="containerNo = lane.containerNo">
        <div class="lane-direction"><span>{{ lane.direction === 'IN' ? '进' : '出' }}</span><b>{{ lane.direction === 'IN' ? '→' : '←' }}</b></div>
        <div class="lane-main"><small>{{ lane.laneNo }} · {{ lane.congestion }}</small><strong>{{ lane.plateNo }}</strong><em>{{ lane.containerNo }}</em></div>
        <i></i>
      </article>
    </div>

    <div class="journey-console glass-card">
      <div class="search-deck">
        <div><small>CONTAINER TRACE</small><b>箱号全链路查询</b></div>
        <el-select v-model="containerNo" filterable placeholder="输入或选择箱号" size="large" style="width:270px">
          <el-option v-for="box in containers" :key="box.containerNo" :label="`${box.containerNo} · ${box.type}`" :value="box.containerNo" />
        </el-select>
        <button class="scan-button" @click="queryLifecycle"><span></span>扫描生命周期</button>
        <div v-if="lifecycle" class="container-status"><small>当前态</small><b>{{ lifecycle.container.pickupStatus }}</b><em>{{ lifecycle.container.currentYardZone ? `${lifecycle.container.currentYardZone}-${lifecycle.container.currentBay}-${lifecycle.container.currentRow}-${lifecycle.container.currentTier}` : '待落位 / 已离场' }}</em></div>
      </div>

      <div v-if="lifecycle" class="journey-body">
        <div class="timeline-map">
          <div class="route-line"></div>
          <button v-for="(node,index) in lifecycle.nodes" :key="`${node.title}-${node.time}`" :class="['life-node',{ active: activeNode === index, done: index <= activeNode }]" @click="activeNode=index">
            <span class="node-pin"><i></i></span>
            <small>{{ time(node.time) }}</small>
            <b>{{ node.title }}</b>
          </button>
        </div>
        <div v-if="lifecycle.nodes[activeNode]" class="node-detail">
          <div class="detail-number">{{ String(activeNode + 1).padStart(2,'0') }}</div>
          <div><small>节点详情 / EVENT DETAIL</small><h3>{{ lifecycle.nodes[activeNode].title }}</h3><p>{{ lifecycle.nodes[activeNode].detail }}</p><time>{{ lifecycle.nodes[activeNode].time?.replace('T',' ') }}</time></div>
          <div class="detail-seal">TRACE<br>VERIFIED</div>
        </div>
      </div>
      <div v-else class="empty-journey">选择一个箱号开始追踪</div>
    </div>

    <div class="instruction-ticker glass-card" v-if="containerNo">
      <div class="ticker-title"><small>关联作业指令</small><b>{{ currentInstructions.length }} 条</b></div>
      <div v-for="item in currentInstructions" :key="item.id" class="instruction-pill">
        <span>{{ item.instructionNo }}</span><b>{{ instructionName(item.instructionType) }}</b><em :class="item.status.toLowerCase()">{{ item.status }}</em>
      </div>
      <div v-if="!currentInstructions.length" class="no-instruction">暂无关联指令</div>
    </div>
  </section>
</template>

<style scoped>
.gate-counter { width:110px;height:58px;background:#123e39;color:#fff;display:flex;align-items:center;justify-content:center;gap:8px;border-right:4px solid #f5c451; }.gate-counter b { font-size:26px; }.gate-counter span { width:38px;font-size:10px;color:#a9c8c4; }
.lane-strip { display:flex;gap:10px;overflow:auto;padding-bottom:12px; }.lane-capsule { min-width:205px;border:0;border-radius:38px;background:#fff;box-shadow:0 4px 14px rgba(26,70,64,.09);display:flex;align-items:center;padding:8px 12px 8px 8px;color:#284d49;position:relative; }.lane-capsule:hover { transform:translateY(-2px); }.lane-direction { width:50px;height:50px;border-radius:50%;display:grid;grid-template-columns:1fr 1fr;place-items:center;background:#e2f1ee;color:#00695c;font-size:11px; }.lane-direction b { font-size:17px; }.lane-main { padding-left:9px;text-align:left; }.lane-main small,.lane-main strong,.lane-main em { display:block; }.lane-main small { color:#78908c;font-size:9px; }.lane-main strong { font-size:13px;margin:3px 0; }.lane-main em { font:10px monospace;color:#08766c; }.lane-capsule > i { margin-left:auto;width:8px;height:8px;border-radius:50%; }.lane-capsule.clear > i { background:#27a67b;box-shadow:0 0 0 4px #dff4ec; }.lane-capsule.slow > i { background:#e4ad32;box-shadow:0 0 0 4px #fbf0d4; }.lane-capsule.jam > i { background:#d84e4e;box-shadow:0 0 0 4px #f8dddd; }
.journey-console { overflow:hidden; }.search-deck { display:flex;align-items:center;gap:15px;padding:15px 18px;background:#f8faf9;border-bottom:1px solid #d8e4e2; }.search-deck > div:first-child { width:145px; }.search-deck small,.search-deck b { display:block; }.search-deck small { font:9px monospace;color:#718984;letter-spacing:1px; }.search-deck b { margin-top:3px;font-size:13px; }.scan-button { height:40px;background:#00695c;color:white;border:0;padding:0 17px;border-radius:3px; }.scan-button span { display:inline-block;width:8px;height:8px;border:2px solid #f5c451;border-radius:50%;margin-right:7px; }.container-status { margin-left:auto;text-align:right;border-left:1px solid #ccd9d7;padding-left:16px; }.container-status b { color:#00695c;font-size:14px; }.container-status em { font-size:10px;font-style:normal;color:#738984; }
.journey-body { padding:26px 25px 20px; }.timeline-map { position:relative;display:flex;justify-content:space-between;min-height:105px; }.route-line { position:absolute;left:3%;right:3%;top:24px;border-top:2px dashed #b8cbc8; }.life-node { flex:1;border:0;background:transparent;position:relative;z-index:1;text-align:center;color:#7b8f8b; }.node-pin { width:48px;height:48px;display:grid;place-items:center;background:white;border:2px solid #bfd0cd;border-radius:50%;margin:0 auto 8px; }.node-pin i { width:10px;height:10px;background:#c5d2d0;border-radius:50%; }.life-node.done .node-pin { border-color:#00897b;background:#e9f5f3; }.life-node.done .node-pin i { background:#00897b; }.life-node.active .node-pin { box-shadow:0 0 0 6px rgba(245,196,81,.26);border-color:#f0b72d; }.life-node small,.life-node b { display:block; }.life-node small { font:9px monospace; }.life-node b { font-size:11px;margin-top:3px; }.life-node.active b { color:#00695c; }
.node-detail { min-height:115px;background:#113d39;color:white;display:flex;align-items:center;padding:16px 22px;position:relative;overflow:hidden; }.detail-number { font:bold 66px monospace;color:rgba(255,255,255,.08);margin-right:18px; }.node-detail small { font:9px monospace;color:#80bdb5;letter-spacing:2px; }.node-detail h3 { margin:5px 0;font-size:18px; }.node-detail p { margin:0;color:#bfd4d1;font-size:12px; }.node-detail time { display:block;margin-top:7px;color:#f5c451;font:10px monospace; }.detail-seal { margin-left:auto;border:1px solid rgba(245,196,81,.45);color:#f5c451;padding:9px;text-align:center;font:9px monospace;transform:rotate(-5deg); }
.empty-journey { padding:70px;text-align:center;color:#899b98; }
.instruction-ticker { margin-top:12px;padding:12px 15px;display:flex;align-items:center;gap:8px;overflow:auto; }.ticker-title { min-width:100px;border-right:1px solid #d5e1df; }.ticker-title small,.ticker-title b { display:block; }.ticker-title small { font-size:9px;color:#758b87; }.ticker-title b { color:#00695c;margin-top:3px; }.instruction-pill { min-width:170px;background:#eef4f3;padding:8px 10px;display:grid;grid-template-columns:1fr auto;gap:3px 8px;border-radius:3px; }.instruction-pill span { grid-column:1/-1;font:9px monospace;color:#68817d; }.instruction-pill b { font-size:11px; }.instruction-pill em { font-size:9px;font-style:normal;color:#a16e00; }.instruction-pill em.completed { color:#007b5f; }.instruction-pill em.paused { color:#b26a00;font-weight:700; }.no-instruction { color:#839591;font-size:11px; }
</style>
