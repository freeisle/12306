# ai-agent-py · 对话式购票 Agent+ 候补智能推荐

12306 AI 智能化：**对话式购票 Agent** 与 **候补购票智能推荐**。
用 Python（LangGraph + LangChain + FastAPI）实现「查询 → 下单」全链路的对话式代理，
加入 **Tool Calling** 与 **LangGraph 状态机**，并以 **interrupt 人工确认** 落实支付前 HITL、
以 **幂等键台账 + 服务端用户锁** 落实下单幂等。

当用户想要的车次/席别无票时，Agent 给出**「候补 vs 改乘」的智能决策推荐**——
用**确定性启发式打分算法**（`app/planning/waitlist_scorer.py`，纯函数、不经 LLM）估算候补成功率百分比，
并对有票的替代车次按用户模糊偏好（不想半夜到、优先高铁、价格敏感等）排序。
**本场景严格区分 LLM 与算法边界**：规划/估算是确定性问题交给算法，LLM 只做「理解人话偏好」与「说人话推荐」。

与`services/ai-service`（Java/LangChain4j RAG 客服）互补：
ai-service 负责「答问题」，本模块负责「办事情」（真实调用 12306 微服务完成购票动作）。

## 一、架构

```
                        ┌─────────────────────────── ai-agent-py (port 10007) ───────────────────────────┐
 控制台/CLI/curl ──HTTP──┤  FastAPI: /agent/session /agent/chat /agent/resume /agent/health             │
                        │      │                                                                       │
                        │      ▼                                                                       │
                        │  LangGraph StateGraph（checkpointer: MemorySaver）                            │
                        │                                                                              │
                        │   START → agent ⇄ tools ──登记计划──→ confirm_order ──interrupt①(人工确认下单)│
                        │             ↑                             │同意            │拒绝               │
                        │             └─────────────────────────────┼──────────────┘                   │
                        │                                           ▼                                  │
                        │                                      place_order（幂等台账）                  │
                        │                                           │失败→agent                          │
                        │                                           ▼成功                               │
                        │                                      pay_gate ──interrupt(支付前人工确认)    │
                        │                                           │同意             │拒绝 → END        │
                        │                                           ▼                                  │
                        │                                      initiate_pay → END                      │
                        └──────────────┬───────────────────────────────────────────────────────────────┘
                                       │ httpx（Authorization: <accessToken>）
                                       ▼
                        12306 网关 :10000 ── user-service / ticket-service / order-service / pay-service
```

- **agent 节点**：`ChatOpenAI`（DashScope 兼容模式）`.bind_tools(...)`，按 `tool_calls` 驱动工具；
- **tools 节点**：自研执行器（非 ToolNode），统一注入会话、统一 `Result<T>` 解包、统一错误回灌；
- **confirm_order / pay_gate**：`langgraph.types.interrupt` 挂起图，客户端 `/agent/resume` 携 `Command(resume=...)` 恢复；
- **place_order**：客户端幂等台账（`idempotency_key → order_result`），同意图同意图重放不重复发单；
  服务端另有 `@Idempotent(SPEL)` 按 username 的非阻塞锁兜底并发重复下单。

## 二、快速开始

```bash
conda activate langgraph            # Python 3.13，依赖见 requirements.txt
cd ai-agent-py
copy .env.example .env              # 填 AI_LLM_API_KEY（DashScope）

# 方式一：HTTP 服务
python -m uvicorn app.main:app --port 10007

# 方式二：交互式 CLI（推荐演示）
python -m app.cli --username admin --password <密码>
```

> **密钥配置唯一生效位置：`ai-agent-py/.env`**（`.env.example` 只是模板，不会被读取）。
> 密钥在阿里云百炼控制台 → API-KEY 管理创建；配置**只在进程启动时读取一次**，
> 修改 `.env` 后必须重启 uvicorn/CLI，否则运行中的进程仍用旧值（典型症状：模型调用 401 invalid_api_key）。
> 自检：`GET /agent/health` 返回 `llm_key_set` 与 `llm_key_hint`（首尾指纹），可核对运行中进程加载的是哪把 key。

前置条件：12306 网关与各微服务已启动（网关默认 `http://127.0.0.1:10000`，
聚合模式单进程亦可）；`.env` 中 `BASE_12306_URL` 可改。

## 三、HTTP 契约

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/agent/session` | `{access_token}`（复用控制台登录态，免密码）或 `{username,password}` → 建会话返回 `thread_id` |
| POST | `/agent/chat` | `{thread_id?, message, username?, password?, access_token?}` → 推进图直到下一个 interrupt 或 END |
| POST | `/agent/resume` | `{thread_id, approved, note?}` → 恢复被 interrupt 挂起的确认门 |
| GET | `/agent/health` | 探活；附带 `llm_key_set`/`llm_key_hint`/`llm_base_url` 便于核对密钥配置 |

`chat/resume` 响应统一为：

```json
{"thread_id": "...", "reply": "助手最新回复", "pending": null, "done": true}
```

`pending` 非空表示图挂起等待人工确认：

```json
{"pending": {"type": "confirm_order", "payload": {"plan": {"train_number": "G101", "total_yuan": 550.0, "...": "..."}}}}
{"pending": {"type": "confirm_pay",   "payload": {"order": {"order_sn": "1798...", "total_yuan": 550.0}}}}
```

## 四、工具清单（Tool Calling）

| 工具 | 后端接口 | 说明 |
| --- | --- | --- |
| `search_trains` | `GET /api/ticket-service/ticket/query` + `/station/all` | 城市名自动解析站码；返回余票与票价(元) |
| `recommend_ticket_options` | 同上（内部复用 `search_trains`）+ 本地打分引擎 | 场景三：无票/候补决策时给出候补成功率估算(%)与改乘有票方案排序 |
| `list_passengers` | `GET /api/user-service/passenger/query` | 真实乘车人 id，禁止臆造 |
| `submit_order_plan` | 无（登记计划） | 产出 `order_plan` + `idempotency_key`，触发确认门① |
| `query_order` | `GET /api/order-service/order/ticket/query` | 订单状态/座位/金额查询 |
| （节点内部）`purchase_tickets` | `POST /api/ticket-service/ticket/purchase/v2` | 仅 place_order 节点在确认①通过后调用 |
| （节点内部）`create_pay_order` | `POST /api/pay-service/pay/create` | 仅 initiate_pay 节点在确认②通过后调用 |

金额约定：查票域 `price` 为元；订单域 `amount` 为分（工具层统一换算为元）；支付域 `totalAmount` 为元。

## 五、HITL 与幂等设计

1. **确认门①（下单前）**：`confirm_order` 节点先输出计划摘要，再 `interrupt` 挂起；
   拒绝时把用户意见以 HumanMessage 回灌 agent，LLM 可改方案重新登记。
2. **确认门②（支付前）**：`pay_gate` 节点在订单创建后、任何资金动作前 `interrupt`；
   拒绝则订单保留待支付并告知后续路径；同意才创建支付单（支付宝沙箱表单，最终付款仍由人完成）。
3. **幂等**：`idempotency_key = sha256(thread|trainId|seatType|sorted(passengerIds)|date)`；
   place_order 先查台账，命中则复用原订单（回复中显式标注「复用原订单，未重复发单」），
   未命中才发单并写台账；并发重复由服务端 `@Idempotent` 用户锁拒绝。
4. **可恢复性**：interrupt 依赖 checkpointer（默认 `MemorySaver`，进程内）；
   生产可换 `SqliteSaver/PostgresSaver` 以跨重启恢复挂起会话。

## 六、候补购票智能推荐（LLM/算法边界）

**这是本场景最重要的架构判断：该用算法的绝不用 LLM。** 候补成功率是可被规则刻画的确定性估算，
交给纯函数算法 `app/planning/waitlist_scorer.py`；LLM 只负责把用户模糊偏好翻译成打分入参、
再把算分结果讲成人话。算法模块不 import 任何 LLM/网络依赖，可完全离线单测。

- **工具入口**：`recommend_ticket_options(from_city, to_city, departure_date, avoid_night_arrival,
  prefer_high_speed, price_sensitive, preferred_seat_type, max_extra_minutes)`。内部复用 `search_trains`
  取真实余票，再调 `analyze()` 打分，返回结构化推荐。
- **候补成功率因子**（透明可追溯，`estimate_waitlist_success`）：距发车天数（3-5 天退票/改签释放高峰→最高，
  当天太仓促→低）、发车时段（黄金时段需求旺→更难，夜间冷门→更易）、车型（高铁/动车更抢手→更难）、
  席别（二等座需求最大→更难，商务/一等座→更易）。输出百分比 + 档位(高/中/低) + 影响因子列表，
  并附**估算声明**（非官方数据，仅供决策参考）。
- **方案分桶**：`buy_now`(余票充足可下单) / `low_stock`(余票紧张提示尽快下单) /
  `waitlist`(售罄且 `candidate=true` 可候补，含成功率) / `unavailable`(售罄且不可候补)；
  顶层 `recommendation` 优先推有票改乘，无票才推候补兜底。
- **候补仅为决策建议**：本系统**不代用户提交候补订单**（未落地真实候补交易），需候补时引导用户到官方 12306 提交；
  若推荐改乘某趟有票车次且用户同意，则复用场景一的 `submit_order_plan → confirm_order → place_order → pay_gate` 链路。
- **后端数据依赖**：`ticket-service` 的 `SeatClassDTO.candidate` 已由硬编码 `false` 改为「席别售罄(quantity≤0)即开放候补」，
  使候补资格能被上层感知（V1/V2 两条查询路径均已修正）。
- **演示测试数据**：`resources/data/12306-springcloud-ticket-waitlist-demo.sql` 把 G35(train_id=1) 全席位售罄、
  保留 G39(train_id=2) 有票（两者均经停北京南→杭州东），构成「候补 vs 改乘」对照组。
  执行后需清除 Redis 余票缓存键 `index12306-ticket-service:train_station_remaining_ticket:1_*` 使改动生效。

> 后续接入余票快照采集流水线（Canal binlog / 定时采样）后，可把 `estimate_waitlist_success` 平滑替换为
> 时序/梯度提升模型，工具层与编排层无需改动。

## 七、测试

```bash
python -m pytest tests -q
```

- `tests/test_graph_hitl.py` 用脚本化模型 + 假 HTTP 离线验证：
  双 interrupt 顺序、确认前零发单、拒绝支付零支付单、同意图重放不重复发单（幂等）、拒绝计划回 agent 不改单。
- `tests/test_waitlist_scorer.py` 验证确定性打分：成功率高峰/当天低谷分档、钳位、各桶归类、
  顶层推荐优先级、夜间到达偏好排序、余票紧张桶。
- `tests/test_waitlist_tool.py` 用假会话桩模拟真实 DB 状态（G35 售罄可候补 / G39 有票），
  端到端验证 `recommend_ticket_options` 的接线与「候补 vs 改乘」推荐输出。

## 八、边界与后续

- 支付仅创建支付单；沙箱实际付款与回调不在代理职责内（无 mock 回调接口）。
- 改签/退票、流式输出(SSE)、向量记忆（跨会话偏好）留待后续场景。
- 会话与台账当前为进程内存储，多实例部署前需外置（Redis/PG）。
