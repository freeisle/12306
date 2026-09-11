<script setup>
import { ref, reactive, nextTick, onMounted, watch } from 'vue'
import { message as antMessage } from 'ant-design-vue'
import {
  RobotOutlined,
  UserOutlined,
  ArrowUpOutlined,
  ShoppingOutlined,
  SafetyCertificateOutlined,
  WalletOutlined
} from '@ant-design/icons-vue'
import Cookie from 'js-cookie'
import {
  fetchAgentSession,
  fetchAgentChat,
  fetchAgentResume,
  fetchAgentHealth
} from '@/service'
import { agentChat, hydrateAgentChat, persistAgentChat } from './store'

// 金额统一只保留一个 ¥：数据若自带符号先剥离再拼接
const fmtYuan = (v) => '¥' + String(v ?? '').replace(/^[¥￥]\s*/, '')

const bodyRef = ref(null)
const inputRef = ref(null)
const sending = ref(false)
const input = ref('')
const messages = reactive([])

// 会话：优先复用控制台登录态（Cookie 里的 JWT），也支持账号密码接通
const threadId = ref('')
const cookieToken = Cookie.get('token') || ''
const cookieUsername = Cookie.get('username') || ''
const loginForm = reactive({ username: cookieUsername, password: '' })
const connecting = ref(false)
const tokenFailed = ref(false)

const agent = reactive({ ready: false, label: '连接中', model: '', hint: '' })

// 当前人工确认门（LangGraph interrupt）：{ type, payload, note }
// 同一时刻后端只允许一个挂起的 interrupt（否则 chat 返回 409）。
const gate = ref(null)

// 会话持久化：写穿到模块级单例 + sessionStorage，回到本页/刷新不丢对话
watch(
  [() => threadId.value, messages, gate],
  () => {
    agentChat.threadId = threadId.value
    agentChat.messages = messages.slice()
    agentChat.gate = gate.value
    persistAgentChat()
  },
  { deep: true }
)

const SUGGESTIONS = [
  '帮我查明天北京到上海的高铁',
  '给我买一张明天北京到上海的二等座',
  '我账号下有哪些乘车人？'
]

const scrollToBottom = () => {
  nextTick(() => {
    const el = bodyRef.value
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  })
}

const pushMessage = (msg) => {
  messages.push({ id: Date.now() + Math.random(), ...msg })
  scrollToBottom()
}

const friendlyError = (e) => {
  const resp = e && e.response
  if (resp) {
    if (resp.status === 409) return '存在待人工确认的事项，请先处理下方的确认卡片。'
    const detail = resp.data && resp.data.detail
    if (resp.status === 401) return `12306 登录失败：${detail || '账号或密码错误'}`
    if (detail) return `Agent 返回错误：${detail}`
    return `Agent 返回错误（HTTP ${resp.status}），请稍后重试。`
  }
  return '请求失败，请确认 ai-agent-py 已启动（默认端口 10007），且前端 dev server 已重启加载新代理。'
}

// 统一消费 /chat 与 /resume 的响应：{thread_id, reply, pending, done}
const applyRespond = (res) => {
  if (!res) return
  if (res.thread_id) threadId.value = res.thread_id
  if (res.reply) pushMessage({ role: 'assistant', content: res.reply })
  if (res.pending) {
    gate.value = { type: res.pending.type, payload: res.pending.payload || {}, note: '' }
    scrollToBottom()
  } else {
    gate.value = null
  }
}

const greetingFor = (name) =>
  `已接通，${name}。我可以查余票、登记下单计划，并在你人工确认后完成下单与创建支付单；全程不会自动扣款。试试说「帮我买一张明天北京到上海的二等座」。`

// 复用控制台登录态：浏览器 Cookie 里只有 JWT（不存明文密码），
// Agent 采纳该 token 作为 Authorization 头，免密码接通。
const connectWithToken = async (silent = false) => {
  if (!cookieToken || connecting.value) return false
  connecting.value = true
  try {
    const res = await fetchAgentSession({ access_token: cookieToken, username: cookieUsername })
    threadId.value = res.thread_id
    tokenFailed.value = false
    pushMessage({ role: 'assistant', greeting: true, content: greetingFor(res.real_name || res.username || cookieUsername) })
    nextTick(() => inputRef.value && inputRef.value.focus())
    return true
  } catch (e) {
    tokenFailed.value = true
    if (!silent) antMessage.error(friendlyError(e))
    return false
  } finally {
    connecting.value = false
  }
}

const connect = async () => {
  if (!loginForm.username || !loginForm.password) {
    antMessage.warning('请输入 12306 用户名与密码')
    return
  }
  connecting.value = true
  try {
    const res = await fetchAgentSession({
      username: loginForm.username,
      password: loginForm.password
    })
    threadId.value = res.thread_id
    loginForm.password = ''
    pushMessage({
      role: 'assistant',
      greeting: true,
      content: greetingFor(res.real_name || res.username)
    })
    nextTick(() => inputRef.value && inputRef.value.focus())
  } catch (e) {
    antMessage.error(friendlyError(e))
  } finally {
    connecting.value = false
  }
}

const send = async (raw) => {
  const text = (raw ?? input.value).trim()
  if (!text || sending.value) return
  if (!threadId.value) {
    antMessage.warning('请先接通 12306 账号，开始导购会话')
    return
  }
  if (gate.value) {
    antMessage.warning('存在待人工确认的事项，请先处理下方的确认卡片')
    return
  }
  pushMessage({ role: 'user', content: text })
  input.value = ''
  autoGrow()
  sending.value = true
  pushMessage({ role: 'assistant', pending: true })
  try {
    const res = await fetchAgentChat({
      message: text,
      thread_id: threadId.value,
      access_token: cookieToken || undefined,
      username: cookieUsername || undefined
    })
    messages.pop()
    applyRespond(res)
  } catch (e) {
    messages.pop()
    pushMessage({ role: 'assistant', error: true, content: friendlyError(e) })
  } finally {
    sending.value = false
    nextTick(() => inputRef.value && inputRef.value.focus())
  }
}

const decide = async (approved) => {
  if (!threadId.value || sending.value || !gate.value) return
  const current = gate.value
  sending.value = true
  pushMessage({
    role: 'system',
    content: `${current.type === 'confirm_order' ? '下单确认' : '支付确认'}：${
      approved ? '同意' : '拒绝'
    }${current.note ? ' · ' + current.note : ''}`
  })
  pushMessage({ role: 'assistant', pending: true })
  try {
    const res = await fetchAgentResume({
      thread_id: threadId.value,
      approved,
      note: current.note || '',
      access_token: cookieToken || undefined
    })
    messages.pop()
    applyRespond(res)
  } catch (e) {
    messages.pop()
    pushMessage({ role: 'assistant', error: true, content: friendlyError(e) })
  } finally {
    sending.value = false
  }
}

const autoGrow = () => {
  nextTick(() => {
    const el = inputRef.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 132) + 'px'
  })
}

const onKeydown = (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

const planOf = () => (gate.value && gate.value.payload && gate.value.payload.plan) || {}
const orderOf = () => (gate.value && gate.value.payload && gate.value.payload.order) || {}
const passengerNames = (p) => (p.passengers || []).map((x) => x.name).join('、') || '—'

onMounted(async () => {
  try {
    const h = await fetchAgentHealth()
    if (h && h.status === 'ok') {
      agent.ready = true
      agent.model = h.model || ''
      agent.hint = h.llm_key_hint || ''
      if (h.llm_key_set === false) {
        agent.ready = false
        agent.label = 'Agent 在运行 · 未配置 LLM 密钥（见 ai-agent-py/.env，改后需重启）'
      } else {
        agent.label = `Agent 就绪 · ${h.model || 'LLM'}`
      }
    } else {
      agent.label = 'Agent 未就绪'
    }
  } catch (e) {
    agent.label = '未连接 Agent（端口 10007）'
  }
  // 会话持久化：有历史先恢复；无历史才静默复用控制台登录态
  if (hydrateAgentChat()) {
    threadId.value = agentChat.threadId
    messages.push(...agentChat.messages)
    gate.value = agentChat.gate
    scrollToBottom()
  } else if (cookieToken) {
    await connectWithToken(true)
  }
})
</script>

<template>
  <div class="guide">
    <!-- 顶栏 -->
    <header class="gd-header">
      <div class="brand">
        <span class="brand-mark">AI导购</span>
        <span class="brand-tag">Agent · LangGraph</span>
      </div>
      <div class="status" :class="{ ready: agent.ready }" :title="agent.hint ? '运行中进程加载的密钥：' + agent.hint : ''">
        <span class="dot"></span>
        <span class="status-text">{{ agent.label }}</span>
      </div>
    </header>

    <!-- 会话区 -->
    <div class="gd-body" ref="bodyRef" aria-live="polite">
      <!-- 接通卡片 -->
      <div v-if="!threadId" class="connect-card">
        <div class="connect-icon"><ShoppingOutlined /></div>
        <h3>接通你的 12306 账号</h3>
        <p class="connect-tip">
          导购 Agent 将以该账号真实查询余票、登记下单计划；<b>下单</b>与<b>创建支付单</b>前各有一道人工确认门，不会自动扣款。
        </p>

        <button
          v-if="cookieToken"
          class="btn-primary btn-wide"
          :disabled="connecting"
          @click="connectWithToken(false)"
        >
          {{ connecting ? '接通中…' : `一键复用控制台登录态（${cookieUsername || '当前账号'}）` }}
        </button>
        <p v-if="tokenFailed" class="connect-warn">控制台登录态无效或已过期，请用下方账号密码接通，或回登录页重新登录。</p>
        <div v-if="cookieToken" class="connect-divider"><span>或用账号密码接通</span></div>

        <div class="connect-form">
          <input v-model="loginForm.username" class="field" placeholder="用户名" autocomplete="username" />
          <input
            v-model="loginForm.password"
            class="field"
            type="password"
            placeholder="密码"
            autocomplete="current-password"
            @keydown.enter="connect"
          />
          <button class="btn-ghost btn-wide" :disabled="connecting" @click="connect">
            {{ connecting ? '接通中…' : '账号密码接通' }}
          </button>
        </div>
      </div>

      <transition-group name="msg" tag="div" class="msg-list">
        <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
          <!-- 系统痕迹：人工确认决定 -->
          <div v-if="m.role === 'system'" class="system-chip">{{ m.content }}</div>

          <template v-else>
            <div class="avatar" :class="m.role">
              <RobotOutlined v-if="m.role === 'assistant'" />
              <UserOutlined v-else />
            </div>
            <div class="bubble-wrap">
              <div v-if="m.pending" class="bubble assistant typing">
                <span></span><span></span><span></span>
              </div>
              <div
                v-else
                class="bubble"
                :class="[m.role, { error: m.error, greeting: m.greeting }]"
              >
                <p class="text">{{ m.content }}</p>
              </div>
            </div>
          </template>
        </div>
      </transition-group>
    </div>

    <!-- 人工确认门①：下单计划 -->
    <div v-if="gate && gate.type === 'confirm_order'" class="gate gate-order">
      <div class="gate-head">
        <SafetyCertificateOutlined />
        <span>人工确认 ① · 下单计划</span>
      </div>
      <dl class="gate-grid">
        <div><dt>车次</dt><dd class="mono">{{ planOf().train_number }}</dd></div>
        <div><dt>行程</dt><dd>{{ planOf().departure }} → {{ planOf().arrival }}</dd></div>
        <div><dt>日期</dt><dd class="mono">{{ planOf().departure_date }}</dd></div>
        <div><dt>席别</dt><dd>{{ planOf().seat_name }}</dd></div>
        <div class="wide"><dt>乘车人</dt><dd>{{ passengerNames(planOf()) }}</dd></div>
        <div><dt>单价</dt><dd>{{ fmtYuan(planOf().price_yuan_per_person) }}</dd></div>
        <div><dt>合计</dt><dd class="total">{{ fmtYuan(planOf().total_yuan) }}</dd></div>
        <div class="wide">
          <dt>幂等键</dt><dd class="mono dim">{{ (planOf().idempotency_key || '').slice(0, 16) }}…</dd>
        </div>
      </dl>
      <input
        v-model="gate.note"
        class="gate-note"
        placeholder="拒绝时可写调整意见（如：改一等座 / 换乘车人），会回传给模型"
      />
      <div class="gate-actions">
        <button class="btn-primary" :disabled="sending" @click="decide(true)">确认下单</button>
        <button class="btn-ghost" :disabled="sending" @click="decide(false)">拒绝并调整</button>
      </div>
    </div>

    <!-- 人工确认门②：创建支付单 -->
    <div v-else-if="gate && gate.type === 'confirm_pay'" class="gate gate-pay">
      <div class="gate-head">
        <WalletOutlined />
        <span>人工确认 ② · 创建支付单</span>
      </div>
      <dl class="gate-grid">
        <div class="wide"><dt>订单号</dt><dd class="mono">{{ orderOf().order_sn }}</dd></div>
        <div><dt>合计</dt><dd class="total">{{ fmtYuan(orderOf().total_yuan) }}</dd></div>
      </dl>
      <div v-if="(orderOf().details || []).length" class="gate-details">
        <div v-for="(d, i) in orderOf().details" :key="i" class="detail-row">
          <span>{{ d.real_name }}</span>
          <span>{{ d.seat_name }}</span>
          <span v-if="d.carriage_number" class="mono">{{ d.carriage_number }}车 {{ d.seat_number }}</span>
          <span class="amount">{{ fmtYuan(d.amount_yuan) }}</span>
        </div>
      </div>
      <input
        v-model="gate.note"
        class="gate-note"
        placeholder="暂不支付时可写备注（可选）"
      />
      <div class="gate-actions">
        <button class="btn-primary" :disabled="sending" @click="decide(true)">确认创建支付单</button>
        <button class="btn-ghost" :disabled="sending" @click="decide(false)">暂不支付</button>
      </div>
    </div>

    <!-- 快捷话术 -->
    <div v-if="threadId && !gate" class="gd-suggest">
      <button
        v-for="(s, i) in SUGGESTIONS"
        :key="i"
        class="chip"
        :disabled="sending"
        @click="send(s)"
      >
        {{ s }}
      </button>
    </div>

    <!-- 输入区 -->
    <footer class="gd-dock">
      <textarea
        ref="inputRef"
        v-model="input"
        rows="1"
        class="gd-input"
        :placeholder="threadId ? '说出你的购票需求，Enter 发送 / Shift + Enter 换行' : '请先接通 12306 账号'"
        :disabled="!threadId"
        @keydown="onKeydown"
        @input="autoGrow"
      ></textarea>
      <button class="send" :disabled="sending || !input.trim() || !threadId" @click="send()" title="发送">
        <ArrowUpOutlined />
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap');

$ink-900: #1f2329;
$ink-800: #33383e;
$ink-700: #4e5964;
$amber: #d48806;
$amber-soft: rgba(212, 136, 6, 0.08);
$cyan: #0e8f84;
$cyan-soft: rgba(14, 143, 132, 0.08);
$muted: #6b7684;
$line: rgba(31, 35, 41, 0.1);

.guide {
  position: relative;
  height: calc(100vh - 190px);
  min-height: 560px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid rgba(31, 35, 41, 0.08);
  background: #ffffff;
  box-shadow: 0 12px 36px -22px rgba(31, 35, 41, 0.25);
  font-family: 'PingFang SC', 'Microsoft YaHei', -apple-system, sans-serif;
  color: $ink-900;
}

/* ---------- 顶栏 ---------- */
.gd-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid $line;
  background: #ffffff;

  .brand {
    display: flex;
    align-items: baseline;
    gap: 12px;
    .brand-mark {
      font-family: 'Noto Serif SC', serif;
      font-weight: 700;
      font-size: 22px;
      letter-spacing: 3px;
      background: linear-gradient(92deg, $ink-900 20%, $amber 90%);
      -webkit-background-clip: text;
      background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .brand-tag {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 11px;
      letter-spacing: 2px;
      color: $cyan;
      border: 1px solid rgba(14, 143, 132, 0.35);
      border-radius: 4px;
      padding: 2px 7px;
    }
  }
  .status {
    display: flex;
    align-items: center;
    gap: 8px;
    font-family: 'IBM Plex Mono', monospace;
    font-size: 12px;
    color: $muted;
    .dot { width: 8px; height: 8px; border-radius: 50%; background: #b0b8c1; }
    &.ready .dot { background: $cyan; animation: pulse 2.2s infinite; }
    &.ready .status-text { color: $ink-800; }
  }
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(14, 143, 132, 0.45); }
  70% { box-shadow: 0 0 0 9px rgba(14, 143, 132, 0); }
  100% { box-shadow: 0 0 0 0 rgba(14, 143, 132, 0); }
}

/* ---------- 会话区 ---------- */
.gd-body {
  flex: 1;
  overflow-y: auto;
  padding: 26px 24px 8px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  scroll-behavior: smooth;
  background: #ffffff;
  &::-webkit-scrollbar { width: 8px; }
  &::-webkit-scrollbar-thumb { background: rgba(31, 35, 41, 0.15); border-radius: 8px; }
  &::-webkit-scrollbar-thumb:hover { background: rgba(212, 136, 6, 0.4); }
}

.msg-list { display: flex; flex-direction: column; gap: 20px; }

.row {
  display: flex;
  gap: 12px;
  max-width: 82%;
  &.user { align-self: flex-end; flex-direction: row-reverse; }
  &.assistant { align-self: flex-start; }
  &.system { align-self: center; max-width: 100%; }
}

.system-chip {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  letter-spacing: 1px;
  color: $muted;
  background: #f7f8fa;
  border: 1px dashed $line;
  border-radius: 999px;
  padding: 4px 14px;
}

.avatar {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  &.assistant { background: linear-gradient(135deg, #f5c163, $amber); color: #fff; }
  &.user { background: #f2f3f7; color: $ink-700; border: 1px solid $line; }
}

.bubble-wrap { display: flex; flex-direction: column; gap: 6px; min-width: 0; }

.bubble {
  border-radius: 12px;
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  &.assistant {
    background: #f7f8fa;
    border: 1px solid $line;
    border-left: 3px solid $amber;
    color: $ink-800;
  }
  &.user {
    background: linear-gradient(135deg, #f0b429, $amber);
    color: #fff;
    border: none;
  }
  &.greeting { border-left-color: $cyan; }
  &.error {
    background: #fff1f0;
    border: 1px solid #ffccc7;
    border-left: 3px solid #a8071a;
    color: #a8071a;
  }
  .text { margin: 0; }
  &.typing {
    display: flex;
    gap: 5px;
    align-items: center;
    min-height: 22px;
    span {
      width: 7px; height: 7px; border-radius: 50%;
      background: rgba(31, 35, 41, 0.35);
      animation: blink 1.2s infinite;
      &:nth-child(2) { animation-delay: 0.2s; }
      &:nth-child(3) { animation-delay: 0.4s; }
    }
  }
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-3px); }
}

.msg-enter-active, .msg-leave-active { transition: all 0.25s ease; }
.msg-enter-from { opacity: 0; transform: translateY(8px); }

/* ---------- 接通卡片 ---------- */
.connect-card {
  align-self: center;
  width: min(520px, 100%);
  margin: 24px auto 8px;
  padding: 30px 32px;
  border: 1px solid $line;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 10px 30px -20px rgba(31, 35, 41, 0.3);
  text-align: center;

  .connect-icon {
    width: 52px; height: 52px;
    margin: 0 auto 14px;
    border-radius: 14px;
    display: flex; align-items: center; justify-content: center;
    font-size: 24px;
    background: $amber-soft;
    color: $amber;
    border: 1px solid rgba(212, 136, 6, 0.25);
  }
  h3 {
    margin: 0 0 8px;
    font-family: 'Noto Serif SC', serif;
    font-size: 19px;
    letter-spacing: 2px;
    color: $ink-900;
  }
  .connect-tip {
    margin: 0 0 20px;
    font-size: 13px;
    line-height: 1.8;
    color: $muted;
    b { color: $amber; font-weight: 600; }
  }
  .connect-form {
    display: flex;
    flex-direction: column;
    gap: 10px;
    .field {
      height: 40px;
      border: 1px solid $line;
      border-radius: 10px;
      padding: 0 14px;
      font-size: 14px;
      color: $ink-900;
      background: #ffffff;
      outline: none;
      transition: border-color 0.2s, box-shadow 0.2s;
      &:focus { border-color: rgba(212, 136, 6, 0.6); box-shadow: 0 0 0 3px rgba(212, 136, 6, 0.12); }
    }
  }
}

.btn-wide { width: 100%; }

.connect-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 14px;
  color: $muted;
  font-size: 12px;
  &::before, &::after { content: ''; flex: 1; height: 1px; background: $line; }
}

.connect-warn {
  margin: 10px 0 0;
  font-size: 12.5px;
  color: #a8071a;
  background: #fff1f0;
  border: 1px solid #ffccc7;
  border-radius: 8px;
  padding: 7px 12px;
}

/* ---------- 人工确认门 ---------- */
.gate {
  margin: 0 24px 12px;
  padding: 16px 20px 18px;
  border-radius: 14px;
  border: 1px solid rgba(212, 136, 6, 0.35);
  background: linear-gradient(180deg, #fffdf8, #ffffff);
  box-shadow: 0 10px 26px -20px rgba(212, 136, 6, 0.55);

  &.gate-pay {
    border-color: rgba(14, 143, 132, 0.35);
    background: linear-gradient(180deg, #f7fdfc, #ffffff);
    box-shadow: 0 10px 26px -20px rgba(14, 143, 132, 0.5);
  }

  .gate-head {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 600;
    color: $ink-900;
    margin-bottom: 12px;
    :first-child { color: $amber; font-size: 16px; }
  }
  &.gate-pay .gate-head :first-child { color: $cyan; }

  .gate-grid {
    margin: 0;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 22px;
    > div { display: flex; gap: 10px; font-size: 13px; line-height: 1.7; }
    dt { color: $muted; flex-shrink: 0; min-width: 52px; }
    dd { margin: 0; color: $ink-800; word-break: break-all; }
    .wide { grid-column: 1 / -1; }
    .total { color: $amber; font-weight: 700; font-size: 15px; }
    .dim { color: $muted; }
  }

  .gate-details {
    margin-top: 10px;
    border-top: 1px dashed $line;
    padding-top: 8px;
    .detail-row {
      display: flex;
      gap: 14px;
      font-size: 12.5px;
      color: $ink-700;
      line-height: 1.9;
      .amount { margin-left: auto; color: $ink-900; font-weight: 600; }
    }
  }

  .gate-note {
    width: 100%;
    margin-top: 12px;
    height: 36px;
    border: 1px solid $line;
    border-radius: 9px;
    padding: 0 12px;
    font-size: 13px;
    color: $ink-900;
    background: #ffffff;
    outline: none;
    &:focus { border-color: rgba(212, 136, 6, 0.6); box-shadow: 0 0 0 3px rgba(212, 136, 6, 0.12); }
  }

  .gate-actions {
    display: flex;
    gap: 10px;
    margin-top: 12px;
  }
}

.mono { font-family: 'IBM Plex Mono', monospace; }

/* ---------- 按钮 ---------- */
.btn-primary {
  height: 40px;
  padding: 0 22px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #f0b429, $amber);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
  cursor: pointer;
  transition: filter 0.2s, transform 0.1s;
  &:hover:not(:disabled) { filter: brightness(1.05); }
  &:active:not(:disabled) { transform: translateY(1px); }
  &:disabled { opacity: 0.55; cursor: not-allowed; }
}
.btn-ghost {
  height: 40px;
  padding: 0 22px;
  border: 1px solid $line;
  border-radius: 10px;
  background: #ffffff;
  color: $ink-700;
  font-size: 14px;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;
  &:hover:not(:disabled) { border-color: rgba(31, 35, 41, 0.3); color: $ink-900; }
  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

/* ---------- 快捷话术 ---------- */
.gd-suggest {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 24px 10px;
  background: #ffffff;
  .chip {
    border: 1px solid $line;
    background: #ffffff;
    color: $ink-700;
    border-radius: 999px;
    padding: 6px 14px;
    font-size: 12.5px;
    cursor: pointer;
    transition: all 0.2s;
    &:hover:not(:disabled) { border-color: rgba(212, 136, 6, 0.5); color: $amber; background: $amber-soft; }
    &:disabled { opacity: 0.5; cursor: not-allowed; }
  }
}

/* ---------- 输入区 ---------- */
.gd-dock {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 24px 18px;
  border-top: 1px solid $line;
  background: #ffffff;

  .gd-input {
    flex: 1;
    resize: none;
    border: 1px solid $line;
    border-radius: 12px;
    background: #ffffff;
    padding: 11px 14px;
    font-size: 14px;
    line-height: 1.6;
    color: $ink-900;
    font-family: inherit;
    outline: none;
    max-height: 132px;
    transition: border-color 0.2s, box-shadow 0.2s;
    &:focus { border-color: rgba(212, 136, 6, 0.6); box-shadow: 0 0 0 3px rgba(212, 136, 6, 0.12); }
    &:disabled { background: #f7f8fa; color: $muted; cursor: not-allowed; }
  }
  .send {
    flex-shrink: 0;
    width: 42px;
    height: 42px;
    border: none;
    border-radius: 12px;
    background: linear-gradient(135deg, #f0b429, $amber);
    color: #fff;
    font-size: 17px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: filter 0.2s;
    &:hover:not(:disabled) { filter: brightness(1.05); }
    &:disabled { opacity: 0.45; cursor: not-allowed; }
  }
}
</style>
