<template>
  <div class="page">
    <div class="bar">
      <div class="bar-left">
        <h2>桩位巡检</h2>
        <span class="tip">
          按站点建巡检批次，逐桩登记<b>桩锁 / 供电 / 通信</b>结果。空桩故障立即隔离；
          桩上有车则<b>待隔离</b>（车仍可借走/调出、新车禁入，车离开自动转已隔离）；
          故障处置完成后必须<b>复验</b>，复验通过按真实占车恢复。
        </span>
      </div>
      <div class="bar-right">
        <el-select
          v-model="stationFilter"
          placeholder="全部站点"
          clearable
          filterable
          style="width: 190px"
          @change="load"
        >
          <el-option v-for="s in stations" :key="s.id" :label="`${s.code} · ${s.name}`" :value="s.id" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option label="巡检中" value="IN_PROGRESS" />
          <el-option label="已完成" value="COMPLETED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">+ 新建巡检批次</el-button>
      </div>
    </div>

    <!-- 列表 -->
    <el-table :data="batches" v-loading="loading" stripe>
      <el-table-column prop="batchNo" label="批次号" width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row.id)">{{ row.batchNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="站点" min-width="170">
        <template #default="{ row }">
          <span>{{ row.stationCode }} · {{ row.stationName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'IN_PROGRESS' ? 'warning' : 'success'" effect="dark" size="small">
            {{ row.statusName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="检查进度" width="130">
        <template #default="{ row }">
          <span :class="{ 'num-fault': row.faultCount > 0 }">{{ row.checkedCount }} / {{ row.totalCount }}</span>
          <el-tag v-if="row.faultCount > 0" size="small" type="danger" effect="plain" class="ml6">
            故障 {{ row.faultCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="站内隔离现状" min-width="230">
        <template #default="{ row }">
          <el-tag size="small" :type="row.stationPendingIsolatedCount ? 'warning' : 'info'" effect="plain" class="ml0">
            待隔离 {{ row.stationPendingIsolatedCount }}
          </el-tag>
          <el-tag size="small" :type="row.stationIsolatedCount ? 'danger' : 'info'" effect="plain" class="ml6">
            已隔离 {{ row.stationIsolatedCount }}
          </el-tag>
          <el-tag size="small" :type="row.stationPendingRecheckCount ? 'primary' : 'info'" effect="plain" class="ml6">
            待复验 {{ row.stationPendingRecheckCount }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="建单人" width="90" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
    </el-table>

    <!-- ================= 新建批次 ================= -->
    <el-dialog v-model="createVisible" title="新建巡检批次" width="480px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="批次一创建，本批次的桩位清单和当时每个桩位的占车情况就被固定下来；之后新增的桩位不会混进本批次。"
        class="mb12"
      />
      <el-form label-width="82px">
        <el-form-item label="巡检站点">
          <el-select v-model="createForm.stationId" placeholder="选择站点" filterable style="width: 100%">
            <el-option
              v-for="s in stations"
              :key="s.id"
              :label="`${s.code} · ${s.name}（桩位 ${s.piles.length} 个）`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="建单人">
          <el-input v-model="createForm.createdBy" placeholder="运营人员姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建并开始巡检</el-button>
      </template>
    </el-dialog>

    <!-- ================= 批次详情（逐桩检查 / 处置 / 复验） ================= -->
    <el-drawer v-model="detailVisible" :title="detail ? `巡检批次 ${detail.batchNo}` : '巡检批次详情'" size="82%">
      <div v-if="detail" class="detail-wrap" v-loading="detailLoading">
        <div class="detail-head">
          <div>
            <h3>
              {{ detail.stationCode }} · {{ detail.stationName }}
              <el-tag :type="detail.status === 'IN_PROGRESS' ? 'warning' : 'success'" effect="dark" class="ml6">
                {{ detail.statusName }}
              </el-tag>
            </h3>
            <div class="sub">
              建单人 <b>{{ detail.createdBy }}</b> · 建单时间 {{ fmt(detail.createdAt) }}
              <template v-if="detail.completedAt"> · 完成时间 {{ fmt(detail.completedAt) }}</template>
            </div>
            <div class="sub">
              固定桩位 <b>{{ detail.totalCount }}</b> 个 · 已检查
              <b :class="{ 'num-fault': detail.faultCount > 0 }">{{ detail.checkedCount }}</b> 个 ·
              故障 <b class="num-fault">{{ detail.faultCount }}</b> 个
            </div>
            <div v-if="detail.remark" class="sub muted">{{ detail.remark }}</div>
          </div>
          <div class="detail-actions">
            <el-button @click="refreshDetail()">刷新最新状态</el-button>
          </div>
        </div>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="隔离规则：空桩故障立即隔离；桩上有车只进待隔离（不清车辆落点，车可借走/调出，任何新车不能进入），车离开自动转已隔离。复验通过时按桩位真实占车恢复为空闲或占用，不能手工指定。"
          class="rule-alert"
        />

        <el-table :data="detail.items" size="small" border>
          <el-table-column label="桩位" width="80">
            <template #default="{ row }">
              <b>{{ row.pileNo }}</b>
            </template>
          </el-table-column>
          <el-table-column label="建单时占车" width="140">
            <template #default="{ row }">
              <span v-if="row.snapshotBikeCode" class="bike-then">🚲 {{ row.snapshotBikeCode }}</span>
              <span v-else class="muted">空桩</span>
            </template>
          </el-table-column>
          <el-table-column label="检查结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="resultTagType(row.result)" effect="dark">
                {{ row.resultName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="桩锁/供电/通信" width="170">
            <template #default="{ row }">
              <template v-if="row.result === 'PENDING'">
                <span class="muted">待检查</span>
              </template>
              <template v-else>
                <span class="tri"><i :class="triClass(row.lockStatus)">{{ row.lockStatusName }}</i></span>
                <span class="tri"><i :class="triClass(row.powerStatus)">{{ row.powerStatusName }}</i></span>
                <span class="tri"><i :class="triClass(row.commStatus)">{{ row.commStatusName }}</i></span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="故障等级" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.faultLevel" size="small" :type="levelTagType(row.faultLevel)" effect="plain">
                {{ row.faultLevelName }}
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="隔离形态 / 桩位现状" width="160">
            <template #default="{ row }">
              <el-tag
                v-if="row.isolatedMode"
                size="small"
                :type="row.isolatedMode === 'ISOLATED' ? 'danger' : 'warning'"
                effect="dark"
              >{{ row.isolatedModeName }}</el-tag>
              <span class="current">
                现状：
                <b :class="pileStatusClass(row.currentPileStatus)">{{ row.currentPileStatusName }}</b>
              </span>
              <div v-if="row.occupancyChanged && row.result !== 'PENDING'" class="changed">
                占车已变化<template v-if="row.currentBikeCode">（现挂 {{ row.currentBikeCode }}）</template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="检查人/时间" width="150">
            <template #default="{ row }">
              <div v-if="row.checkerName">{{ row.checkerName }}</div>
              <div v-if="row.checkedAt" class="muted fs11">{{ fmt(row.checkedAt) }}</div>
              <span v-if="!row.checkerName" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="处置状态 / 复验结论" min-width="230">
            <template #default="{ row }">
              <template v-if="row.handling">
                <div class="h-line">
                  <el-tag size="small" :type="handlingTagType(row.handling.status)" effect="plain">
                    {{ row.handling.statusName }}
                  </el-tag>
                  <span class="fs11 muted">{{ row.handling.handlingNo }}</span>
                </div>
                <div v-if="row.handling.handlerName" class="fs11">
                  处置：{{ row.handling.handlerName }} · {{ row.handling.handleNote || '—' }}
                </div>
                <div v-if="row.handling.recheckedAt" class="fs11">
                  复验：{{ row.handling.recheckerName }} ·
                  <b :class="row.handling.recheckPass ? 'ok-text' : 'num-fault'">
                    {{ row.handling.recheckPass ? '通过' : '不通过' }}
                  </b>
                  <span v-if="row.handling.recheckNote">（{{ row.handling.recheckNote }}）</span>
                </div>
              </template>
              <span v-else-if="row.result === 'FAULT'" class="muted fs11">处置单生成中…</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.result === 'PENDING'"
                size="small" type="primary"
                @click="openCheck(row)"
              >逐桩检查</el-button>
              <template v-if="row.handling">
                <el-button
                  v-if="canHandle(row.handling.status)"
                  size="small" type="warning" plain
                  @click="openHandle(row)"
                >故障处置</el-button>
                <el-button
                  v-if="canRecheck(row.handling.status)"
                  size="small" type="success" plain
                  @click="openRecheck(row)"
                >复验</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 逐桩检查 -->
    <el-dialog v-model="checkVisible" :title="checking ? `逐桩检查 · ${checking.pileNo}` : '逐桩检查'" width="520px">
      <div v-if="checking" class="check-box">
        <div class="snap">
          建单时：
          <b v-if="checking.snapshotBikeCode">桩上有 {{ checking.snapshotBikeCode }}</b>
          <b v-else>空桩</b>
          ｜ 当前：
          <b :class="pileStatusClass(checking.currentPileStatus)">{{ checking.currentPileStatusName }}</b>
          <template v-if="checking.currentBikeCode">（{{ checking.currentBikeCode }}）</template>
          <template v-else>（无车）</template>
          <el-tag v-if="checking.occupancyChanged" size="small" type="warning" effect="plain" class="ml6">
            占车已变化，将按最新事实判定
          </el-tag>
        </div>
        <el-form label-width="70px">
          <el-form-item label="桩锁">
            <el-radio-group v-model="checkForm.lockStatus">
              <el-radio value="NORMAL">正常</el-radio>
              <el-radio value="FAULT">故障</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="供电">
            <el-radio-group v-model="checkForm.powerStatus">
              <el-radio value="NORMAL">正常</el-radio>
              <el-radio value="FAULT">故障</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="通信">
            <el-radio-group v-model="checkForm.commStatus">
              <el-radio value="NORMAL">正常</el-radio>
              <el-radio value="FAULT">故障</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="checkIsFault" label="故障等级">
            <el-radio-group v-model="checkForm.faultLevel">
              <el-radio value="LOW">低</el-radio>
              <el-radio value="MID">中</el-radio>
              <el-radio value="HIGH">高</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="checkIsFault ? '故障描述' : '备注'">
            <el-input v-model="checkForm.faultDesc" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
          <el-form-item label="检查人">
            <el-input v-model="checkForm.checkerName" placeholder="运营人员姓名" maxlength="32" />
          </el-form-item>
        </el-form>
        <el-alert
          v-if="checkIsFault"
          :title="faultPreviewHint"
          :type="checking.currentBikeCode ? 'warning' : 'error'"
          :closable="false" show-icon
        />
      </div>
      <template #footer>
        <el-button @click="checkVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doCheck">提交检查结果</el-button>
      </template>
    </el-dialog>

    <!-- 故障处置 -->
    <el-dialog v-model="handleVisible" title="故障处置" width="460px">
      <div v-if="handlingRow" class="act-box">
        <p>处置单 <b>{{ handlingRow.handling.handlingNo }}</b>（桩位 {{ handlingRow.pileNo }} ·
          {{ handlingRow.handling.faultLevelName }}危）</p>
        <p class="muted fs12">{{ handlingRow.handling.faultDesc }}</p>
        <el-form label-width="70px">
          <el-form-item label="处置人">
            <el-input v-model="handleForm.handlerName" placeholder="处置人姓名" maxlength="32" />
          </el-form-item>
          <el-form-item label="处置说明">
            <el-input v-model="handleForm.handleNote" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
        </el-form>
        <el-alert type="warning" :closable="false" show-icon
                  title="提交后桩位继续隔离，必须经过复验通过才会恢复可用。" />
      </div>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="doHandle">提交处置</el-button>
      </template>
    </el-dialog>

    <!-- 复验 -->
    <el-dialog v-model="recheckVisible" title="复验" width="480px">
      <div v-if="recheckRow" class="act-box">
        <p>处置单 <b>{{ recheckRow.handling.handlingNo }}</b>（桩位 {{ recheckRow.pileNo }}）</p>
        <el-form label-width="70px">
          <el-form-item label="复验结论">
            <el-radio-group v-model="recheckForm.pass">
              <el-radio :value="true">复验通过</el-radio>
              <el-radio :value="false">复验不通过</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="复验人">
            <el-input v-model="recheckForm.recheckerName" placeholder="复验人姓名" maxlength="32" />
          </el-form-item>
          <el-form-item :label="recheckForm.pass ? '复验说明' : '不通过原因'">
            <el-input v-model="recheckForm.recheckNote" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
        </el-form>
        <el-alert
          :type="recheckForm.pass ? 'success' : 'error'"
          :closable="false"
          show-icon
          :title="recheckForm.pass
            ? `通过后桩位将按真实占车恢复（当前${recheckRow.currentBikeCode ? '占用：' + recheckRow.currentBikeCode : '空闲'}），不能手工指定空闲或占用。`
            : '不通过必须填写原因，桩位继续保持隔离，可再次处置后复验。'"
        />
      </div>
      <template #footer>
        <el-button @click="recheckVisible = false">取消</el-button>
        <el-button :type="recheckForm.pass ? 'success' : 'danger'" :loading="submitting" @click="doRecheck">
          提交复验
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { inspectionApi, stationApi } from '../api'

const route = useRoute()
const nav = useRouter()

const loading = ref(false)
const batches = ref([])
const stations = ref([])
const stationFilter = ref(route.query.stationId ? Number(route.query.stationId) : null)
const statusFilter = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ stationId: null, createdBy: '', remark: '' })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const checkVisible = ref(false)
const handleVisible = ref(false)
const recheckVisible = ref(false)
const submitting = ref(false)
const checking = ref(null)
const checkForm = reactive({ lockStatus: 'NORMAL', powerStatus: 'NORMAL', commStatus: 'NORMAL',
  faultLevel: 'MID', faultDesc: '', checkerName: '' })
const handlingRow = ref(null)
const handleForm = reactive({ handlerName: '', handleNote: '' })
const recheckRow = ref(null)
const recheckForm = reactive({ pass: true, recheckerName: '', recheckNote: '' })

const checkIsFault = computed(() =>
  checkForm.lockStatus === 'FAULT' || checkForm.powerStatus === 'FAULT' || checkForm.commStatus === 'FAULT'
)
const faultPreviewHint = computed(() => {
  if (!checking.value) return ''
  return checking.value.currentBikeCode
    ? `当前桩上仍有 ${checking.value.currentBikeCode}：提交后进入「待隔离」，车辆落点保留、仍可借走或调出，但任何新车不能进入；车离开后自动转已隔离。`
    : '当前桩位空闲：提交后立即「已隔离」，不再出现在借车、还车、调拨的可选桩位中。'
})

function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : ''
}
function resultTagType(r) {
  return { PENDING: 'info', OK: 'success', FAULT: 'danger' }[r] || 'info'
}
function levelTagType(l) {
  return { LOW: 'info', MID: 'warning', HIGH: 'danger' }[l] || 'info'
}
function triClass(v) {
  return v === 'FAULT' ? 'tri-fault' : 'tri-ok'
}
function pileStatusClass(s) {
  if (s === 'ISOLATED') return 'pile-isolated-text'
  if (s === 'PENDING_ISOLATED') return 'pile-pending-text'
  if (s === 'DISABLED') return 'muted'
  return 'ok-text'
}
function handlingTagType(s) {
  return {
    FH_PENDING: 'danger',
    FH_HANDLED: 'warning',
    FH_RECHECK_REJECTED: 'danger',
    FH_RECHECK_PASS: 'success'
  }[s] || 'info'
}
function canHandle(s) {
  return s === 'FH_PENDING' || s === 'FH_RECHECK_REJECTED'
}
function canRecheck(s) {
  return s === 'FH_HANDLED' || s === 'FH_RECHECK_REJECTED'
}

async function loadStations() {
  if (!stations.value.length) stations.value = await stationApi.list()
}

async function load() {
  loading.value = true
  try {
    await loadStations()
    batches.value = await inspectionApi.list({
      stationId: stationFilter.value || undefined,
      status: statusFilter.value || undefined
    })
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.stationId = stationFilter.value || null
  createForm.createdBy = ''
  createForm.remark = ''
  createVisible.value = true
  loadStations()
}

async function doCreate() {
  if (!createForm.stationId) {
    ElMessage.warning('请选择巡检站点')
    return
  }
  if (!createForm.createdBy.trim()) {
    ElMessage.warning('请填写建单人')
    return
  }
  creating.value = true
  try {
    const created = await inspectionApi.create({
      stationId: createForm.stationId,
      createdBy: createForm.createdBy.trim(),
      remark: createForm.remark || null
    })
    ElMessage.success('巡检批次已创建，桩位清单与占车情况已固定')
    createVisible.value = false
    await load()
    await openDetail(created.id)
  } finally {
    creating.value = false
  }
}

async function openDetail(id) {
  detailVisible.value = true
  await refreshDetail(id)
}

async function refreshDetail(id) {
  const targetId = id || (detail.value && detail.value.id)
  detailLoading.value = true
  try {
    detail.value = await inspectionApi.detail(targetId)
  } finally {
    detailLoading.value = false
  }
}

function openCheck(row) {
  checking.value = row
  checkForm.lockStatus = 'NORMAL'
  checkForm.powerStatus = 'NORMAL'
  checkForm.commStatus = 'NORMAL'
  checkForm.faultLevel = 'MID'
  checkForm.faultDesc = ''
  checkForm.checkerName = detail.value.createdBy || ''
  checkVisible.value = true
}

async function doCheck() {
  if (!checking.value) return
  if (!checkForm.checkerName.trim()) {
    ElMessage.warning('请填写检查人')
    return
  }
  if (checkIsFault.value && !checkForm.faultLevel) {
    ElMessage.warning('发现故障必须选择故障等级')
    return
  }
  submitting.value = true
  try {
    const view = await inspectionApi.check(detail.value.id, checking.value.id, {
      checkerName: checkForm.checkerName.trim(),
      lockStatus: checkForm.lockStatus,
      powerStatus: checkForm.powerStatus,
      commStatus: checkForm.commStatus,
      faultLevel: checkIsFault.value ? checkForm.faultLevel : null,
      faultDesc: checkForm.faultDesc || null,
      rev: checking.value.rev
    })
    detail.value = view
    checkVisible.value = false
    ElMessage.success(checkIsFault.value ? '故障已登记并隔离，处置单已自动开立' : '检查结果已登记')
    await load()
  } catch (e) {
    await onConflictOrReload(e)
  } finally {
    submitting.value = false
  }
}

function openHandle(row) {
  handlingRow.value = row
  handleForm.handlerName = row.handling.handlerName || ''
  handleForm.handleNote = row.handling.handleNote || ''
  handleVisible.value = true
}

async function doHandle() {
  if (!handlingRow.value) return
  if (!handleForm.handlerName.trim()) {
    ElMessage.warning('请填写处置人')
    return
  }
  submitting.value = true
  try {
    detail.value = await inspectionApi.handle(handlingRow.value.handling.id, {
      handlerName: handleForm.handlerName.trim(),
      handleNote: handleForm.handleNote || null,
      rev: handlingRow.value.handling.rev
    })
    handleVisible.value = false
    ElMessage.success('处置已完成，等待复验')
    await load()
  } catch (e) {
    await onConflictOrReload(e)
  } finally {
    submitting.value = false
  }
}

function openRecheck(row) {
  recheckRow.value = row
  recheckForm.pass = true
  recheckForm.recheckerName = row.handling.recheckerName || ''
  recheckForm.recheckNote = ''
  recheckVisible.value = true
}

async function doRecheck() {
  if (!recheckRow.value) return
  if (!recheckForm.recheckerName.trim()) {
    ElMessage.warning('请填写复验人')
    return
  }
  if (!recheckForm.pass && !recheckForm.recheckNote.trim()) {
    ElMessage.warning('复验不通过必须填写原因')
    return
  }
  submitting.value = true
  try {
    detail.value = await inspectionApi.recheck(recheckRow.value.handling.id, {
      recheckerName: recheckForm.recheckerName.trim(),
      pass: recheckForm.pass,
      recheckNote: recheckForm.recheckNote || null,
      rev: recheckRow.value.handling.rev
    })
    recheckVisible.value = false
    ElMessage.success(recheckForm.pass ? '复验通过，桩位已按真实占车恢复' : '复验不通过，桩位继续隔离')
    await load()
  } catch (e) {
    await onConflictOrReload(e)
  } finally {
    submitting.value = false
  }
}

/**
 * 并发相撞：后端 409 在 latest 里带回最新批次视图 ——
 * 已生效的车辆落点与巡检记录绝不被旧页面覆盖，这里直接用最新状态覆盖页面。
 */
async function onConflictOrReload(e) {
  if (e && e.conflict) {
    checkVisible.value = false
    handleVisible.value = false
    recheckVisible.value = false
    if (e.latest) {
      detail.value = e.latest
      ElMessage.warning('页面状态已过期，已为你刷新为最新状态，请核对后重新操作')
    } else {
      await refreshDetail()
    }
    await load()
  }
}

onMounted(async () => {
  await load()
  // 从站点看板的「巡检」按钮跳来时，自动打开建批次对话框
  if (route.query.create && stationFilter.value) {
    openCreate()
  }
})
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.bar h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: #2f3b22;
}

.tip {
  font-size: 12px;
  color: #93a386;
}

.tip b {
  color: var(--el-color-primary-dark-2);
}

.bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ml6 { margin-left: 6px; }
.ml0 { margin-left: 0; }
.mb12 { margin-bottom: 12px; }
.muted { color: #9aa591; }
.fs11 { font-size: 11px; }
.fs12 { font-size: 12px; }
.ok-text { color: var(--el-color-success-dark-2); }
.num-fault { color: var(--el-color-danger); font-weight: 700; }
.pile-pending-text { color: #b88230; font-weight: 700; }
.pile-isolated-text { color: #c45656; font-weight: 700; }

.detail-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 8px;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
}

.detail-head h3 {
  margin: 0 0 6px;
  color: #2f3b22;
}

.sub {
  font-size: 13px;
  color: #6f7f62;
  margin-top: 3px;
}

.rule-alert {
  margin: 2px 0;
}

.tri {
  display: inline-block;
  margin-right: 6px;
  font-size: 11px;
  font-style: normal;
}

.tri i {
  font-style: normal;
  padding: 1px 6px;
  border-radius: 6px;
}

.tri-ok {
  color: var(--el-color-success-dark-2);
  background: var(--el-color-success-light-9);
}

.tri-fault {
  color: #c45656;
  background: #fef0f0;
  font-weight: 700;
}

.bike-then {
  font-size: 12px;
  color: #4b6b32;
}

.current {
  display: block;
  font-size: 11px;
  color: #8b9a7c;
  margin-top: 2px;
}

.changed {
  font-size: 10px;
  color: #b88230;
}

.h-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.check-box,
.act-box {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.snap {
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
}
</style>
