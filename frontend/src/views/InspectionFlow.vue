<template>
  <div class="page">
    <div class="bar">
      <div class="bar-left">
        <h2>桩位巡检</h2>
        <span class="tip">
          按站点建巡检批次，逐桩记录桩锁 / 供电 / 通信结果 ——
          空桩故障<b>立即隔离</b>，有车故障进<b>待隔离</b>（车可借走/调出，新车禁入），复验通过才恢复
        </span>
      </div>
      <div class="bar-right">
        <el-select v-model="filterStationId" class="sel" placeholder="全部站点" clearable @change="loadList">
          <el-option v-for="s in stations" :key="s.id" :label="`${s.code} ${s.name}`" :value="s.id" />
        </el-select>
        <el-select v-model="filterStatus" class="sel-sm" placeholder="全部状态" clearable @change="loadList">
          <el-option label="巡检中" value="OPEN" />
          <el-option label="已完成" value="COMPLETED" />
        </el-select>
        <el-button @click="loadList">刷新</el-button>
        <el-button type="primary" @click="openCreate">+ 创建巡检批次</el-button>
      </div>
    </div>

    <!-- 批次列表 -->
    <el-table :data="batches" v-loading="loading" stripe class="batch-table">
      <el-table-column prop="batchNo" label="批次号" width="180">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.batchNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="站点" width="170">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.stationCode }}</el-tag>
          {{ row.stationName }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'OPEN' ? 'warning' : 'success'" effect="dark">
            {{ row.statusName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="检查进度" width="130">
        <template #default="{ row }">{{ row.checkedCount }} / {{ row.pileCount }}</template>
      </el-table-column>
      <el-table-column label="正常 / 故障" width="110">
        <template #default="{ row }">
          <span class="cnt-ok">{{ row.normalCount }}</span> /
          <span :class="row.faultCount ? 'cnt-fault' : ''">{{ row.faultCount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="建单时占用快照" width="150">
        <template #default="{ row }">
          占 {{ row.snapshotOccupiedCount }} · 空 {{ row.snapshotIdleCount }}
          <template v-if="row.snapshotDisabledCount"> · 停 {{ row.snapshotDisabledCount }}</template>
        </template>
      </el-table-column>
      <el-table-column label="当前隔离" width="170">
        <template #default="{ row }">
          <el-tag v-if="row.pendingQuarantineCount" size="small" type="warning" effect="dark" class="mini-tag">
            待隔离 {{ row.pendingQuarantineCount }}
          </el-tag>
          <el-tag v-if="row.quarantinedCount" size="small" type="danger" effect="dark" class="mini-tag">
            已隔离 {{ row.quarantinedCount }}
          </el-tag>
          <el-tag v-if="row.pendingRecheckCount" size="small" type="primary" effect="plain" class="mini-tag">
            待复验 {{ row.pendingRecheckCount }}
          </el-tag>
          <span v-if="!row.pendingQuarantineCount && !row.quarantinedCount && !row.pendingRecheckCount" class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="建单人" width="100">
        <template #default="{ row }">{{ row.creatorName }}</template>
      </el-table-column>
      <el-table-column label="建单时间" width="150">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <!-- 创建批次 -->
    <el-dialog v-model="createVisible" title="创建巡检批次" width="520">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="提交时由后端固定该站点当前的全部桩位清单与占车情况；之后新增的桩位不会进入本批次。"
        class="dlg-alert"
      />
      <el-form label-width="96px">
        <el-form-item label="巡检站点" required>
          <el-select v-model="createForm.stationId" placeholder="选择站点" style="width: 100%">
            <el-option v-for="s in stations" :key="s.id" :label="`${s.code} ${s.name}`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="建单人" required>
          <el-select v-model="createForm.creatorId" placeholder="选择运营人员" style="width: 100%">
            <el-option
              v-for="o in onDutyOperators"
              :key="o.id"
              :label="`${o.name}（${o.phone}）`"
              :value="o.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">固定清单并建批</el-button>
      </template>
    </el-dialog>

    <!-- 批次详情抽屉 -->
    <el-drawer v-model="detailVisible" size="72%" :title="detailTitle" destroy-on-close>
      <div v-if="detail" class="detail">
        <div class="d-meta">
          <el-tag :type="detail.status === 'OPEN' ? 'warning' : 'success'" effect="dark">
            {{ detail.statusName }}
          </el-tag>
          <span>站点：<b>{{ detail.stationCode }} {{ detail.stationName }}</b></span>
          <span>建单人：{{ detail.creatorName }}</span>
          <span>建单时间：{{ fmt(detail.createdAt) }}</span>
          <span v-if="detail.finishedAt">完成时间：{{ fmt(detail.finishedAt) }}</span>
        </div>

        <div class="d-snapshot">
          建单时固定：共 <b>{{ detail.pileCount }}</b> 个桩位（占用 {{ detail.snapshotOccupiedCount }} ·
          空闲 {{ detail.snapshotIdleCount }}<template v-if="detail.snapshotDisabledCount"> · 停用 {{ detail.snapshotDisabledCount }}</template>）；
          之后新增桩位不会进入本批次。
        </div>

        <div class="d-stats">
          <div class="d-stat">
            <span class="d-stat-num">{{ detail.checkedCount }}/{{ detail.pileCount }}</span>
            <span class="d-stat-label">已检查</span>
          </div>
          <div class="d-stat d-stat--ok">
            <span class="d-stat-num">{{ detail.normalCount }}</span>
            <span class="d-stat-label">正常</span>
          </div>
          <div class="d-stat d-stat--fault">
            <span class="d-stat-num">{{ detail.faultCount }}</span>
            <span class="d-stat-label">故障</span>
          </div>
          <div class="d-stat d-stat--pq">
            <span class="d-stat-num">{{ detail.pendingQuarantineCount }}</span>
            <span class="d-stat-label">待隔离</span>
          </div>
          <div class="d-stat d-stat--q">
            <span class="d-stat-num">{{ detail.quarantinedCount }}</span>
            <span class="d-stat-label">已隔离</span>
          </div>
          <div class="d-stat d-stat--recheck">
            <span class="d-stat-num">{{ detail.pendingRecheckCount }}</span>
            <span class="d-stat-label">待复验</span>
          </div>
        </div>

        <el-alert
          v-if="detail.status === 'OPEN'"
          type="warning"
          :closable="false"
          show-icon
          title="安全隔离优先：待隔离桩上的车可以借走/调出，但任何新车不能进入；已隔离桩不参与借车、还车与调拨，复验通过才恢复。"
          class="dlg-alert"
        />

        <el-table :data="detail.items" class="item-table" stripe>
          <el-table-column label="桩位" width="90">
            <template #default="{ row }">
              <b>{{ row.pileNo }}</b>
              <div class="muted mini">快照车：{{ row.snapshotBikeCode || '空桩' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="pileTagType(row.pileStatus)" effect="dark">{{ row.pileStatusName }}</el-tag>
              <div class="mini" :class="row.currentBikeCode ? '' : 'muted'">
                {{ row.currentBikeCode ? '车 ' + row.currentBikeCode : '当前无车' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="检查结论" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.itemResult === 'FAULT'" size="small" type="danger" effect="dark">故障</el-tag>
              <el-tag v-else-if="row.itemResult === 'NORMAL'" size="small" type="success" effect="plain">正常</el-tag>
              <el-tag v-else size="small" type="info">待检查</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="桩锁/供电/通信" width="140">
            <template #default="{ row }">
              <template v-if="row.lockResult">
                <span :class="row.lockResult === 'FAULT_FAIL' ? 'chk-fail' : 'chk-ok'">{{ checkMark(row.lockResult) }}</span>
                /
                <span :class="row.powerResult === 'FAULT_FAIL' ? 'chk-fail' : 'chk-ok'">{{ checkMark(row.powerResult) }}</span>
                /
                <span :class="row.comResult === 'FAULT_FAIL' ? 'chk-fail' : 'chk-ok'">{{ checkMark(row.comResult) }}</span>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="故障等级 / 描述" min-width="180">
            <template #default="{ row }">
              <template v-if="row.faultLevel">
                <el-tag size="small" :type="levelType(row.faultLevel)" effect="plain">{{ row.faultLevelName }}</el-tag>
                <span class="fault-desc">{{ row.faultDesc }}</span>
              </template>
              <span v-else class="muted">—</span>
              <div v-if="row.inspectOccupyNote" class="occupy-note">⚠ {{ row.inspectOccupyNote }}</div>
            </template>
          </el-table-column>
          <el-table-column label="处置状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="disposeTagType(row.disposeStatus)">{{ row.disposeStatusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="检查人 / 时间" width="150">
            <template #default="{ row }">
              <div>{{ row.inspectorName || '—' }}</div>
              <div class="muted mini">{{ fmt(row.inspectedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="复验" min-width="170">
            <template #default="{ row }">
              <div v-if="row.recheckResult" class="recheck-line">
                <el-tag size="small" :type="row.recheckResult === 'PASS' ? 'success' : 'danger'" effect="plain">
                  {{ row.recheckResultName }}
                </el-tag>
                <span class="muted mini">{{ row.recheckerName }} · {{ fmt(row.recheckedAt) }}</span>
              </div>
              <div v-if="row.recheckNote" class="recheck-note">{{ row.recheckNote }}</div>
              <el-collapse v-if="row.verifyLogs && row.verifyLogs.length" class="log-collapse">
                <el-collapse-item :title="`复验记录（${row.verifyLogs.length}）`">
                  <div v-for="log in row.verifyLogs" :key="log.id" class="log-line">
                    <el-tag size="small" :type="log.result === 'PASS' ? 'success' : 'danger'">
                      {{ log.resultName }}
                    </el-tag>
                    <span>{{ log.operatorName }} · {{ fmt(log.createdAt) }}</span>
                    <span v-if="log.pileRestoredName">→ 恢复为{{ log.pileRestoredName }}<template v-if="log.restoredBikeCode">（{{ log.restoredBikeCode }}）</template></span>
                    <div v-if="log.note" class="muted mini">原因：{{ log.note }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.itemResult === 'WAITING' || row.itemResult === 'NORMAL' || row.itemResult === 'FAULT'"
                size="small"
                :type="row.itemResult === 'WAITING' ? 'primary' : 'default'"
                @click="openCheck(row)"
              >{{ row.itemResult === 'WAITING' ? '检查' : '重新登记' }}</el-button>
              <template v-if="row.itemResult === 'FAULT'">
                <el-button
                  v-if="row.disposeStatus === 'PROCESSING'"
                  size="small"
                  type="warning"
                  @click="openDispose(row)"
                >处置</el-button>
                <el-button
                  v-if="row.disposeStatus === 'FIXED'"
                  size="small"
                  type="success"
                  @click="openRecheck(row)"
                >复验</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 逐桩检查 -->
    <el-dialog v-model="checkVisible" :title="checkTitle" width="560">
      <div v-if="checkTarget" class="chk-info">
        <el-tag size="small" :type="pileTagType(checkTarget.pileStatus)" effect="dark">
          {{ checkTarget.pileStatusName }}
        </el-tag>
        <span>桩上当前车辆：<b>{{ checkTarget.currentBikeCode || '空桩（无车）' }}</b></span>
        <span class="muted">建单快照：{{ checkTarget.snapshotBikeCode || '空桩' }}</span>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="提交时以后端锁内最新占车事实为准：空桩立即隔离；有车进入待隔离（不清车辆落点）。检查期间车辆若被借走/还走/调拨，结果自动按最新事实落地。"
        class="dlg-alert"
      />
      <el-form label-width="110px">
        <el-form-item label="检查人" required>
          <el-select v-model="checkForm.operatorId" placeholder="选择检查人" style="width: 100%">
            <el-option v-for="o in onDutyOperators" :key="o.id" :label="`${o.name}（${o.phone}）`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="桩锁检查" required>
          <el-radio-group v-model="checkForm.lockResult">
            <el-radio value="NORMAL_OK">正常</el-radio>
            <el-radio value="FAULT_FAIL">故障</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="供电检查" required>
          <el-radio-group v-model="checkForm.powerResult">
            <el-radio value="NORMAL_OK">正常</el-radio>
            <el-radio value="FAULT_FAIL">故障</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="通信检查" required>
          <el-radio-group v-model="checkForm.comResult">
            <el-radio value="NORMAL_OK">正常</el-radio>
            <el-radio value="FAULT_FAIL">故障</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="checkFault">
          <el-form-item label="故障等级" required>
            <el-radio-group v-model="checkForm.faultLevel">
              <el-radio value="LOW">低</el-radio>
              <el-radio value="MID">中</el-radio>
              <el-radio value="HIGH">高</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="故障描述" required>
            <el-input v-model="checkForm.faultDesc" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="checkVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitCheck">登记结果</el-button>
      </template>
    </el-dialog>

    <!-- 故障处置 -->
    <el-dialog v-model="disposeVisible" title="故障处置" width="520">
      <div v-if="checkTarget" class="chk-info">
        <el-tag size="small" :type="pileTagType(checkTarget.pileStatus)" effect="dark">
          {{ checkTarget.pileStatusName }}
        </el-tag>
        <span class="muted">{{ checkTarget.pileNo }} · {{ checkTarget.faultLevelName }} · {{ checkTarget.faultDesc }}</span>
      </div>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="处置期间桩位保持隔离：待隔离桩上的车仍可离开，已隔离/待隔离桩都不会接收新车。点「处置完成」后进入待复验，复验通过才恢复。"
        class="dlg-alert"
      />
      <el-form label-width="96px">
        <el-form-item label="处置人" required>
          <el-select v-model="disposeForm.operatorId" placeholder="选择处置人" style="width: 100%">
            <el-option v-for="o in onDutyOperators" :key="o.id" :label="`${o.name}（${o.phone}）`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置措施">
          <el-input v-model="disposeForm.disposeNote" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disposeVisible = false">取消</el-button>
        <el-button :loading="acting" @click="submitDispose('start')">开始处置</el-button>
        <el-button type="success" :loading="acting" @click="submitDispose('finish')">处置完成（待复验）</el-button>
      </template>
    </el-dialog>

    <!-- 复验 -->
    <el-dialog v-model="recheckVisible" title="复验" width="520">
      <div v-if="checkTarget" class="chk-info">
        <el-tag size="small" :type="pileTagType(checkTarget.pileStatus)" effect="dark">
          {{ checkTarget.pileStatusName }}
        </el-tag>
        <span>桩上当前车辆：<b>{{ checkTarget.currentBikeCode || '空桩（无车）' }}</b></span>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="复验通过时按桩位真实占车恢复：有车恢复为占用、无车恢复为空闲，恢复状态不接受手工指定。"
        class="dlg-alert"
      />
      <el-form label-width="96px">
        <el-form-item label="复验人" required>
          <el-select v-model="recheckForm.operatorId" placeholder="选择复验人" style="width: 100%">
            <el-option v-for="o in onDutyOperators" :key="o.id" :label="`${o.name}（${o.phone}）`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="复验结论" required>
          <el-radio-group v-model="recheckForm.result">
            <el-radio value="PASS">复验通过（恢复桩位）</el-radio>
            <el-radio value="FAIL">复验不通过（继续隔离）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="recheckForm.result === 'FAIL' ? '不通过原因' : '复验说明'">
          <el-input
            v-model="recheckForm.note"
            type="textarea"
            :rows="3"
            :maxlength="255"
            show-word-limit
            :placeholder="recheckForm.result === 'FAIL' ? '复验不通过必须填写原因，原因会保留在复验记录中' : '可填复验说明'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recheckVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitRecheck">提交复验</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { inspectionApi, stationApi } from '../api'

const route = useRoute()
const loading = ref(false)
const creating = ref(false)
const acting = ref(false)
const batches = ref([])
const stations = ref([])
const operators = ref([])

const filterStationId = ref(route.query.stationId ? Number(route.query.stationId) : null)
const filterStatus = ref(null)

const createVisible = ref(false)
const createForm = reactive({ stationId: null, creatorId: null, remark: '' })

const detailVisible = ref(false)
const detail = ref(null)

const checkVisible = ref(false)
const disposeVisible = ref(false)
const recheckVisible = ref(false)
const checkTarget = ref(null)
const checkForm = reactive({
  operatorId: null,
  lockResult: 'NORMAL_OK',
  powerResult: 'NORMAL_OK',
  comResult: 'NORMAL_OK',
  faultLevel: 'MID',
  faultDesc: ''
})
const disposeForm = reactive({ operatorId: null, disposeNote: '' })
const recheckForm = reactive({ operatorId: null, result: 'PASS', note: '' })

const onDutyOperators = computed(() => operators.value.filter((o) => o.status === 'ON_DUTY'))

const detailTitle = computed(() =>
  detail.value ? `巡检批次 ${detail.value.batchNo}（${detail.value.stationName}）` : '批次详情'
)
const checkTitle = computed(() => (checkTarget.value ? `逐桩检查 · ${checkTarget.value.pileNo}` : '逐桩检查'))
const checkFault = computed(() =>
  checkForm.lockResult === 'FAULT_FAIL'
  || checkForm.powerResult === 'FAULT_FAIL'
  || checkForm.comResult === 'FAULT_FAIL'
)

function fmt(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

function checkMark(v) {
  return v === 'FAULT_FAIL' ? '✗ 故障' : '✓ 正常'
}

function pileTagType(status) {
  if (status === 'PENDING_QUARANTINE') return 'warning'
  if (status === 'QUARANTINED') return 'danger'
  if (status === 'OCCUPIED') return 'primary'
  if (status === 'DISABLED') return 'info'
  return 'success'
}

function levelType(level) {
  if (level === 'HIGH') return 'danger'
  if (level === 'MID') return 'warning'
  return 'info'
}

function disposeTagType(status) {
  if (status === 'PROCESSING') return 'warning'
  if (status === 'FIXED') return 'primary'
  if (status === 'RECHECK_PASS') return 'success'
  if (status === 'RECHECK_FAIL') return 'danger'
  return 'info'
}

async function loadBase() {
  const [stationList, operatorList] = await Promise.all([
    stationApi.list(),
    inspectionApi.operators()
  ])
  stations.value = stationList
  operators.value = operatorList
}

async function loadList() {
  loading.value = true
  try {
    batches.value = await inspectionApi.list({
      stationId: filterStationId.value || undefined,
      status: filterStatus.value || undefined
    })
  } finally {
    loading.value = false
  }
}

async function loadDetail(id) {
  detail.value = await inspectionApi.detail(id)
}

function openCreate() {
  createForm.stationId = filterStationId.value || (stations.value[0] && stations.value[0].id)
  createForm.creatorId = onDutyOperators.value[0]?.id || null
  createForm.remark = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.stationId) {
    ElMessage.warning('请选择巡检站点')
    return
  }
  if (!createForm.creatorId) {
    ElMessage.warning('请选择建单人')
    return
  }
  creating.value = true
  try {
    const view = await inspectionApi.create({
      stationId: createForm.stationId,
      creatorId: createForm.creatorId,
      remark: createForm.remark || null
    })
    ElMessage.success(`批次 ${view.batchNo} 已创建，固定 ${view.pileCount} 个桩位`)
    createVisible.value = false
    filterStationId.value = view.stationId
    await loadList()
    await openDetailById(view.id)
  } finally {
    creating.value = false
  }
}

async function openDetail(row) {
  await openDetailById(row.id)
}

async function openDetailById(id) {
  await loadDetail(id)
  detailVisible.value = true
}

async function refreshDetail() {
  if (detail.value) {
    await loadDetail(detail.value.id)
  }
  await loadList()
}

function openCheck(row) {
  checkTarget.value = row
  checkForm.operatorId = onDutyOperators.value[0]?.id || null
  checkForm.lockResult = row.lockResult || 'NORMAL_OK'
  checkForm.powerResult = row.powerResult || 'NORMAL_OK'
  checkForm.comResult = row.comResult || 'NORMAL_OK'
  checkForm.faultLevel = row.faultLevel || 'MID'
  checkForm.faultDesc = row.faultDesc || ''
  checkVisible.value = true
}

async function submitCheck() {
  if (!checkForm.operatorId) {
    ElMessage.warning('请选择检查人')
    return
  }
  if (checkFault.value && !checkForm.faultDesc.trim()) {
    ElMessage.warning('发现故障时必须填写故障描述')
    return
  }
  acting.value = true
  try {
    await inspectionApi.check(detail.value.id, checkTarget.value.id, {
      operatorId: checkForm.operatorId,
      lockResult: checkForm.lockResult,
      powerResult: checkForm.powerResult,
      comResult: checkForm.comResult,
      faultLevel: checkFault.value ? checkForm.faultLevel : null,
      faultDesc: checkFault.value ? checkForm.faultDesc.trim() : null,
      expectedBikeCode: checkTarget.value.currentBikeCode || null,
      previousResult: checkTarget.value.itemResult
    })
    ElMessage.success('检查结果已登记')
    checkVisible.value = false
    await refreshDetail()
  } finally {
    acting.value = false
  }
}

function openDispose(row) {
  checkTarget.value = row
  disposeForm.operatorId = row.disposerId || onDutyOperators.value[0]?.id || null
  disposeForm.disposeNote = row.disposeNote || ''
  disposeVisible.value = true
}

async function submitDispose(stage) {
  if (!disposeForm.operatorId) {
    ElMessage.warning('请选择处置人')
    return
  }
  if (stage === 'finish' && !disposeForm.disposeNote.trim()) {
    ElMessage.warning('处置完成时请填写处置措施说明')
    return
  }
  acting.value = true
  try {
    if (stage === 'start') {
      await inspectionApi.startDispose(detail.value.id, checkTarget.value.id, {
        operatorId: disposeForm.operatorId,
        disposeNote: disposeForm.disposeNote.trim() || null
      })
      ElMessage.success('故障处置已开始，桩位继续隔离')
    } else {
      await inspectionApi.finishDispose(detail.value.id, checkTarget.value.id, {
        operatorId: disposeForm.operatorId,
        disposeNote: disposeForm.disposeNote.trim()
      })
      ElMessage.success('处置完成，桩位保持隔离，等待复验')
    }
    disposeVisible.value = false
    await refreshDetail()
  } finally {
    acting.value = false
  }
}

function openRecheck(row) {
  checkTarget.value = row
  recheckForm.operatorId = onDutyOperators.value[0]?.id || null
  recheckForm.result = 'PASS'
  recheckForm.note = ''
  recheckVisible.value = true
}

async function submitRecheck() {
  if (!recheckForm.operatorId) {
    ElMessage.warning('请选择复验人')
    return
  }
  if (recheckForm.result === 'FAIL' && !recheckForm.note.trim()) {
    ElMessage.warning('复验不通过时必须填写不通过原因')
    return
  }
  acting.value = true
  try {
    const outcome = await inspectionApi.recheck(detail.value.id, checkTarget.value.id, {
      operatorId: recheckForm.operatorId,
      result: recheckForm.result,
      note: recheckForm.note.trim() || null
    })
    ElMessage[outcome.passed ? 'success' : 'warning'](outcome.message)
    recheckVisible.value = false
    await refreshDetail()
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  await loadBase()
  await loadList()
  // 顶栏直查 IC 批次号会带 code，站点看板跳转会带 id：自动打开批次详情
  const rawId = route.query.id
  const code = (route.query.code || '').toString().toUpperCase()
  if (rawId) {
    const id = Number(rawId)
    if (id) {
      try {
        await openDetailById(id)
      } catch (e) {
        /* 编号查不到时已由统一拦截弹提示 */
      }
    }
  } else if (code) {
    const hit = batches.value.find((b) => b.batchNo.toUpperCase() === code)
    if (hit) {
      await openDetailById(hit.id)
    }
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
  color: var(--el-color-danger);
}

.bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sel {
  width: 190px;
}

.sel-sm {
  width: 120px;
}

.batch-table,
.item-table {
  background: #fff;
  border-radius: 10px;
}

.mini-tag {
  margin-right: 4px;
}

.muted {
  color: #9aa692;
}

.mini {
  font-size: 11px;
}

.cnt-ok {
  color: var(--el-color-success);
  font-weight: 700;
}

.cnt-fault {
  color: var(--el-color-danger);
  font-weight: 700;
}

.dlg-alert {
  margin-bottom: 14px;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 4px;
}

.d-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
  color: #5f6f55;
  flex-wrap: wrap;
}

.d-snapshot {
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: #4b6b32;
}

.d-stats {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.d-stat {
  flex: 1 1 90px;
  background: #fff;
  border-radius: 10px;
  border-left: 4px solid var(--el-color-primary);
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 1px 4px rgba(15, 30, 10, 0.06);
}

.d-stat-num {
  font-size: 20px;
  font-weight: 700;
  color: var(--el-color-primary-dark-2);
}

.d-stat-label {
  font-size: 12px;
  color: #93a386;
}

.d-stat--ok { border-left-color: var(--el-color-success); }
.d-stat--ok .d-stat-num { color: var(--el-color-success); }
.d-stat--fault { border-left-color: var(--el-color-danger); }
.d-stat--fault .d-stat-num { color: var(--el-color-danger); }
.d-stat--pq { border-left-color: #e6a23c; }
.d-stat--pq .d-stat-num { color: #e6a23c; }
.d-stat--q { border-left-color: #c0392b; }
.d-stat--q .d-stat-num { color: #c0392b; }
.d-stat--recheck { border-left-color: #409eff; }
.d-stat--recheck .d-stat-num { color: #409eff; }

.chk-info {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: #4b6b32;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.chk-fail {
  color: var(--el-color-danger);
  font-weight: 700;
}

.chk-ok {
  color: var(--el-color-success);
}

.fault-desc {
  margin-left: 8px;
  font-size: 12px;
}

.occupy-note {
  margin-top: 4px;
  font-size: 11px;
  color: #b88230;
  background: #fdf6ec;
  border-radius: 6px;
  padding: 3px 6px;
}

.recheck-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.recheck-note {
  font-size: 12px;
  color: #c0392b;
  margin-top: 2px;
}

.log-collapse {
  margin-top: 4px;
}

.log-collapse :deep(.el-collapse-item__header) {
  font-size: 12px;
  height: 28px;
  line-height: 28px;
}

.log-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 3px 0;
  flex-wrap: wrap;
}
</style>
