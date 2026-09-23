# P4 · 慧借计划（小额信贷模拟器）

DSH 场景案例：AI 贷款试算助手。Spring Boot 业务应用 + DSH Java Native 插件，等额本息/等额本金全部精确公式计算（到分），Agent 只读不写。

## 组成

| 模块 | 说明 |
|---|---|
| `credit-app` | 业务应用（端口 18084）：产品货架、试算对比、推荐、AI 面板 SSE 代理 |
| `credit-plugin` | DSH Java Native 插件（pluginId=`credit-loan-assistant`）：注册 3 个只读工具 + system prompt + PRE_TOOL_USE 审计 Hook |

## 数据设计

4 款预置产品（利率/额度参考 2026 年市场真实区间）：

| 产品 | 类别 | 年化 | 额度 | 期限 |
|---|---|---|---|---|
| 金穗享花·消费贷 | 消费贷 | 3.85% | ¥1,000~200,000 | 6~36 期 |
| 启程计划·教育贷 | 教育贷 | 3.20% | ¥5,000~300,000 | 12~60 期 |
| 兴生意·经营贷 | 经营贷 | 4.35% | ¥50,000~1,000,000 | 12~60 期 |
| 轻快借·小额消费贷 | 消费贷 | 5.60% | ¥500~50,000 | 3~24 期 |

## 试算核心

- 等额本息：`M = P·r·(1+r)^n / ((1+r)^n − 1)`，月供固定
- 等额本金：固定本金 + 剩余本金当期利息，月供递减；末期对齐消除累计舍入
- 两种方式对比（总利息差）+ 月收入承受力评估（≤50% 可承受 / 50~70% 偏紧 / >70% 不建议）
- 评分推荐：用途匹配 + 金额区间 + 收入压力 + 利率权重

## Agent 工具（全部只读）

| 工具 | 说明 |
|---|---|
| `list_products` | 产品列表，可按类别过滤 |
| `amortize` | 两种还款方式对比 + 承受力评估 + 可选逐期计划表 |
| `recommend` | 按收入/用途/金额评分推荐 |

## 启动与安装

```bash
mvn package -DskipTests
java -jar credit-app/target/credit-app-1.0.0-SNAPSHOT.jar --server.port=18084
bash install_plugin.sh $(pwd)/credit-plugin/target/credit-plugin-1.0.0-SNAPSHOT.jar credit-loan-assistant 1.0.0-SNAPSHOT credit-plugin-1.0.0-SNAPSHOT.jar 慧借计划·贷款试算助手
```

- 页面：http://127.0.0.1:18084 （金融财务设计语言信贷分支：深藏蓝 + 铜金 + 利率曲线/还款进度弧母题）
- Agent 验证：`agent_stream.sh 127.0.0.1:8090 credit-loan-assistant "借10万 3年 月入1万 月供多少？"`
- 插件→应用默认走 `http://127.0.0.1:18084`，可用环境变量 `CREDIT_APP_BASE_URL` 覆盖
