# Infrastructure Control Rules（重点设施管控服务）

能回答“**为什么封、依据哪版规则封**”的管控服务。场景覆盖隧道（TUNNEL）、涉水路段（FLOOD_ROAD）、学校（SCHOOL）、临时搭建物（TEMP_STRUCTURE）；规则从默认层一路叠加到人工强制层。

技术栈：Kotlin 2.1 · Ktor 3 · Exposed · SQLite（Gradle Kotlin DSL，JDK 17）。

## 核心语义

| 主题 | 约定 |
| --- | --- |
| 冲突裁决 | 固定顺序：人工强制(MANUAL) > 设施专用(FACILITY) > 区域(REGION) > 默认(DEFAULT)；同优先级采用更严格动作：CLOSE > RESTRICT > MONITOR；再相同按 ruleId 字典序确定性裁决 |
| 生效窗口 | `effectiveFrom <= now < effectiveTo`；`now == effectiveTo` 视为已过期；`effectiveTo = null` 永久有效 |
| 版本链 | 同 `ruleId` 为一条链，求值取 `publishedAt <= asOf` 的最高版本；`(ruleId, version)` 唯一，并发重复发布只有一个赢家（其余 `DUPLICATE_VERSION`） |
| 历史隔离 | `asOf` 之前发布的版本才可见；历史解释不会偷看后来发布的规则 |
| 纯求值 | `RuleEngine.evaluate` 是无副作用纯函数（不读时钟/DB/网络）；持久化与通知只消费求值结果，不参与判定 |
| 确定性 | 相同（设施, 规则集, 快照, now, asOf）→ 字节级一致的 canonical JSON 与 SHA-256 `contentHash` |
| 原因码 | 求值/解释用 `EvalCode`，发布用 `PublishCode`，全部可枚举；公开 API 无 `!!` |
| 结果留痕 | 每次结果持久化命中规则版本、输入快照、完整解释链（`evaluations.canonical_json`） |
| 批量基准 | 批量求值逐项走与单次完全相同的服务路径（不绕过领域层），落库可复现的逐项指纹 |

## 构建 / 测试 / 启动

```bash
gradle build          # 编译 + 全部测试（表驱动 + 集成 + API，25 个用例）
gradle test           # 只跑测试，报告在 build/reports/tests/test/index.html
gradle run            # 启动服务，默认 http://localhost:8080
```

环境变量：

- `DB_FILE`：SQLite 文件路径（默认 `data/control.db`）
- `PORT`：监听端口（默认 `8080`）
- `SEED_DEMO`：`false` 关闭演示种子（默认开启，库为空时写入 tunnel-17 与四层规则）

启动后可访问 `GET /openapi.yaml` 获取 OpenAPI 3 规范。

## 演示种子（`SEED_DEMO` 默认开启）

- 设施：`tunnel-17`（TUNNEL，区域 `440800`）
- 风险输入：小时降水 72mm、风力 7 级、水深 18cm（id=1）
- 四层规则（动作覆盖 MONITOR / RESTRICT / CLOSE）：

| 层 | ruleId | 条件 | 动作 | 窗口 |
| --- | --- | --- | --- | --- |
| DEFAULT | `default-heavy-rain` | 降水 ≥ 50mm | MONITOR | 永久 |
| REGION | `region-440800-storm` | 降水 ≥ 60mm | RESTRICT | 永久 |
| FACILITY | `facility-tunnel-17-depth` | 水深 ≥ 15cm | CLOSE | 永久 |
| MANUAL | `manual-tunnel-17-typhoon` | 风力 ≥ 8 级 | CLOSE | 2026-07-01 ~ 2026-12-31T16:00Z（带过期） |

对种子输入求值：人工规则风力未达（7 < 8），设施层 `CLOSE` 胜出；把 `now` 传到 2027 年可看到人工规则 `EXPIRED`。

## API 速览（时间均为 epoch millis）

```bash
# 求值（可传 now / asOf 控制生效窗口与历史可见性）
curl -X POST localhost:8080/api/evaluations -H 'Content-Type: application/json' \
  -d '{"facilityId":"tunnel-17","inputId":1}'

# 结果详情与解释链
curl localhost:8080/api/evaluations/{id}

# 发布规则版本（失败返回可枚举 PublishCode；409=重复/回滚，422=校验失败）
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' \
  -d '{"ruleId":"manual-tunnel-17-ice","version":1,"tier":"MANUAL","scopeKey":"tunnel-17",
       "action":"CLOSE","precipitationMmAtLeast":0,"effectiveFrom":0,"effectiveTo":1798761600000}'

# 批量求值（重跑同一请求，contentHashes 序列可复现）
curl -X POST localhost:8080/api/batches -H 'Content-Type: application/json' \
  -d '{"items":[{"facilityId":"tunnel-17","inputId":1},{"facilityId":"tunnel-17","inputId":1,"now":1798761600000}]}'

# 通知记录（通知只消费求值结果）
curl 'localhost:8080/api/notifications?facilityId=tunnel-17'
```

其余端点：`POST/GET /api/facilities`、`GET /api/facilities/{id}/evaluations`、`GET /api/rules/{ruleId}/versions`、`POST /api/risk-inputs`、`GET /api/batches/{id}`。完整定义见 [openapi.yaml](src/main/resources/openapi.yaml)。

## 代码结构

```
src/main/kotlin/app/control/
├── domain/            # 纯领域层（无副作用）
│   ├── Model.kt       #   Facility / Rule / RuleCondition / RiskSnapshot
│   ├── Codes.kt       #   EvalCode / PublishCode 可枚举原因码
│   ├── Evaluation.kt  #   EvaluationResult / 解释链 / Decision
│   ├── RuleEngine.kt  #   纯求值函数：版本选择→作用域→窗口→条件→冲突裁决
│   └── Canonical.kt   #   规范化 JSON + SHA-256 指纹（字节级确定性）
├── db/                # Exposed 表定义 + 有序 SQL 迁移（schema_migrations 追踪）
├── services/          # 编排层：发布校验、求值→持久化→通知、批量基准
├── http/              # Ktor 路由 + DTO（错误一律 {code,message}）
├── DemoSeed.kt        # tunnel-17 四层规则种子
└── Main.kt            # 启动入口

src/test/kotlin/app/control/
├── RuleEngineTest.kt           # 表驱动：16 个求值用例 + 字节级一致性
├── ServiceIntegrationTest.kt   # 并发同版本发布 / 历史隔离 / 恰好过期 / 批量基准 / 通知
└── ApiTest.kt                  # HTTP 全链路端到端
```

## 测试覆盖的关键场景

- 四层规则排列组合：全命中人工胜出、人工阈值未达、区域/设施/默认逐层回落
- 重叠生效区间内同层规则取更严格动作；同优先级同动作按 ruleId 确定性裁决
- 恰好过期（`now == effectiveTo` → `EXPIRED`）与恰好生效（`now == effectiveFrom`）
- 缺失输入：需要该指标的规则得到 `MISSING_*` 原因码并被跳过
- 同一版本并发发布：8 线程并发，唯一约束保证只有一个赢家
- 历史解释隔离：`asOf` 之后发布的版本对历史求值不可见，重放指纹不变
- 同一快照纯求值字节级一致，且与规则传入顺序无关
- 批量求值基准可复现，逐项通知留痕
