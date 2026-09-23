package cn.xiaofuge.credit.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 慧借计划插件：把 credit-app 的 REST API 注册为 DSH Agent 工具。
 * 全部为只读工具（产品查询/试算/推荐），不做任何写操作。
 */
public class CreditPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "credit-loan-assistant";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public CreditPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ListProductsTool(),
                new AmortizeTool(),
                new RecommendTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("credit-capabilities", 20, """
                ## 慧借计划·贷款试算助手
                - 用户问"有哪些产品/能贷多少/利率" → list_products（可按 category=消费贷/教育贷/经营贷 过滤）
                - 用户想算月供/利息/两种还款方式对比/还款计划 → amortize（principal、rate、months 必填；用户提供月收入则传 monthlyIncome 做承受力评估；用户要计划表传 withSchedule=1）
                - 用户没给全参数（金额/利率/期限）→ 先根据他描述的场景用 list_products 查产品拿利率区间，再复述"按 X 产品利率 Y%、借 Z 元、N 期"让他确认后再试算
                - 用户说"帮我推荐/我该贷哪个" → recommend（monthlyIncome、purpose、amount 有什么传什么）
                - 涉及金额一律 ¥ + 千分位（如 ¥12,345.60），利率用年化 X.XX%
                - 所有数字必须来自工具返回（等额本息/等额本金为精确公式计算），禁止心算或编造
                - 解释等额本息（月供固定、总利息略高）与等额本金（月供递减、前期压力大、总利息少）差异要通俗
                - 承受力评估：月供占收入 ≤50% 可承受、50%~70% 偏紧、>70% 不建议，务必给出调整建议（拉长期限/减少本金）
                - 本助手仅做模拟试算与知识说明，不构成真实放贷承诺
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: credit tool call.");
            }
            return null;
        });
    }

    // ---- HTTP 辅助 ----

    private String get(String pathWithQuery, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + pathWithQuery)).GET().build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("CREDIT_APP_BASE_URL", "http://127.0.0.1:18084")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return failJson(resp.statusCode(), resp.body());
            return resp.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String appendParam(String path, String name, String value) {
        if (value == null || value.isBlank()) return path;
        return path + (path.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- 工具定义 ----

    private class ListProductsTool extends AbstractTool {
        @Override public String name() { return "list_products"; }
        @Override public String description() {
            return "查询在售贷款产品列表（名称/类别/年化利率/额度区间/期限区间/适合人群/标签），可按类别过滤。"
                    + "何时必须调用：用户问有什么产品、利率多少、能贷多少；试算前用户未给利率时也先用它取产品利率。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("category", stringSchema("产品类别：消费贷/教育贷/经营贷，可选"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam("/api/products", "category", str(args, "category")), args));
        }
    }

    private class AmortizeTool extends AbstractTool {
        @Override public String name() { return "amortize"; }
        @Override public String description() {
            return "贷款试算：等额本息与等额本金两种方式对比（月供/总利息/利息差），提供月收入时附承受力评估，withSchedule=1 附逐期还款计划表。"
                    + "何时必须调用：用户要算月供、比利息、看还款计划。所有金额数字以此工具返回为准，禁止自行计算。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("principal", stringSchema("借款本金（元），大于 0"))
                    .prop("rate", stringSchema("年化利率（百分数，如 3.85 表示 3.85%）"))
                    .prop("months", stringSchema("期限（月），1~360"))
                    .prop("monthlyIncome", stringSchema("用户月收入（元），可选，提供则做承受力评估"))
                    .prop("withSchedule", stringSchema("是否返回逐期还款计划表：1=是，0/不填=否"))
                    .required("principal", "rate", "months")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String path = appendParam(appendParam(appendParam("/api/amortize",
                    "principal", str(args, "principal")),
                    "rate", str(args, "rate")),
                    "months", str(args, "months"));
            path = appendParam(path, "monthlyIncome", str(args, "monthlyIncome"));
            path = appendParam(path, "withSchedule", str(args, "withSchedule"));
            return ok(get(path, args));
        }
    }

    private class RecommendTool extends AbstractTool {
        @Override public String name() { return "recommend"; }
        @Override public String description() {
            return "按用户情况推荐贷款产品：提供月收入、借款用途（如 装修/培训/生意周转/应急）、期望金额中的任意几项，返回带评分与推荐理由的排序列表。"
                    + "何时必须调用：用户表达\"帮我推荐/我适合哪个/我想贷 X 用于 Y\"。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("monthlyIncome", stringSchema("月收入（元），可选"))
                    .prop("purpose", stringSchema("借款用途描述，如 装修/考证书培训/店铺进货周转/应急，可选"))
                    .prop("amount", stringSchema("期望借款金额（元），可选"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String path = appendParam(appendParam(appendParam("/api/recommend",
                    "monthlyIncome", str(args, "monthlyIncome")),
                    "purpose", str(args, "purpose")),
                    "amount", str(args, "amount"));
            return ok(get(path, args));
        }
    }
}
