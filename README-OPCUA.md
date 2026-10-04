# OPC UA 核心服务集（本地回环实现）

本模块在 Spring Boot 内实现了一套用于本地设备接入与遥测订阅验证的 OPC UA
核心服务端能力。所有通信都走本机回环 TCP（`127.0.0.1` + 长度前缀 JSON 分帧），
不依赖任何外部服务。

## 分层结构

- 通道层：`opcua/transport` + `internal/SecureChannel`、`ChannelRegistry`
- 会话层：`internal/Session`、`SessionRegistry`、`CredentialStore`
- 地址空间：`internal/AddressSpace`、`Node`、`Reference`、`ContinuationPoint`
- 订阅发布：`internal/Subscription`、`MonitoredItem`、`SubscriptionManager`
- 服务分发：`server/ServiceLayer`
- 回环客户端：`client/OpcUaClient`

## 通道（SecureChannel）状态语义

- 每个 TCP 连接在首次请求前注册为一条安全通道，服务器返回独立的 `channelId`。
- 每条通道维护**各自独立**的请求序列与响应序列（均从 1 开始单调递增）。
  请求序号必须严格连续；序号违例返回 `BAD_SEQUENCE_NUMBER_INVALID` 并关闭通道。
- 通道有寿命（`opcua.default-channel-lifetime-ms`，OpenSecureChannel 可请求修订，
  服务器在默认值的 10 倍以内收口）。每次请求刷新活动时间。
- 超过寿命后由 reaper 关闭：关闭 socket、级联关闭该通道上的全部会话及其订阅，
  之后任何请求都被拒绝（传输层 EOF），客户端必须重新建立通道。
- 客户端显式 CloseSecureChannel：服务器先回成功响应，再关闭连接。
- 通道关闭时其关联的会话、订阅、监控项、待发布、保留通知全部回收，不再占用资源。

## 会话（Session）状态语义

- CreateSession 生成不透明 `sessionToken`，会话绑定到建立它的通道。
- 会话在 **ActivateSession 之前不可用**：此时 Browse/Read/Write 一律返回
  `BAD_SESSION_NOT_ACTIVATED`。
- 支持两种激活：
  - 匿名（IdentityToken policyId=`anonymous`）：授予 `anonymous`、`reader` 角色。
  - 用户名密码（policyId=`username`）：内置 `operator/operator-pass`、
    `admin/admin-pass`。校验失败统一返回 `BAD_IDENTITY_TOKEN_REJECTED`，
    不区分"用户不存在"与"密码错误"，使用摘要常量时间比较，不泄露校验细节。
- 会话有空闲超时（`opcua.default-session-timeout-ms`），任何已激活请求刷新活动时间。
  空闲超时后会话失效，再访问返回 `BAD_SESSION_CLOSED`/`BAD_SESSION_ID_INVALID`。
- CloseSession 后订阅被回收；会话不可跨通道使用，跨通道访问返回
  `BAD_SESSION_ID_INVALID`。

## 地址空间与浏览

- 内置节点：Root(84)/Objects(85)/Device(2000)，其下 Temperature(2001, 读写 Double)、
  Pressure(2002, 只读 Long)、Setpoint(2003, 读写 Int)、SerialNumber(2004, 只读 String)。
- Browse 返回按 NodeId 排序的**稳定快照**，并用 ContinuationPoint 分页。
  浏览与 BrowseNext 并发或节点集在分页期间变更时，每个游标都基于自己的不可变快照，
  不会漏节点或重复节点，结果自洽。
- 未知节点/引用：Browse、Read、Write 分别返回 `BAD_NODE_ID_UNKNOWN`。
- 续读点不存在或不属于该会话：`BAD_NO_CONTINUATION_POINT`；
  续读点数量超限：`BAD_CONTINUATION_POINT_LIMIT`。

## 读 / 写

- 值以 `DataValue` 表示，携带 `Variant` 值、状态码与**来源时间戳**。
- 节点值用锁保护：并发读写时，任一读取看到的都是某次**完整写入之后**的值。
- 写入按节点返回可区分状态码：
  - 节点不存在：`BAD_NODE_ID_UNKNOWN`
  - 类型不匹配（如把字符串写入数值节点）：`BAD_TYPE_MISMATCH`
  - 无写权限：`BAD_NOT_WRITABLE`；无读权限：`BAD_NOT_READABLE`
- 数值标量之间互相兼容并按节点既有类型强制转换（Int→Double 等）。

## 订阅与监控项参数

- CreateSubscription 参数：
  - `requestedPublishingIntervalMs`：发布周期，不小于 `opcua.min-sampling-interval-ms`。
  - `requestedLifetimeMs`：订阅寿命（<=0 时取发布周期 ×1000）。
  - `notificationRetentionWindow`：通知保留窗口（<=0 时取发布周期 ×10）。
- CreateMonitoredItems 每个监控项独立配置：
  - `samplingIntervalMs`：采样间隔；仅在值或状态变化时入队。
  - `queueSize`：有界队列大小（1..`opcua.max-notification-queue-depth`）。
  - `discardPolicy`：`DISCARD_OLDEST` 或 `DISCARD_NEWEST`；溢出时在最近通知上置 overflow。
- 每个订阅使用**单线程调度器**串行完成采样与发布：
  发布序列号在订阅内单调递增，即使采样周期与发布周期错位，也不会乱序或丢号。
- Publish 为长轮询语义：未确认通知在保留窗口内保留；
  正常 Publish 只投递"尚未正常投递过"的新通知，已投递的通知只能通过 Republish 按序号取回。
- Republish：保留窗口内按序列号返回原通知；窗口外或不存在返回
  `BAD_MESSAGE_NOT_AVAILABLE`，过期通知由 reaper 回收。
- Unpublish：取消该订阅上尚未完成的待发布请求；DeleteSubscription / 关会话 / 关通道
  会释放调度线程、监控项队列、保留通知与待发布 future。

## 多会话与资源上限

- 不同会话的订阅、监控项、待发布队列与调度线程彼此独立；
  一个会话的慢消费只阻塞它自己的长轮询，不影响其他会话的通知投递。
- 上限（均可通过 `opcua.*` 配置）：
  - 会话数：`opcua.max-sessions`，超限 `BAD_TOO_MANY_SESSIONS`。
  - 每会话订阅数：`opcua.max-subscriptions-per-session`，超限
    `BAD_NOTIFICATION_QUEUE_LIMIT`。
  - 每订阅监控项数：`opcua.max-monitored-items-per-subscription`，超限
    `BAD_TOO_MANY_MONITORED_ITEMS`。
  - 监控项队列/通知深度：`opcua.max-notification-queue-depth`，超限
    `BAD_NOTIFICATION_QUEUE_LIMIT`；非法队列大小
    `BAD_MONITORED_ITEM_QUEUE_SIZE_INVALID`。

## 主要状态码

| 名称 | 含义 |
| --- | --- |
| `GOOD` | 成功 |
| `BAD_SECURE_CHANNEL_ID_TOKEN_INVALID` | 通道不存在或已关闭 |
| `BAD_SEQUENCE_NUMBER_INVALID` | 通道请求序列不连续 |
| `BAD_SESSION_ID_INVALID` | 会话不存在/跨通道使用 |
| `BAD_SESSION_CLOSED` | 会话已关闭或已失效 |
| `BAD_SESSION_NOT_ACTIVATED` | 会话未激活 |
| `BAD_TOO_MANY_SESSIONS` | 会话数超限 |
| `BAD_IDENTITY_TOKEN_REJECTED` | 凭据被拒（不区分原因） |
| `BAD_NODE_ID_UNKNOWN` | 节点不存在 |
| `BAD_NO_CONTINUATION_POINT` | 续读点无效/不归属 |
| `BAD_CONTINUATION_POINT_LIMIT` | 续读点数量超限 |
| `BAD_TYPE_MISMATCH` | 写入值类型不匹配 |
| `BAD_NOT_READABLE` / `BAD_NOT_WRITABLE` | 读写权限不足 |
| `BAD_SUBSCRIPTION_ID_INVALID` | 订阅不存在/不归属/已释放 |
| `BAD_MONITORED_ITEM_ID_INVALID` | 监控项不存在 |
| `BAD_TOO_MANY_MONITORED_ITEMS` | 监控项数量超限 |
| `BAD_MONITORED_ITEM_QUEUE_SIZE_INVALID` | 队列大小非法 |
| `BAD_NOTIFICATION_QUEUE_LIMIT` | 订阅/通知队列深度超限 |
| `BAD_MESSAGE_NOT_AVAILABLE` | 重发布序号不可用或超出保留窗口 |

## 配置项（application.properties）

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `opcua.port` | 0（随机端口） | 回环监听端口 |
| `opcua.default-channel-lifetime-ms` | 60000 | 通道默认寿命 |
| `opcua.default-session-timeout-ms` | 60000 | 会话默认空闲超时 |
| `opcua.reaper-interval-ms` | 100 | 超时/过期回收周期 |
| `opcua.max-sessions` | 64 | 最大会话数 |
| `opcua.max-subscriptions-per-session` | 16 | 每会话最大订阅数 |
| `opcua.max-monitored-items-per-subscription` | 64 | 每订阅最大监控项数 |
| `opcua.max-notification-queue-depth` | 1000 | 通知/队列深度上限 |
| `opcua.min-sampling-interval-ms` | 10 | 最小采样间隔 |
| `opcua.max-continuation-points` | 32 | 最大续读点数量 |

## 本地验证

```bash
mvn test
```

测试覆盖：

- `ChannelLifecycleIT` / `ChannelCleanupIT`：通道建立、独立序列、显式关闭与
  超时关闭拒绝后续请求、重连可用、通道关闭级联回收会话/订阅/监控项。
- `SessionLifecycleIT`：未激活拒绝、匿名与凭据激活、凭据拒绝可区分、空闲失效、关闭拒绝。
- `AddressSpaceIT`：层级浏览分页不漏不重、并发浏览自洽、未知节点错误、并发读写完整值、
  类型/权限/未知节点可区分错误、来源时间戳与状态码。
- `SubscriptionIT`：序列号单调、采样/发布错位不乱序、保留窗口内重发布、
  窗口外回收、Unpublish/DeleteSubscription 释放、监控项与订阅上限错误码。
- `ConcurrencyAndCleanupIT`：慢消费不阻塞其他会话、多会话独立投递、
  关会话资源回收、连接内重发布。

### 从发布到重发布的最小流程

1. OpenSecureChannel → CreateSession → ActivateSession
2. CreateSubscription（发布周期 40ms，保留窗口 60000ms）
3. CreateMonitoredItems（监控 ns=0;i=2001，采样 10ms，队列 50）
4. 客户端发起 Publish 长轮询
5. Write 修改节点值；等待 Publish 收到带单调序列号的通知
6. 用该序列号调用 Republish，在保留窗口内可取回同一条通知
7. 等待超过保留窗口后再 Republish，返回 `BAD_MESSAGE_NOT_AVAILABLE`
