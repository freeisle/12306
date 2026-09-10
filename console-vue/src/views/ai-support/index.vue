<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { message as antMessage } from 'ant-design-vue'
import { RobotOutlined, UserOutlined, ArrowUpOutlined, ThunderboltFilled } from '@ant-design/icons-vue'
import {
  fetchAiSupportAsk,
  fetchAiSuggestedQuestions,
  fetchAiSupportHealth
} from '@/service'

const MODE_LABEL = {
  LLM: '大模型生成',
  EXTRACTIVE: '离线抽取',
  FALLBACK: '知识库兜底',
  BLOCKED: '限流 / 熔断'
}

const bodyRef = ref(null)
const inputRef = ref(null)
const sending = ref(false)
const input = ref('')
const messages = reactive([])
const suggestions = ref([])
const kb = reactive({ ready: false, chunks: 0, label: '连接中' })

const scrollToBottom = () => {
  nextTick(() => {
    const el = bodyRef.value
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  })
}

const pushMessage = (msg) => {
  messages.push({ id: Date.now() + Math.random(), refsOpen: false, ...msg })
  scrollToBottom()
}

const ask = async (raw) => {
  const question = (raw ?? input.value).trim()
  if (!question || sending.value) return
  pushMessage({ role: 'user', content: question })
  input.value = ''
  autoGrow()
  sending.value = true
  const pending = { role: 'assistant', pending: true }
  messages.push({ id: Date.now() + Math.random(), ...pending })
  scrollToBottom()
  try {
    const res = await fetchAiSupportAsk({ question })
    messages.pop() // 移除 pending 占位
    if (res && res.success && res.data) {
      pushMessage({ role: 'assistant', ...res.data })
    } else {
      pushMessage({
        role: 'assistant',
        error: true,
        answer: (res && res.message) || '服务返回异常，请稍后重试。'
      })
    }
  } catch (e) {
    messages.pop()
    pushMessage({
      role: 'assistant',
      error: true,
      answer: '请求失败，请确认 ai-service 已启动（默认端口 9006）。'
    })
    antMessage.error('智能客服请求失败')
  } finally {
    sending.value = false
    nextTick(() => inputRef.value && inputRef.value.focus())
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
    ask()
  }
}

const toggleRefs = (m) => (m.refsOpen = !m.refsOpen)

onMounted(async () => {
  pushMessage({
    role: 'assistant',
    greeting: true,
    answer:
      '您好，我是 12306 智能客服。退票、改签、学生票、儿童票、安检、候补购票等问题都可以问我，答案会附上可核对的规则来源。'
  })
  try {
    const h = await fetchAiSupportHealth()
    if (h && h.success && h.data) {
      kb.ready = true
      kb.chunks = h.data.knowledgeChunks ?? 0
      kb.label = `知识库就绪 · ${kb.chunks} 片段`
    } else {
      kb.label = '知识库未就绪'
    }
  } catch (e) {
    kb.label = '未连接服务'
  }
  try {
    const q = await fetchAiSuggestedQuestions()
    if (q && q.success && Array.isArray(q.data)) suggestions.value = q.data
  } catch (e) {
    /* 推荐问题加载失败不阻断使用 */
  }
})
</script>

<template>
  <div class="concierge">
    <div class="grain" aria-hidden="true"></div>
    <div class="glow glow-a" aria-hidden="true"></div>
    <div class="glow glow-b" aria-hidden="true"></div>

    <!-- 顶栏 -->
    <header class="cj-header">
      <div class="brand">
        <span class="brand-mark">智能客服</span>
        <span class="brand-tag">AI · RAG</span>
      </div>
      <div class="status" :class="{ ready: kb.ready }">
        <span class="dot"></span>
        <span class="status-text">{{ kb.label }}</span>
      </div>
    </header>

    <!-- 会话区 -->
    <div class="cj-body" ref="bodyRef" aria-live="polite">
      <transition-group name="msg">
        <div
          v-for="m in messages"
          :key="m.id"
          class="row"
          :class="m.role"
        >
          <div class="avatar" :class="m.role">
            <RobotOutlined v-if="m.role === 'assistant'" />
            <UserOutlined v-else />
          </div>

          <div class="bubble-wrap">
            <!-- 思考中 -->
            <div v-if="m.pending" class="bubble assistant typing">
              <span></span><span></span><span></span>
            </div>

            <template v-else>
              <div
                class="bubble"
                :class="[m.role, { error: m.error, greeting: m.greeting }]"
              >
                <p class="text">{{ m.content || m.answer }}</p>

                <template v-if="m.role === 'assistant' && !m.error">
                  <div class="meta">
                    <span
                      v-if="m.confidence"
                      class="signal"
                      :class="(m.confidence || '').toLowerCase()"
                    >
                      <i class="lamp"></i>{{ m.confidence }}
                    </span>
                    <span v-if="m.mode" class="chip-mono">{{ MODE_LABEL[m.mode] || m.mode }}</span>
                    <span v-if="m.hitCache" class="chip-mono cache">
                      <ThunderboltFilled /> 缓存命中
                    </span>
                    <span v-if="m.latencyMs != null" class="chip-mono">{{ m.latencyMs }} ms</span>
                  </div>

                  <div v-if="m.references && m.references.length" class="refs">
                    <button class="refs-toggle" @click="toggleRefs(m)">
                      <span class="perf"></span>
                      {{ m.refsOpen ? '收起' : '查看' }}引用来源 · {{ m.references.length }}
                    </button>
                    <transition name="slide">
                      <div v-show="m.refsOpen" class="refs-list">
                        <div
                          v-for="(r, i) in m.references"
                          :key="i"
                          class="coupon"
                        >
                          <div class="coupon-head">
                            <span class="coupon-src">{{ r.source }} · {{ r.title }}</span>
                            <span class="coupon-score">{{ r.score != null ? r.score.toFixed(3) : '—' }}</span>
                          </div>
                          <div class="coupon-snippet">{{ r.snippet }}</div>
                        </div>
                      </div>
                    </transition>
                  </div>
                </template>
              </div>
            </template>
          </div>
        </div>
      </transition-group>
    </div>

    <!-- 推荐问题 -->
    <div v-if="suggestions.length" class="cj-suggest">
      <button
        v-for="(s, i) in suggestions"
        :key="i"
        class="chip"
        :disabled="sending"
        @click="ask(s)"
      >
        {{ s }}
      </button>
    </div>

    <!-- 输入区 -->
    <footer class="cj-dock">
      <textarea
        ref="inputRef"
        v-model="input"
        rows="1"
        class="cj-input"
        placeholder="输入您的问题，Enter 发送 / Shift + Enter 换行"
        @keydown="onKeydown"
        @input="autoGrow"
      ></textarea>
      <button class="send" :disabled="sending || !input.trim()" @click="ask()" title="发送">
        <ArrowUpOutlined />
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap');

$ink-900: #080d18;
$ink-800: #0c1424;
$ink-700: #111c30;
$amber: #f0a63c;
$amber-soft: rgba(240, 166, 60, 0.14);
$cyan: #4fd1c5;
$paper: #edf1f7;
$muted: #93a0b8;
$line: rgba(255, 255, 255, 0.08);

.concierge {
  position: relative;
  height: calc(100vh - 190px);
  min-height: 540px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid rgba(240, 166, 60, 0.16);
  background:
    radial-gradient(120% 90% at 12% -10%, rgba(79, 209, 197, 0.10), transparent 55%),
    radial-gradient(120% 100% at 100% 110%, rgba(240, 166, 60, 0.12), transparent 55%),
    linear-gradient(160deg, $ink-800 0%, $ink-900 60%, #060a12 100%);
  box-shadow: 0 24px 60px -20px rgba(4, 8, 16, 0.75), inset 0 1px 0 rgba(255, 255, 255, 0.04);
  font-family: 'PingFang SC', 'Microsoft YaHei', -apple-system, sans-serif;
  color: $paper;

  .grain {
    position: absolute;
    inset: 0;
    pointer-events: none;
    opacity: 0.5;
    mix-blend-mode: overlay;
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='140' height='140'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.35'/%3E%3C/svg%3E");
  }
  .glow {
    position: absolute;
    border-radius: 50%;
    filter: blur(70px);
    pointer-events: none;
    z-index: 0;
  }
  .glow-a { width: 340px; height: 340px; top: -120px; left: -80px; background: rgba(79, 209, 197, 0.14); }
  .glow-b { width: 380px; height: 380px; bottom: -140px; right: -90px; background: rgba(240, 166, 60, 0.16); }
}

/* ---------- 顶栏 ---------- */
.cj-header {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid $line;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent);

  .brand {
    display: flex;
    align-items: baseline;
    gap: 12px;
    .brand-mark {
      font-family: 'Noto Serif SC', serif;
      font-weight: 700;
      font-size: 22px;
      letter-spacing: 3px;
      background: linear-gradient(92deg, #fff 20%, $amber 90%);
      -webkit-background-clip: text;
      background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .brand-tag {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 11px;
      letter-spacing: 2px;
      color: $cyan;
      border: 1px solid rgba(79, 209, 197, 0.35);
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
    .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: #6b7688; box-shadow: 0 0 0 0 rgba(240, 166, 60, 0.6);
    }
    &.ready .dot { background: $cyan; animation: pulse 2.2s infinite; }
    &.ready .status-text { color: rgba(237, 241, 247, 0.85); }
  }
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(79, 209, 197, 0.55); }
  70% { box-shadow: 0 0 0 9px rgba(79, 209, 197, 0); }
  100% { box-shadow: 0 0 0 0 rgba(79, 209, 197, 0); }
}

/* ---------- 会话区 ---------- */
.cj-body {
  position: relative;
  z-index: 2;
  flex: 1;
  overflow-y: auto;
  padding: 26px 24px 8px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  scroll-behavior: smooth;
  &::-webkit-scrollbar { width: 8px; }
  &::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.1); border-radius: 8px; }
  &::-webkit-scrollbar-thumb:hover { background: rgba(240, 166, 60, 0.35); }
}

.row {
  display: flex;
  gap: 12px;
  max-width: 82%;
  &.user { align-self: flex-end; flex-direction: row-reverse; }
  &.assistant { align-self: flex-start; }
}
.avatar {
  flex-shrink: 0;
  width: 36px; height: 36px; border-radius: 11px;
  display: flex; align-items: center; justify-content: center;
  font-size: 17px;
  &.assistant {
    color: $ink-900;
    background: linear-gradient(140deg, $amber, #ffca7a);
    box-shadow: 0 6px 16px -6px rgba(240, 166, 60, 0.7);
  }
  &.user {
    color: $paper;
    background: rgba(255, 255, 255, 0.07);
    border: 1px solid $line;
  }
}
.bubble-wrap { min-width: 0; }

.bubble {
  padding: 13px 16px;
  border-radius: 16px;
  line-height: 1.75;
  font-size: 14.5px;
  position: relative;

  .text { white-space: pre-wrap; word-break: break-word; margin: 0; }

  &.assistant {
    background: rgba(255, 255, 255, 0.045);
    border: 1px solid $line;
    border-top-left-radius: 5px;
    backdrop-filter: blur(6px);
    &::before {
      content: '';
      position: absolute; left: 0; top: 12px; bottom: 12px; width: 2px;
      border-radius: 2px;
      background: linear-gradient(180deg, $amber, $cyan);
      opacity: 0.7;
    }
  }
  &.user {
    background: linear-gradient(135deg, $amber, #e08f28);
    color: #20160a;
    border-top-right-radius: 5px;
    font-weight: 500;
    box-shadow: 0 10px 24px -12px rgba(240, 166, 60, 0.8);
  }
  &.greeting .text { color: rgba(237, 241, 247, 0.9); }
  &.error {
    background: rgba(197, 34, 31, 0.12);
    border-color: rgba(240, 90, 80, 0.4);
    color: #ffb4ad;
    &::before { background: #f05a50; }
  }
}

/* 思考中 */
.typing { display: inline-flex; gap: 5px; align-items: center; padding: 16px 18px;
  span { width: 7px; height: 7px; border-radius: 50%; background: $amber; opacity: 0.4; animation: blink 1.3s infinite both;
    &:nth-child(2) { animation-delay: 0.18s; } &:nth-child(3) { animation-delay: 0.36s; } }
}
@keyframes blink { 0%, 80%, 100% { opacity: 0.25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }

/* 元信息 */
.meta {
  display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
  margin-top: 11px; padding-top: 10px; border-top: 1px dashed rgba(255, 255, 255, 0.09);
  font-family: 'IBM Plex Mono', monospace; font-size: 11px;
}
.signal {
  display: inline-flex; align-items: center; gap: 6px; text-transform: uppercase; letter-spacing: 1px;
  .lamp { width: 7px; height: 7px; border-radius: 50%; background: currentColor; box-shadow: 0 0 8px currentColor; }
  &.high { color: $cyan; } &.medium { color: $amber; } &.low { color: #f0736a; }
}
.chip-mono {
  color: $muted; border: 1px solid $line; border-radius: 5px; padding: 2px 8px; background: rgba(255, 255, 255, 0.03);
  &.cache { color: $amber; border-color: rgba(240, 166, 60, 0.3); }
}

/* 引用副券 */
.refs { margin-top: 12px; }
.refs-toggle {
  display: inline-flex; align-items: center; gap: 9px;
  background: none; border: none; cursor: pointer;
  font-family: 'IBM Plex Mono', monospace; font-size: 12px; color: $cyan;
  padding: 3px 0; letter-spacing: 0.5px; transition: color 0.18s;
  &:hover { color: #7ff0e4; }
  .perf {
    width: 16px; height: 8px; border-radius: 2px;
    background: repeating-linear-gradient(90deg, $cyan 0 2px, transparent 2px 4px);
    opacity: 0.6;
  }
}
.refs-list { margin-top: 10px; display: flex; flex-direction: column; gap: 9px; }
.coupon {
  position: relative;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid $line;
  border-left: 3px solid rgba(240, 166, 60, 0.55);
  border-radius: 4px 12px 12px 4px;
  padding: 10px 13px 10px 16px;
  /* 穿孔：左侧半圆缺口，模拟车票副券 */
  -webkit-mask-image: radial-gradient(circle at 0 50%, transparent 5px, #000 5.5px);
  mask-image: radial-gradient(circle at 0 50%, transparent 5px, #000 5.5px);

  .coupon-head {
    display: flex; justify-content: space-between; gap: 10px; align-items: baseline;
    font-family: 'Noto Serif SC', serif; font-weight: 600; font-size: 13px; color: rgba(237, 241, 247, 0.92);
    .coupon-score { font-family: 'IBM Plex Mono', monospace; font-size: 11px; color: $amber; white-space: nowrap; }
  }
  .coupon-snippet { margin-top: 4px; font-size: 12.5px; line-height: 1.6; color: $muted; }
}

/* ---------- 推荐问题 ---------- */
.cj-suggest {
  position: relative; z-index: 2;
  display: flex; flex-wrap: wrap; gap: 8px;
  padding: 6px 24px 12px;
}
.chip {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid $line;
  color: rgba(237, 241, 247, 0.8);
  padding: 7px 13px; border-radius: 20px; cursor: pointer;
  font-size: 12.5px; font-family: inherit; transition: all 0.18s;
  &:hover:not(:disabled) {
    background: $amber-soft; border-color: rgba(240, 166, 60, 0.5); color: #ffd79a; transform: translateY(-1px);
  }
  &:disabled { opacity: 0.45; cursor: not-allowed; }
}

/* ---------- 输入区 ---------- */
.cj-dock {
  position: relative; z-index: 2;
  display: flex; align-items: flex-end; gap: 12px;
  padding: 14px 20px 18px;
  border-top: 1px solid $line;
  background: linear-gradient(0deg, rgba(6, 10, 18, 0.6), transparent);
}
.cj-input {
  flex: 1; resize: none; max-height: 132px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid $line; border-radius: 14px;
  padding: 12px 16px; color: $paper; font-size: 14.5px; line-height: 1.6;
  font-family: inherit; outline: none; transition: border-color 0.18s, box-shadow 0.18s;
  &::placeholder { color: rgba(147, 160, 184, 0.7); }
  &:focus { border-color: rgba(240, 166, 60, 0.55); box-shadow: 0 0 0 3px rgba(240, 166, 60, 0.12); }
}
.send {
  flex-shrink: 0; width: 46px; height: 46px; border-radius: 14px; border: none; cursor: pointer;
  background: linear-gradient(140deg, $amber, #e08f28); color: #20160a; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 10px 22px -10px rgba(240, 166, 60, 0.85); transition: transform 0.16s, opacity 0.16s;
  &:hover:not(:disabled) { transform: translateY(-2px); }
  &:active:not(:disabled) { transform: translateY(0); }
  &:disabled { opacity: 0.4; cursor: not-allowed; box-shadow: none; }
}

/* ---------- 动效 ---------- */
.msg-enter-active { transition: all 0.34s cubic-bezier(0.22, 1, 0.36, 1); }
.msg-enter-from { opacity: 0; transform: translateY(14px); }
.msg-leave-active { transition: all 0.18s ease; position: absolute; }
.msg-leave-to { opacity: 0; }
.slide-enter-active, .slide-leave-active { transition: all 0.26s ease; overflow: hidden; }
.slide-enter-from, .slide-leave-to { opacity: 0; max-height: 0; }
.slide-enter-to, .slide-leave-from { opacity: 1; max-height: 600px; }
</style>
