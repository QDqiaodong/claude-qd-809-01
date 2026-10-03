<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPreparations, getSamples, inputResult, moveSample, releaseSample, retestSample } from '../api'

const bloodType = inject('bloodType')
const samples = ref([])
const preparations = ref([])
const loading = ref(false)
const draggingId = ref(null)
const dragOverLane = ref('')
const detail = ref(null)
const detailVisible = ref(false)

const lanes = [
  { key: 'PENDING', title: '待检', cls: 'pending', desc: '标本已贴码，等待上机' },
  { key: 'TESTING', title: '检测中', cls: 'testing', desc: 'ALT / 血红蛋白 / HBV / HCV / HIV / 梅毒' },
  { key: 'RELEASED', title: '已放行', cls: 'released', desc: '复核通过后自动生成成分制备单' }
]

const retestItems = [
  { code: 'ALT', name: 'ALT', unit: 'U/L' },
  { code: 'HB', name: '血红蛋白', unit: 'g/L' },
  { code: 'HBV', name: 'HBV', unit: '' },
  { code: 'HCV', name: 'HCV', unit: '' },
  { code: 'HIV', name: 'HIV', unit: '' },
  { code: 'SYPHILIS', name: '梅毒', unit: '' }
]

const grouped = computed(() => ({
  PENDING: samples.value.filter((s) => s.status === 'PENDING'),
  TESTING: samples.value.filter((s) => s.status === 'TESTING'),
  RELEASED: samples.value.filter((s) => s.status === 'RELEASED'),
  SCRAPPED: samples.value.filter((s) => s.status === 'SCRAPPED')
}))

// 待复核的复测轮次（复核人放行前必须定夺）
const pendingRetests = (sample) => (sample?.retests || []).filter((r) => r.adopted === null)

// ---------------- HTML5 原生拖拽 ----------------
function onDragStart(sample, event) {
  draggingId.value = sample.id
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', String(sample.id))
}

function onDragEnd() {
  draggingId.value = null
  dragOverLane.value = ''
}

function onDragOver(lane, event) {
  event.preventDefault()
  event.dataTransfer.dropEffect = 'move'
  dragOverLane.value = lane
}

function onDragLeave() {
  dragOverLane.value = ''
}

async function onDrop(lane) {
  const id = draggingId.value
  dragOverLane.value = ''
  draggingId.value = null
  if (!id) return
  const sample = samples.value.find((s) => s.id === id)
  if (!sample || sample.status === lane) return
  await doMove(sample, lane)
}

async function doMove(sample, lane) {
  if (lane === 'RELEASED') {
    // 存在待复核的复测结果时，必须先在放行复核弹窗里定夺采用/拒绝并记录理由
    if (pendingRetests(sample).length) {
      openRelease(sample)
      return
    }
    try {
      const { value } = await ElMessageBox.prompt(
        `标本 ${sample.barcode}（${sample.bloodType}）放入「已放行」将即时触发成分制备，请输入复核人：`,
        '选择复核人',
        { inputPlaceholder: '例如 林岚', inputValue: '林岚' }
      )
      const res = await releaseSample(sample.id, { reviewer: value })
      ElMessage.success(res.message || '已放行')
    } catch (e) {
      if (e !== 'cancel' && e.message) ElMessage.error(e.message)
      return
    }
  } else {
    try {
      const res = await moveSample(sample.id, lane)
      ElMessage.success(res.message || '状态已更新')
    } catch (e) {
      ElMessage.error(e.message)
      return
    }
  }
  load()
}

// ---------------- 检验结果录入 ----------------
const resultVisible = ref(false)
const resultForm = ref({ id: null, altValue: 22, hbValue: 135, hbvResult: 'NEGATIVE', hcvResult: 'NEGATIVE', hivResult: 'NEGATIVE', syphilisResult: 'NEGATIVE', operator: '林岚' })

function openResult(sample) {
  resultForm.value = {
    id: sample.id,
    altValue: 22,
    hbValue: 135,
    hbvResult: 'NEGATIVE',
    hcvResult: 'NEGATIVE',
    hivResult: 'NEGATIVE',
    syphilisResult: 'NEGATIVE',
    operator: sample.reviewer || '林岚'
  }
  resultVisible.value = true
}

async function submitResult() {
  try {
    const res = await inputResult(resultForm.value.id, resultForm.value)
    ElMessage.success(res.message || '结果已录入')
    resultVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 复测 ----------------
const retestVisible = ref(false)
const retestForm = ref({ id: null, barcode: '', itemCode: 'ALT', numValue: 30, serologyValue: 'NEGATIVE', reason: '', operator: '林岚' })

const currentItem = computed(() => {
  if (!detail.value) return null
  return (detail.value.items || []).find((i) => i.code === retestForm.value.itemCode) || null
})

function openRetest(sample) {
  retestForm.value = {
    id: sample.id,
    barcode: sample.barcode,
    itemCode: 'ALT',
    numValue: 30,
    serologyValue: 'NEGATIVE',
    reason: '',
    operator: sample.reviewer || '林岚'
  }
  retestVisible.value = true
}

async function submitRetest() {
  const f = retestForm.value
  if (!f.reason.trim()) {
    ElMessage.warning('请填写复测原因')
    return
  }
  const numeric = f.itemCode === 'ALT' || f.itemCode === 'HB'
  try {
    const res = await retestSample(f.id, {
      itemCode: f.itemCode,
      resultValue: numeric ? String(f.numValue) : f.serologyValue,
      reason: f.reason,
      operator: f.operator
    })
    ElMessage.success(res.message || '复测结果已提交')
    retestVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 放行复核（采用/拒绝复测结果） ----------------
const releaseVisible = ref(false)
const releaseForm = ref({ sample: null, reviewer: '林岚', retestAdopt: true, retestReason: '' })

const releasePending = computed(() => pendingRetests(releaseForm.value.sample))

function openRelease(sample) {
  releaseForm.value = { sample, reviewer: '林岚', retestAdopt: true, retestReason: '' }
  releaseVisible.value = true
}

async function submitRelease() {
  const f = releaseForm.value
  if (!f.reviewer.trim()) {
    ElMessage.warning('请填写复核人')
    return
  }
  if (!f.retestReason.trim()) {
    ElMessage.warning('请记录' + (f.retestAdopt ? '采用' : '拒绝') + '复测结果的理由')
    return
  }
  try {
    const res = await releaseSample(f.sample.id, {
      reviewer: f.reviewer,
      retestAdopt: f.retestAdopt,
      retestReason: f.retestReason
    })
    ElMessage.success(res.message || '已放行')
    releaseVisible.value = false
    detailVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 明细 ----------------
function openDetail(sample) {
  detail.value = sample
  detailVisible.value = true
}

function fmtMoment(value) {
  return value ? String(value).replace('T', ' ').slice(5, 16) : '--'
}

function verdictColor(verdict) {
  if (verdict === '合格') return '#2e7d32'
  if (verdict === '不合格') return '#c62828'
  return '#8d6e63'
}

async function load() {
  loading.value = true
  try {
    const [s, p] = await Promise.all([getSamples(bloodType.value), getPreparations(bloodType.value)])
    samples.value = s.data || []
    preparations.value = (p.data || []).slice(0, 8)
    if (detailVisible.value && detail.value) {
      detail.value = samples.value.find((x) => x.id === detail.value.id) || detail.value
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(bloodType, load)
</script>

<template>
  <div v-loading="loading">
    <div class="bc-alert">
      三条轨道分别代表 <b>待检 / 检测中 / 已放行</b>：按住标本管拖动即可流转；落入「已放行」需选择复核人，并即时触发成分制备（领域事件）。
      检测中或报废的标本可<b>发起复测</b>：原结果保留不覆盖，按轮次追溯，放行时复核人须定夺采用或拒绝复测结果。
    </div>

    <div
      v-for="lane in lanes"
      :key="lane.key"
      class="bc-lane"
      :class="lane.cls"
    >
      <div class="bc-lane-head">
        <span class="bc-lane-title">{{ lane.title }}</span>
        <span class="bc-lane-count">{{ grouped[lane.key].length }}</span>
        <span class="bc-panel-sub">{{ lane.desc }}</span>
      </div>
      <div
        class="bc-lane-body"
        :class="{ dragover: dragOverLane === lane.key }"
        @dragover="onDragOver(lane.key, $event)"
        @dragleave="onDragLeave"
        @drop="onDrop(lane.key)"
      >
        <div
          v-for="s in grouped[lane.key]"
          :key="s.id"
          class="bc-tube"
          :class="{ dragging: draggingId === s.id, positive: s.currentVerdict === '不合格' }"
          draggable="true"
          @dragstart="onDragStart(s, $event)"
          @dragend="onDragEnd"
          @click="openDetail(s)"
        >
          <div class="barcode"></div>
          <div class="info">
            <div class="code">{{ s.barcode }}</div>
            <div class="who">
              {{ s.donorName }} · {{ s.reviewer ? '复核 ' + s.reviewer : '未复核' }}<template v-if="s.retests && s.retests.length"> · 复测×{{ s.retests.length }}</template>
            </div>
          </div>
          <div class="blood">{{ s.bloodType }}</div>
        </div>
        <div v-if="!grouped[lane.key].length" class="bc-empty">拖动标本管到这里</div>
      </div>
    </div>

    <!-- 已报废标本（可发起复测） -->
    <div class="bc-lane scrapped">
      <div class="bc-lane-head">
        <span class="bc-lane-title">已报废</span>
        <span class="bc-lane-count">{{ grouped.SCRAPPED.length }}</span>
        <span class="bc-panel-sub">检验不合格（ALT 超标 / 血红蛋白偏低 / 传染病阳性）；点开可发起复测，复测全部合格后回到检测中</span>
      </div>
      <div class="bc-lane-body">
        <div v-for="s in grouped.SCRAPPED" :key="s.id" class="bc-tube positive" @click="openDetail(s)">
          <div class="barcode"></div>
          <div class="info">
            <div class="code">{{ s.barcode }}</div>
            <div class="who">
              {{ s.donorName }} · 判定不合格<template v-if="s.retests && s.retests.length"> · 复测×{{ s.retests.length }}</template>
            </div>
          </div>
          <div class="blood">{{ s.bloodType }}</div>
        </div>
        <div v-if="!grouped.SCRAPPED.length" class="bc-empty">暂无报废标本</div>
      </div>
    </div>

    <div class="bc-panel">
      <div class="bc-panel-head">
        <div class="bc-panel-title">最近生成的成分制备批次</div>
        <div class="spacer"></div>
        <span class="bc-panel-sub">由标本放行事件自动写入</span>
      </div>
      <el-table :data="preparations" size="small" stripe>
        <el-table-column prop="batchNo" label="批号" width="130" />
        <el-table-column prop="collectNo" label="全血来源" width="120" />
        <el-table-column prop="bloodType" label="血型" width="60" />
        <el-table-column label="红细胞" width="110">
          <template #default="{ row }">{{ row.rbcBags }} 袋 / {{ row.rbcVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column label="血浆" width="110">
          <template #default="{ row }">{{ row.plasmaBags }} 袋 / {{ row.plasmaVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column label="血小板" width="110">
          <template #default="{ row }">{{ row.plateletBags }} 袋 / {{ row.plateletVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column label="冷沉淀" width="110">
          <template #default="{ row }">{{ row.cryoBags }} 袋 / {{ row.cryoVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column prop="operator" label="制备人" width="110" />
      </el-table>
    </div>

    <!-- 标本明细：当前判定 + 复测轮次 -->
    <el-drawer v-model="detailVisible" title="标本检验明细" size="620px">
      <div v-if="detail">
        <div class="bc-alert">
          {{ detail.barcode }} · {{ detail.donorName }} · {{ detail.bloodType }} ·
          <b>{{ detail.statusName }}</b> · 当前判定
          <b :style="{ color: verdictColor(detail.currentVerdict) }">{{ detail.currentVerdict }}</b>
        </div>
        <el-table :data="detail.items" size="small">
          <el-table-column prop="name" label="项目" width="90" />
          <el-table-column label="当前结果" width="110">
            <template #default="{ row }">{{ row.value }}{{ row.unit }}</template>
          </el-table-column>
          <el-table-column prop="conclusion" label="判定" width="80" />
          <el-table-column prop="source" label="来源" />
        </el-table>

        <template v-if="detail.retests && detail.retests.length">
          <div class="bc-retest-title">复测轮次（原初检结果保留不覆盖）</div>
          <el-table :data="detail.retests" size="small">
            <el-table-column prop="itemName" label="项目" width="70" />
            <el-table-column label="轮次" width="90">
              <template #default="{ row }">
                第{{ row.roundNo }}轮
                <el-tag v-if="row.current" size="small" type="success" effect="plain">当前</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="新结果" width="90">
              <template #default="{ row }">{{ row.value }}{{ row.unit }}</template>
            </el-table-column>
            <el-table-column prop="conclusionName" label="判定" width="60" />
            <el-table-column prop="reason" label="复测原因" min-width="150" show-overflow-tooltip />
            <el-table-column prop="operator" label="检测人" width="70" />
            <el-table-column label="复核定夺" width="170">
              <template #default="{ row }">
                <el-tag v-if="row.adopted === null" size="small" type="warning">待复核</el-tag>
                <template v-else>
                  <el-tag :type="row.adopted ? 'success' : 'danger'" size="small">{{ row.adopted ? '采用' : '拒绝' }}</el-tag>
                  <span class="bc-adopt-reason" :title="row.adoptReason">{{ row.reviewer }}：{{ row.adoptReason }}</span>
                </template>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="100">
              <template #default="{ row }">{{ fmtMoment(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </template>

        <div style="margin-top: 14px; display: flex; gap: 8px; flex-wrap: wrap">
          <el-button
            v-if="detail.status !== 'RELEASED' && !(detail.retests && detail.retests.length)"
            type="primary"
            @click="openResult(detail)"
          >
            录入/重录结果
          </el-button>
          <el-button
            v-if="detail.status === 'TESTING' || detail.status === 'SCRAPPED'"
            type="warning"
            @click="openRetest(detail)"
          >
            发起复测
          </el-button>
          <el-button
            v-if="detail.status === 'TESTING'"
            type="success"
            @click="doMove(detail, 'RELEASED')"
          >
            放行并制备
          </el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 检验结果录入 -->
    <el-dialog v-model="resultVisible" title="录入检验结果" width="520px">
      <el-form label-width="92px">
        <el-form-item label="ALT">
          <el-input-number v-model="resultForm.altValue" :min="1" :max="200" :step="0.1" />
          <span style="margin-left: 8px; color: #8d6e63">U/L（&gt; 40 判不合格）</span>
        </el-form-item>
        <el-form-item label="血红蛋白">
          <el-input-number v-model="resultForm.hbValue" :min="60" :max="200" :step="0.5" />
          <span style="margin-left: 8px; color: #8d6e63">g/L</span>
        </el-form-item>
        <el-form-item label="HBV">
          <el-radio-group v-model="resultForm.hbvResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="HCV">
          <el-radio-group v-model="resultForm.hcvResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="HIV">
          <el-radio-group v-model="resultForm.hivResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="梅毒">
          <el-radio-group v-model="resultForm.syphilisResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检验人">
          <el-input v-model="resultForm.operator" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resultVisible = false">取消</el-button>
        <el-button type="primary" @click="submitResult">保存结果</el-button>
      </template>
    </el-dialog>

    <!-- 发起复测 -->
    <el-dialog v-model="retestVisible" title="发起复测" width="500px">
      <div class="bc-alert">
        {{ retestForm.barcode }}：原初检结果保留不覆盖，本次提交将新增一轮复测记录
      </div>
      <el-form label-width="92px">
        <el-form-item label="复测项目">
          <el-select v-model="retestForm.itemCode" style="width: 200px">
            <el-option v-for="item in retestItems" :key="item.code" :label="item.name" :value="item.code" />
          </el-select>
          <span v-if="currentItem" style="margin-left: 8px; color: #8d6e63">
            当前 {{ currentItem.value }}{{ currentItem.unit }}（{{ currentItem.conclusion }}）
          </span>
        </el-form-item>
        <el-form-item label="新结果">
          <template v-if="retestForm.itemCode === 'ALT'">
            <el-input-number v-model="retestForm.numValue" :min="1" :max="200" :step="0.1" />
            <span style="margin-left: 8px; color: #8d6e63">U/L（&gt; 40 判不合格）</span>
          </template>
          <template v-else-if="retestForm.itemCode === 'HB'">
            <el-input-number v-model="retestForm.numValue" :min="60" :max="200" :step="0.5" />
            <span style="margin-left: 8px; color: #8d6e63">g/L</span>
          </template>
          <el-radio-group v-else v-model="retestForm.serologyValue">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="复测原因">
          <el-input
            v-model="retestForm.reason"
            type="textarea"
            :rows="2"
            placeholder="例如：初检 ALT 临界，怀疑标本溶血，重新离心后复测"
          />
        </el-form-item>
        <el-form-item label="检测人">
          <el-input v-model="retestForm.operator" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="retestVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRetest">提交复测结果</el-button>
      </template>
    </el-dialog>

    <!-- 放行复核：采用/拒绝复测结果 -->
    <el-dialog v-model="releaseVisible" title="放行复核" width="640px">
      <div class="bc-alert">
        标本 {{ releaseForm.sample?.barcode }} 存在 {{ releasePending.length }} 条待复核的复测结果，
        放行判定以各项目<b>最后一次有效结果</b>为准，复核人必须定夺采用或拒绝并记录理由。
      </div>
      <el-table :data="releasePending" size="small">
        <el-table-column prop="itemName" label="项目" width="80" />
        <el-table-column label="轮次" width="90">
          <template #default="{ row }">复测第{{ row.roundNo }}轮</template>
        </el-table-column>
        <el-table-column label="新结果" width="100">
          <template #default="{ row }">{{ row.value }}{{ row.unit }}</template>
        </el-table-column>
        <el-table-column prop="conclusionName" label="判定" width="70" />
        <el-table-column prop="reason" label="复测原因" show-overflow-tooltip />
        <el-table-column prop="operator" label="检测人" width="80" />
      </el-table>
      <el-form label-width="92px" style="margin-top: 12px">
        <el-form-item label="复测定夺">
          <el-radio-group v-model="releaseForm.retestAdopt">
            <el-radio :value="true">采用复测结果</el-radio>
            <el-radio :value="false">拒绝复测结果（回退到上一轮有效结果）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="定夺理由">
          <el-input
            v-model="releaseForm.retestReason"
            type="textarea"
            :rows="2"
            placeholder="记录采用或拒绝复测结果的理由"
          />
        </el-form-item>
        <el-form-item label="复核人">
          <el-input v-model="releaseForm.reviewer" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="releaseVisible = false">取消</el-button>
        <el-button type="success" @click="submitRelease">确认放行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bc-retest-title {
  margin: 14px 0 6px;
  font-weight: 600;
  color: #5d4037;
}
.bc-adopt-reason {
  margin-left: 4px;
  color: #8d6e63;
  font-size: 12px;
  display: inline-block;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
</style>
