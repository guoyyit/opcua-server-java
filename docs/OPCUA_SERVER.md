# OPC UA 核心服务集（本地回环实现）

本工程在进程内实现了一套 OPC UA 风格的核心服务集，用于本地设备接入与遥测订阅验证。
**不依赖任何外部服务或网络栈**：客户端（`LoopbackClient`）与服务端运行在同一 JVM，
通过直接方法调用完成“安全通道 → 会话 → 浏览/读写 → 订阅/发布/重发布”的完整闭环。

## 1. 分层结构

| 层 | 入口类 | 职责 |
| --- | --- | --- |
| 通道层 | `channel/SecureChannelService`、`channel/SecureChannel` | 通道建立、独立请求/响应序列、超时关闭、级联回收 |
| 会话层 | `session/SessionService`、`session/Session` | 创建/激活/关闭、匿名与凭据激活、空闲失效 |
| 身份校验 | `security/IdentityValidator`、`security/SimpleIdentityValidator` | 内存账户校验，失败不泄露细节 |
| 地址空间 | `addressspace/InMemoryAddressSpace`、`addressspace/DemoAddressSpace` | 层级引用、快照浏览与分页、原子读写 |
| 订阅层 | `subscription/SubscriptionService` 及内部状态类 | 采样、打包、发布、重发布、保留窗口与资源上限 |
| 服务门面 | `server/OpcUaServer` | 统一串联各层并做“通道→会话→权限”校验 |
| 回环客户端 | `client/LoopbackClient` | 维护单通道序列号，供验证与测试使用 |

演示地址空间：

```
Root ─ Organizes ─▶ Objects ─ Organizes ─▶ DeviceA
                                      ├─ HasProperty ▶ Temperature(Double, 可读写)
                                      ├─ HasProperty ▶ Pressure(Double, 只读)
                                      └─ HasProperty ▶ SetPoint(Integer, 可读写)
```

## 2. 通道状态语义

通道处于两种状态之一：

- **OPEN**：创建后即可用。每次合法请求都会刷新“最后活动时间”。
- **CLOSED**：显式关闭或超时后进入，终态不可恢复；客户端必须重新 `openChannel`。

规则：

1. 每条通道拥有**独立的请求序列号空间**，必须严格从 `1` 开始、连续递增（`1,2,3,...`）。
   跳号、重复或不从 1 开始均返回 `BAD_SEQUENCE_NUMBER_INVALID`，且不推进期望值，通道仍可用。
2. 响应序列号同样在通道内独立递增，由服务端生成。
3. 距最后活动时间超过通道生命周期即判定超时：
   - 清扫线程/显式清扫发现超时，会删除通道；此后请求得到 `BAD_SECURE_CHANNEL_ID_INVALID`；
   - 请求到达瞬间发现超时，得到 `BAD_SECURE_CHANNEL_TIMEOUT` 且通道随即关闭。
4. 通道关闭（含超时）会**级联关闭其绑定的全部会话**，进而回收这些会话的全部订阅与监控项，
   不残留等待队列。唯一例外见第 5 节的“订阅迁移”：迁移发生在通道关闭之前。

## 3. 会话状态语义

```
创建 → CREATED(未激活) ─ 激活成功 → ACTIVATED ─ 关闭/超时/通道关闭 → CLOSED(终态)
                 └── 激活失败：保持 CREATED，返回凭据错误
```

- **CREATED（未激活）**：禁止 Browse / Read / Write / 订阅等一切业务请求，返回
  `BAD_SESSION_NOT_ACTIVATED`。
- **ACTIVATED**：可用；每次业务请求刷新空闲时间。距最后活动超过会话超时时间即失效，
  下一次请求得到 `BAD_SESSION_TIMEOUT`。
- **CLOSED**：显式关闭、空闲失效或其通道关闭后进入终态；关闭会话会回收其全部订阅资源。

激活方式：

- 匿名激活：成功，可读但**不可写**（匿名写返回 `BAD_USER_ACCESS_DENIED`）。
- 凭据激活：内置账户 `operator/operator-pass`、`admin/admin-pass`；
  用户不存在与口令错误返回**完全相同**的 `BAD_IDENTITY_TOKEN_REJECTED` 与文案，
  口令比较采用恒定时间写法，避免账户枚举与时序泄露。
- 令牌格式问题返回 `BAD_IDENTITY_TOKEN_INVALID`，与凭据校验失败可区分。

## 4. 浏览与读写语义

### 浏览

- Browse 在调用瞬间对引用做**不可变快照**，排序后分页；BrowseNext 携带不透明
  continuationPoint 继续读取同一份快照。因此浏览期间并发增删节点，单次浏览不会
  漏节点或重复节点；快照后已删除的悬空引用不会出现在结果里，结果保持自洽。
- 节点不存在 → `BAD_NODE_ID_UNKNOWN`；引用类型不存在 → `BAD_REFERENCE_ID_UNKNOWN`。
- 支持 FORWARD / INVERSE / BOTH 方向与按引用类型过滤。

### 读写

- 值是不可变的 `DataValue`（value + 状态码 + 来源时间戳 + 服务端时间戳）。
  写入以“整体替换引用”方式发布，因此并发读写时任一读取看到的必然是**某个完整写入后的值**。
- 可区分错误码：
  - 节点不存在：`BAD_NODE_ID_UNKNOWN`
  - 值类型与节点数据类型不匹配：`BAD_TYPE_MISMATCH`
  - 节点只读/不可写：`BAD_NOT_WRITABLE`；不可读：`BAD_NOT_READABLE`
  - 用户权限不足（如匿名写）：`BAD_USER_ACCESS_DENIED`

## 5. 订阅、监控项与发布参数

| 参数 | 含义 |
| --- | --- |
| publishingIntervalMillis | 发布周期：把各监控项的变化打包成一条通知消息的周期 |
| samplingIntervalMillis | 每个监控项独立的采样间隔；仅采样到值变化才产生通知 |
| queueSize | 监控项通知队列大小 |
| discardPolicy | 队列满策略：`DISCARD_OLDEST` 丢最旧保最新；`DISCARD_NEWEST` 丢最新保最旧 |
| retentionWindowMillis | 已投递但未确认消息的**时间**保留窗口 |
| 保留消息条数上限 | 每个订阅 1000 条；超出回收最旧消息 |

顺序保证：

- 采样与发布由**单一调度线程**驱动（采样 → 按发布周期打包 → 入待发队列），
  即使采样间隔与发布周期错位，消息序列号在单个订阅内也**单调递增且连续**，不乱序、不跳号。
- 没有数据变化的发布周期产生 keep-alive（`PublishResult.keepAlive=true`），**不占序列号**。

发布/重发布：

- Publish 取走最旧的待发消息，消息进入 retained 区等待确认；
  Publish 可携带已确认序列号列表，确认后消息立即释放。
- 未确认消息在保留窗口（时间 + 条数）内可通过 Republish 按序列号取回；
  超出窗口或不存在的号返回 `BAD_MESSAGE_NOT_AVAILABLE`。
- **重连后重发布**：客户端重建通道/会话后，可在旧通道关闭前通过
  `transferSubscription` 把订阅迁移到新会话，随后在新会话上 Republish 漏收的序列号；
  迁移后旧会话关闭不会回收该订阅，订阅随新会话最终关闭而释放。
  按需求 1，若未迁移且通道已关闭，则会话与订阅一并回收，无法再重发。

## 6. 资源上限与会话隔离

| 上限 | 默认值 | 超限错误码 |
| --- | --- | --- |
| 会话数量 | 100 | `BAD_SESSION_LIMIT_REACHED` |
| 单会话监控项数量 | 200 | `BAD_TOO_MANY_MONITORED_ITEMS` |
| 单会话通知队列深度（各监控项队列之和） | 2000 | `BAD_NOTIFICATION_QUEUE_LIMIT_REACHED` |
| 单订阅保留消息条数 | 1000 | 超出回收最旧消息 |

上限可通过 `opcua.server.*` 配置覆盖（见 `OpcUaProperties`）。

慢消费隔离：每个会话拥有独立的发布等待锁/条件队列。一个会话不及时调用 Publish，
只会让其自身消息堆积/等待，不会阻塞调度线程，也不会阻塞其他会话的通知投递。

## 7. 错误码一览

| 状态码 | 含义 |
| --- | --- |
| GOOD | 成功 |
| BAD_NODE_ID_UNKNOWN | 节点不存在 |
| BAD_REFERENCE_ID_UNKNOWN | 引用类型不存在 |
| BAD_TYPE_MISMATCH | 写入值类型不匹配 |
| BAD_NOT_READABLE / BAD_NOT_WRITABLE | 节点访问级别不允许读/写 |
| BAD_USER_ACCESS_DENIED | 用户权限不足 |
| BAD_SECURE_CHANNEL_ID_INVALID | 通道标识无效（不存在或已关闭） |
| BAD_SECURE_CHANNEL_CLOSED / _TIMEOUT | 通道已关闭 / 通道已超时 |
| BAD_SEQUENCE_NUMBER_INVALID | 请求序列号非法 |
| BAD_SESSION_ID_INVALID / _CLOSED / _NOT_ACTIVATED / _TIMEOUT | 会话不存在/已关闭/未激活/已超时 |
| BAD_SESSION_LIMIT_REACHED | 会话数量超限 |
| BAD_IDENTITY_TOKEN_INVALID / _REJECTED | 令牌格式无效 / 凭据校验失败 |
| BAD_SUBSCRIPTION_ID_INVALID / _UNKNOWN | 订阅不属于该会话 / 订阅不存在或已回收 |
| BAD_MONITORED_ITEM_ID_INVALID / _UNKNOWN | 监控项标识无效 / 不存在或已回收 |
| BAD_TOO_MANY_MONITORED_ITEMS | 监控项数量超限 |
| BAD_NOTIFICATION_QUEUE_LIMIT_REACHED | 通知队列深度超限 |
| BAD_MESSAGE_NOT_AVAILABLE | 重发布序列号超出保留窗口或不存在 |
| BAD_NO_DATA / BAD_SUBSCRIPTION_NOT_TRANSFERRED | 无数据 / 订阅未迁移到当前会话 |

## 8. 本地回环验证

所有自动化测试均通过回环客户端在同一 JVM 内完成：

```bash
mvn test
```

关键场景与对应测试：

| 需求场景 | 测试类 |
| --- | --- |
| 通道超时、序列号、关闭级联回收 | `SecureChannelTest` |
| 会话未激活拦截、凭据拒绝、空闲失效、关闭回收 | `SessionTest` |
| 层级浏览、续读分页、并发浏览自洽、错误码 | `BrowseTest` |
| 时间戳/状态码、并发读写原子可见性、读写错误码 | `ReadWriteTest` |
| 数据变化通知、错位采样不乱序不丢号、丢弃策略 | `SubscriptionTest` |
| 保留窗口重发布、超窗回收、确认释放、重连重发 | `PublishRepublishTest` |
| 会话/监控项/队列上限、慢消费不阻塞他人 | `ConcurrencyAndLimitsTest` |
| 三级资源回收与迁移订阅的最终回收 | `ResourceReclamationIntegrationTest` |

测试通过 `TestServerFactory` 使用可控虚拟时钟并手工驱动采样/超时，保证时序确定性、
无对真实时间与外部服务的依赖。Spring 启动时由 `OpcUaConfig` 装配内存版服务，
真实运行则使用墙钟与后台调度线程。
