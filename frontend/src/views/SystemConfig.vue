<template>
  <div class="system-config-page">
    <el-page-header content="需求解析规则配置" @back="goBack">
      <template #extra>
        <div>
          <el-button :loading="saving" type="primary" @click="handleSave">
            <el-icon><Check /></el-icon> 保存规则
          </el-button>
          <el-button @click="handleReset">
            <el-icon><RefreshLeft /></el-icon> 恢复默认
          </el-button>
        </div>
      </template>
    </el-page-header>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin: 16px 0"
      title="解析规则配置（需求解析规则 FR-REQ-002 + 代码解析范围 FR-CODE-001）"
      description="上方配置歧义/模糊词清单与互斥词对；下方配置代码解析范围（按包/类/方法过滤）；保存后立即热生效，新建分析任务将使用最新规则。仅管理员可修改。"
    />

    <el-card v-loading="loading" shadow="never">
      <template #header><span>歧义 / 模糊词（命中出现即建议明确化）</span></template>
      <el-tag
        v-for="(kw, idx) in ambiguityKeywords"
        :key="'amb-' + idx"
        closable
        style="margin: 4px"
        @close="removeAmbiguity(idx)"
      >{{ kw }}</el-tag>
      <el-input
        v-model="ambInput"
        placeholder="输入后回车添加，如：等 / 适当 / 方便"
        style="width: 280px; margin: 4px"
        @keyup.enter="addAmbiguity"
      />
      <el-button size="small" @click="addAmbiguity">添加</el-button>
    </el-card>

    <el-card v-loading="loading" shadow="never" style="margin-top: 16px">
      <template #header><span>互斥词对（同一需求内同时出现提示潜在矛盾，须人工复核）</span></template>
      <el-table :data="contradictionPairs" border style="margin-bottom: 12px">
        <el-table-column label="词 A" width="240">
          <template #default="{ row, $index }">
            <el-input v-model="contradictionPairs[$index][0]" placeholder="如：必须" />
          </template>
        </el-table-column>
        <el-table-column label="词 B（支持 (?&lt;!不) 前缀否定）" width="320">
          <template #default="{ $index }">
            <el-input v-model="contradictionPairs[$index][1]" placeholder="如：禁止 或 (?<!不)允许" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ $index }">
            <el-button type="danger" size="small" @click="removePair($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button @click="addPair">+ 新增互斥词对</el-button>
    </el-card>

    <!-- FR-CODE-001 规则3/4（2.4 整改项）：代码解析范围配置 -->
    <el-card v-loading="loading" shadow="never" style="margin-top: 16px">
      <template #header><span>代码解析范围（FR-CODE-001，按包/类/方法过滤解析）</span></template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="代码解析范围说明"
        description="include* 非空时仅解析匹配项；exclude* 命中即排除（优先于 include）；包/类支持通配（如 com.example.*、*service*）；类匹配支持简单类名与「包.类」全限定名。保存后热生效。"
      />
      <div v-for="(cfg, ci) in scopeGroups" :key="ci" style="margin-bottom: 16px">
        <div style="font-weight: 600; margin-bottom: 6px">{{ cfg.label }}</div>
        <el-tag
          v-for="(v, vi) in scopeLists[cfg.key]"
          :key="vi"
          closable
          style="margin: 4px"
          @close="removeScope(cfg.key, vi)"
        >{{ v }}</el-tag>
        <el-input
          v-model="scopeInputs[cfg.key]"
          :placeholder="cfg.placeholder"
          style="width: 300px; margin: 4px"
          @keyup.enter="addScope(cfg.key)"
        />
        <el-button size="small" @click="addScope(cfg.key)">添加</el-button>
      </div>
      <el-button type="primary" :loading="savingScope" @click="handleSaveScope">
        <el-icon><Check /></el-icon> 保存代码解析范围
      </el-button>
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header><span>当前生效 JSON（保存即写入后端）</span></template>
      <pre style="background: #f5f7fa; padding: 12px; border-radius: 4px; overflow: auto">{{ previewJson }}</pre>
    </el-card>

    <!-- 4.9 整改：密钥轮换入口（GAP-028，仅管理员） -->
    <el-card shadow="never" style="margin-top: 16px">
      <template #header><span>安全运维 · 密钥轮换（GAP-028）</span></template>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="密钥轮换说明"
        description="将上传文件与备份文件的加密密钥从旧密钥重加密为新密钥。仅管理员可操作，轮换期间请避免并发上传/备份。新密钥建议长度 ≥ 16 位且包含大小写字母与数字。"
      />
      <el-form :model="rotateForm" label-width="110px" style="max-width: 520px">
        <el-form-item label="旧密钥">
          <el-input v-model="rotateForm.oldKey" type="password" show-password placeholder="当前存储加密密钥" />
        </el-form-item>
        <el-form-item label="新密钥">
          <el-input v-model="rotateForm.newKey" type="password" show-password placeholder="轮换后的新密钥（≥16位）" />
        </el-form-item>
        <el-form-item>
          <el-button type="warning" :loading="rotating" @click="handleRotate">执行密钥轮换</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, RefreshLeft } from '@element-plus/icons-vue'
import { systemConfigApi, securityApi } from '@/api'
import { UserContext } from '@/store/user'

const router = useRouter()
const KEY = 'req_parse_rules'

const loading = ref(false)
const saving = ref(false)
const ambiguityKeywords = ref([])
const contradictionPairs = reactive([])
const ambInput = ref('')

const previewJson = computed(() =>
  JSON.stringify({ ambiguityKeywords: ambiguityKeywords.value, contradictionPairs: contradictionPairs }, null, 2)
)

const goBack = () => router.push('/dashboard')

const addAmbiguity = () => {
  const v = (ambInput.value || '').trim()
  if (v && !ambiguityKeywords.value.includes(v)) {
    ambiguityKeywords.value.push(v)
  }
  ambInput.value = ''
}
const removeAmbiguity = (idx) => ambiguityKeywords.value.splice(idx, 1)

const addPair = () => contradictionPairs.push(['', ''])
const removePair = (idx) => contradictionPairs.splice(idx, 1)

const load = async () => {
  loading.value = true
  try {
    const res = await systemConfigApi.get(KEY)
    const val = res || {}
    ambiguityKeywords.value = Array.isArray(val.ambiguityKeywords) ? [...val.ambiguityKeywords] : []
    contradictionPairs.splice(0, contradictionPairs.length, ...(Array.isArray(val.contradictionPairs) ? val.contradictionPairs : []))
    await loadScope()
  } catch (e) {
    ElMessage.error(e.message || '加载配置失败')
  } finally {
    loading.value = false
  }
}

/* ============ FR-CODE-001 规则3/4（2.4）：代码解析范围配置 ============ */
const CODE_SCOPE_KEY = 'code_parse_scope'
const savingScope = ref(false)
const scopeGroups = [
  { key: 'includePackages', label: '包含包（空=不限，支持前缀通配 com.example.*）', placeholder: '如：com.example.biz' },
  { key: 'excludePackages', label: '排除包', placeholder: '如：com.example.test' },
  { key: 'includeClasses', label: '包含类（简单类名或 包.类 全限定名）', placeholder: '如：OrderService' },
  { key: 'excludeClasses', label: '排除类（支持通配 *Test）', placeholder: '如：*Test' },
  { key: 'includeMethods', label: '包含方法名（空=不限）', placeholder: '如：createOrder' },
  { key: 'excludeMethods', label: '排除方法名', placeholder: '如：toString' }
]
const scopeLists = reactive({
  includePackages: [], excludePackages: [], includeClasses: [], excludeClasses: [], includeMethods: [], excludeMethods: []
})
const scopeInputs = reactive({})
scopeGroups.forEach(g => { scopeInputs[g.key] = '' })

const addScope = (key) => {
  const v = (scopeInputs[key] || '').trim()
  if (v && !scopeLists[key].includes(v)) scopeLists[key].push(v)
  scopeInputs[key] = ''
}
const removeScope = (key, idx) => scopeLists[key].splice(idx, 1)

const loadScope = async () => {
  try {
    const res = await systemConfigApi.get(CODE_SCOPE_KEY)
    const val = res || {}
    scopeGroups.forEach(g => {
      scopeLists[g.key].splice(0, scopeLists[g.key].length, ...(Array.isArray(val[g.key]) ? val[g.key] : []))
    })
  } catch (e) {
    // 未配置时保持空列表（不过滤）
  }
}

const handleSaveScope = async () => {
  if (!UserContext.isAdmin()) {
    ElMessage.warning('仅管理员可修改系统配置')
    return
  }
  savingScope.value = true
  try {
    const payload = {}
    scopeGroups.forEach(g => { payload[g.key] = scopeLists[g.key] })
    await systemConfigApi.save(CODE_SCOPE_KEY, JSON.stringify(payload, null, 2), '代码解析范围（FR-CODE-001：按包/类/方法过滤解析）')
    ElMessage.success('代码解析范围已保存并热生效')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingScope.value = false
  }
}

const handleSave = async () => {
  if (!UserContext.isAdmin()) {
    ElMessage.warning('仅管理员可修改系统配置')
    return
  }
  saving.value = true
  try {
    await systemConfigApi.save(KEY, previewJson.value, '需求解析规则（歧义/模糊词 + 互斥词对）')
    ElMessage.success('需求解析规则已保存并热生效')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleReset = () => {
  ambiguityKeywords.value = ['等', '等等', '适当', '合适', '合理', '可能', '也许', '若干', '一些', '方便', '友好']
  contradictionPairs.splice(0, contradictionPairs.length,
    ['必须', '禁止'], ['必须', '不得'], ['禁止', '(?<!不)允许'], ['不得', '(?<!不)允许'], ['启用', '禁用'])
  ElMessage.info('已恢复为内置默认规则（保存后生效）')
}

/* ============ 4.9 整改：密钥轮换（GAP-028，仅管理员） ============ */
const rotating = ref(false)
const rotateForm = reactive({ oldKey: '', newKey: '' })
const handleRotate = async () => {
  if (!UserContext.isAdmin()) {
    ElMessage.warning('仅管理员可执行密钥轮换')
    return
  }
  if (!rotateForm.oldKey || !rotateForm.newKey) {
    ElMessage.warning('请填写旧密钥与新密钥')
    return
  }
  if (rotateForm.newKey.length < 16) {
    ElMessage.warning('新密钥建议长度 ≥ 16 位')
    return
  }
  try {
    await ElMessageBox.confirm('确认执行密钥轮换？轮换期间请避免并发上传/备份操作。', '安全确认', {
      type: 'warning', confirmButtonText: '确认轮换', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  rotating.value = true
  try {
    const res = await securityApi.rotateKeys(rotateForm.oldKey, rotateForm.newKey)
    ElMessage.success('密钥轮换完成：' + (Array.isArray(res) ? res.length + ' 个文件已重加密' : '成功'))
    rotateForm.oldKey = ''
    rotateForm.newKey = ''
  } catch (e) {
    ElMessage.error(e.message || '密钥轮换失败')
  } finally {
    rotating.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.system-config-page { padding: 16px; }
</style>
