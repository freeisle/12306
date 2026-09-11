/**
 * AI导购会话持久化（模块级单例 + sessionStorage）。
 *
 * 组件卸载（切菜单/刷新）不丢对话：
 * - 模块级 reactive 单例：SPA 内路由切换后回到页面可立即恢复；
 * - sessionStorage 备份：整页刷新后仍可恢复（仅当前标签页，不跨设备）。
 * 注意：只存 UI 历史与 thread_id；Agent 侧图状态仍在其进程内存（MemorySaver）。
 */
import { reactive } from 'vue'

const SS_KEY = 'ai-agent-chat-v1'

export const agentChat = reactive({
  threadId: '',
  messages: [],
  gate: null
})

/** 从 sessionStorage 恢复；返回是否有可恢复的会话。会过滤掉未完成的 typing 占位。 */
export function hydrateAgentChat() {
  try {
    const raw = window.sessionStorage.getItem(SS_KEY)
    if (!raw) return false
    const s = JSON.parse(raw)
    agentChat.threadId = s.threadId || ''
    agentChat.messages = Array.isArray(s.messages) ? s.messages.filter((m) => m && !m.pending) : []
    agentChat.gate = s.gate || null
    return !!agentChat.threadId
  } catch (e) {
    return false
  }
}

/** 将当前单例状态写入 sessionStorage。 */
export function persistAgentChat() {
  try {
    window.sessionStorage.setItem(
      SS_KEY,
      JSON.stringify({
        threadId: agentChat.threadId,
        messages: agentChat.messages,
        gate: agentChat.gate
      })
    )
  } catch (e) {
    /* 存储不可用时静默降级：仅保留模块级单例 */
  }
}

/** 清空会话（供后续"新会话"能力使用）。 */
export function resetAgentChat() {
  agentChat.threadId = ''
  agentChat.messages = []
  agentChat.gate = null
  try {
    window.sessionStorage.removeItem(SS_KEY)
  } catch (e) {
    /* ignore */
  }
}
