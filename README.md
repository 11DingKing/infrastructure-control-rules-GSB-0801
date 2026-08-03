# Infrastructure Control Rules Service

重点设施管控服务——解释"为什么封、依据哪版规则封"。

支持场景：隧道、涉水路段、学校、临时搭建物。规则从默认层一路叠加到人工强制层。

## 技术栈

- **Kotlin 1.9** + **Ktor 2.3** (Netty)
- **Exposed 0.49** SQL 框架
- **SQLite** 持久化
- **kotlinx.serialization** JSON
- **Gradle 8.7** (Kotlin DSL)

## 架构原则

1. **纯领域引擎**：`RuleEngine.evaluate()` 是无副作用纯函数，不读数据库、不发通知
2. **持久化/通知只消费结果**：Repository 和 NotificationService 在求值完成后运行，不参与判定
3. **固定冲突裁决**：`MANUAL > FACILITY > REGION > DEFAULT`；同优先级取更严格动作
4. **版本时间隔离**：只读取 `publishedAt <= evaluationTime` 的规则，历史解释不能偷看后来发布的规则
5. **快照不可变**：每次结果保留输入快照、命中规则版本、完整解释链和 SHA-256 哈希
6. **公开 Kotlin API 不使用 `!!`**，失败返回可枚举的 `ReasonCode`

## 快速开始

### 环境要求

- JDK 17+
- 无需预先安装 Gradle（使用项目自带 Wrapper）

### 构建

```bash
./gradlew build
```

### 运行测试

```bash
./gradlew test
```

测试报告位于 `build/reports/tests/test/index.html`。

### 启动服务

```bash
./gradlew run
```

服务默认监听 `http://0.0.0.0:8080`。

首次启动自动：
- 创建 SQLite 数据库 `data/infra-control.db`
- 执行迁移（facilities、rules、evaluation_results 表）
- 写入种子数据：设施 `tunnel-17` 和四层规则

### 打包可执行 JAR

```bash
./gradlew installDist
./build/install/infrastructure-control-rules/bin/infrastructure-control-rules
```

## API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/facilities` | 列出设施 |
| POST | `/api/facilities` | 创建设施 |
| GET | `/api/facilities/{id}` | 设施详情 |
| GET | `/api/rules` | 列出规则（可按 layer/facilityId/regionCode 过滤） |
| POST | `/api/rules` | 发布新版本规则（同 id+version 返回 409） |
| GET | `/api/rules/{id}` | 规则详情（可指定 version） |
| POST | `/api/evaluate` | 单次风险求值 |
| POST | `/api/evaluate/batch` | 批量风险求值 |
| GET | `/api/results` | 历史结果列表 |
| GET | `/api/results/{requestId}` | 结果详情 |
| GET | `/api/results/{requestId}/explanation` | 完整解释链 |
| GET | `/openapi.json` | OpenAPI 3.0 规范 |
| GET | `/swagger-ui` | Swagger UI |

## 种子数据

### 设施

| ID | 名称 | 类型 | 区域 |
|----|------|------|------|
| tunnel-17 | 海滨隧道 17 号 | TUNNEL | 440800 |

### 规则（四层）

| 规则 ID | 层级 | 动作 | 条件 |
|---------|------|------|------|
| default-tunnel-rainfall | DEFAULT | MONITOR | 小时降水 ≥ 50mm |
| region-440800-rain-wind | REGION | RESTRICT | 小时降水 ≥ 70mm 且风力 ≥ 6 级 |
| region-440800-water-depth | REGION | RESTRICT | 水深 ≥ 15cm |
| facility-tunnel-17-extreme-rain | FACILITY | CLOSE | 小时降水 ≥ 100mm |
| facility-tunnel-17-extreme-water | FACILITY | CLOSE | 水深 ≥ 25cm |
| manual-tunnel-17-close | MANUAL | CLOSE | 无条件（有效期至 2030-01-01） |

### 示例求值

输入：小时降水 72mm、风力 7 级、水深 18cm

```bash
curl -X POST http://localhost:8080/api/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
    "facilityId": "tunnel-17",
    "hourlyRainfallMm": 72,
    "windLevel": 7,
    "waterDepthCm": 18
  }'
```

结果：`CLOSE`（人工强制层命中，`manual-tunnel-17-close` v1）。

## 测试覆盖

表驱动测试覆盖以下场景：

- **层级优先级**：MANUAL > FACILITY > REGION > DEFAULT 的逐级覆盖
- **同优先级更严格动作**：monitor/restrict/close 中取最严格
- **重叠生效区间**：不同版本 validFrom/validTo 交接
- **恰好过期**：validTo 时刻为过期（左闭右开区间）
- **缺失输入**：规则需要的字段未提供时返回原因
- **同一版本并发发布**：唯一约束冲突抛出版本冲突
- **字节级一致性**：同一快照多次求值哈希完全相同
- **历史隔离**：evaluationTime 之前未发布的规则不可见
- **批量可重复性**：批量求值结果排序确定，两次运行哈希一致
- **纯函数性**：重复调用不修改输入和规则集合

## 项目结构

```
src/main/kotlin/com/infra/
├── Application.kt              # Ktor 启动入口
├── domain/                     # 纯领域层（无框架依赖）
│   ├── Facility.kt
│   ├── Action.kt               # Action / RuleLayer / ReasonCode
│   ├── RiskInput.kt
│   ├── RuleCondition.kt
│   ├── Rule.kt
│   └── EvaluationResult.kt
├── engine/
│   └── RuleEngine.kt           # 纯函数求值引擎
├── persistence/
│   ├── Tables.kt               # Exposed 表定义
│   ├── DatabaseFactory.kt
│   ├── MigrationRunner.kt      # 版本化迁移
│   └── Repository.kt           # 仓储实现
├── notification/
│   └── NotificationService.kt  # 只消费结果，不参与判定
├── api/
│   ├── Dtos.kt
│   └── Routes.kt
└── seed/
    └── SeedData.kt
```

## 配置

数据库路径可通过 `application.conf` 或环境变量覆盖：

```bash
JDBC_URL="jdbc:sqlite:/custom/path.db" ./gradlew run
```

端口默认 8080，可通过 `PORT` 环境变量修改。
