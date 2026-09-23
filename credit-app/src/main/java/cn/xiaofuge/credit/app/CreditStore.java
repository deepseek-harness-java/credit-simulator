package cn.xiaofuge.credit.app;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预置贷款产品库（4 款，利率与额度参考 2026 年市场真实区间）。
 * 试算全部实时计算，等额本息/等额本金公式准确，保证算术一致。
 */
@Component
public class CreditStore {

    public final List<LoanProduct> products = new ArrayList<>();

    public CreditStore() {
        products.add(new LoanProduct("p201", "金穗享花·消费贷", "消费贷", 3.85,
                6, 36, 1000, 200000,
                "有稳定工资流水的上班族，装修/家电/旅游等大额消费", "随借随还,线上审批,当日放款"));
        products.add(new LoanProduct("p202", "启程计划·教育贷", "教育贷", 3.20,
                12, 60, 5000, 300000,
                "备考证书/学历提升/职业培训人群，凭录取或报名材料申请", "在校期间贴息,宽限期6个月"));
        products.add(new LoanProduct("p203", "兴生意·经营贷", "经营贷", 4.35,
                12, 60, 50000, 1000000,
                "个体工商户/小微企业主，凭营业执照与经营流水申请", "额度高,先息后本可选,随借随还"));
        products.add(new LoanProduct("p204", "轻快借·小额消费贷", "消费贷", 5.60,
                3, 24, 500, 50000,
                "急需小额周转（医疗/应急），征信良好即可申请", "极速放款,门槛低,期限短"));
    }

    public List<LoanProduct> list(String category) {
        List<LoanProduct> result = new ArrayList<>();
        for (LoanProduct p : products) {
            if (category != null && !category.isBlank() && !p.category.equals(category.trim())) continue;
            result.add(p);
        }
        return result;
    }

    public LoanProduct productOf(String id) {
        return products.stream().filter(p -> p.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("产品不存在: " + id));
    }

    // ---- 试算核心 ----

    /** 月利率 = 年利率/12（内部精确计算，结果四舍五入到分） */
    private static double monthlyRate(double annualRatePct) {
        return annualRatePct / 100.0 / 12.0;
    }

    /**
     * 等额本息：每月还款额固定。
     * M = P * r * (1+r)^n / ((1+r)^n - 1)
     */
    public static double equalPaymentMonthly(double principal, double annualRatePct, int months) {
        double r = monthlyRate(annualRatePct);
        if (r == 0) return round2(principal / months);
        double factor = Math.pow(1 + r, months);
        return round2(principal * r * factor / (factor - 1));
    }

    /**
     * 等额本金：每月归还固定本金 + 剩余本金当期利息，月供逐月递减。
     * 首月 = P/n + P*r；末月 = P/n + (P/n)*r
     */
    public static double equalPrincipalFirst(double principal, double annualRatePct, int months) {
        double r = monthlyRate(annualRatePct);
        return round2(principal / months + principal * r);
    }

    public static double equalPrincipalLast(double principal, double annualRatePct, int months) {
        double r = monthlyRate(annualRatePct);
        double perPrincipal = principal / months;
        return round2(perPrincipal + perPrincipal * r);
    }

    /** 等额本息还款计划表（含每期本金/利息/剩余本金，算术自洽） */
    public static List<Map<String, Object>> equalPaymentSchedule(double principal, double annualRatePct, int months) {
        List<Map<String, Object>> rows = new ArrayList<>();
        double r = monthlyRate(annualRatePct);
        double payment = equalPaymentMonthly(principal, annualRatePct, months);
        double remaining = principal;
        for (int i = 1; i <= months; i++) {
            double interest = round2(remaining * r);
            double principalPart = round2(payment - interest);
            if (i == months) principalPart = round2(remaining); // 末期对齐，消除累计舍入
            remaining = round2(remaining - principalPart);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", i);
            row.put("payment", payment);
            row.put("principal", principalPart);
            row.put("interest", interest);
            row.put("remaining", Math.max(0, remaining));
            rows.add(row);
        }
        return rows;
    }

    /** 等额本金还款计划表 */
    public static List<Map<String, Object>> equalPrincipalSchedule(double principal, double annualRatePct, int months) {
        List<Map<String, Object>> rows = new ArrayList<>();
        double r = monthlyRate(annualRatePct);
        double perPrincipal = round2(principal / months);
        double remaining = principal;
        for (int i = 1; i <= months; i++) {
            double principalPart = (i == months) ? round2(remaining) : perPrincipal;
            double interest = round2(remaining * r);
            double payment = round2(principalPart + interest);
            remaining = round2(remaining - principalPart);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", i);
            row.put("payment", payment);
            row.put("principal", principalPart);
            row.put("interest", interest);
            row.put("remaining", Math.max(0, remaining));
            rows.add(row);
        }
        return rows;
    }

    /** 两种还款方式对比 + 按月收入给承受力评估 */
    public Map<String, Object> compare(double principal, double annualRatePct, int months, Double monthlyIncome) {
        if (principal <= 0) throw new IllegalArgumentException("借款金额必须大于 0");
        if (months < 1 || months > 360) throw new IllegalArgumentException("期限需在 1~360 个月");

        List<Map<String, Object>> epSchedule = equalPaymentSchedule(principal, annualRatePct, months);
        List<Map<String, Object>> prSchedule = equalPrincipalSchedule(principal, annualRatePct, months);
        double epTotal = round2(epSchedule.stream().mapToDouble(m -> (Double) m.get("payment")).sum());
        double prTotal = round2(prSchedule.stream().mapToDouble(m -> (Double) m.get("payment")).sum());

        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("method", "等额本息");
        ep.put("monthlyFirst", epSchedule.get(0).get("payment"));
        ep.put("monthlyLast", epSchedule.get(epSchedule.size() - 1).get("payment"));
        ep.put("totalPayment", epTotal);
        ep.put("totalInterest", round2(epTotal - principal));

        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("method", "等额本金");
        pr.put("monthlyFirst", prSchedule.get(0).get("payment"));
        pr.put("monthlyLast", prSchedule.get(prSchedule.size() - 1).get("payment"));
        pr.put("totalPayment", prTotal);
        pr.put("totalInterest", round2(prTotal - principal));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("principal", principal);
        result.put("annualRatePct", annualRatePct);
        result.put("months", months);
        result.put("equalPayment", ep);
        result.put("equalPrincipal", pr);
        result.put("interestDiff", round2(prTotal < epTotal ? epTotal - prTotal : 0));
        if (monthlyIncome != null && monthlyIncome > 0) {
            double epMonthly = (Double) ep.get("monthlyFirst");
            double prMonthlyFirst = (Double) pr.get("monthlyFirst");
            double ratio = round2(epMonthly / monthlyIncome * 100);
            Map<String, Object> afford = new LinkedHashMap<>();
            afford.put("monthlyIncome", monthlyIncome);
            afford.put("epRatioPct", ratio);
            afford.put("prFirstRatioPct", round2(prMonthlyFirst / monthlyIncome * 100));
            afford.put("verdict", ratio <= 50 ? "可承受（等额本息月供占收入 " + ratio + "%，低于 50% 安全线）"
                    : ratio <= 70 ? "偏紧（月供占收入 " + ratio + "%，建议拉长期限或减少本金）"
                    : "超出承受力（月供占收入 " + ratio + "%，超过 70%，不建议该方案）");
            result.put("affordability", afford);
        }
        return result;
    }

    /** 按收入/用途/金额推荐产品 */
    public List<Map<String, Object>> recommend(double monthlyIncome, String purpose, Double amount) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (LoanProduct p : products) {
            double score = 0;
            List<String> reasons = new ArrayList<>();
            if (purpose != null && !purpose.isBlank()) {
                String ps = purpose.trim();
                if (p.category.equals("教育贷") && (ps.contains("教育") || ps.contains("学") || ps.contains("培训") || ps.contains("考证"))) { score += 50; reasons.add("用途匹配教育场景"); }
                if (p.category.equals("经营贷") && (ps.contains("经营") || ps.contains("生意") || ps.contains("店铺") || ps.contains("周转") && ps.contains("货"))) { score += 50; reasons.add("用途匹配经营场景"); }
                if (p.category.equals("消费贷") && (ps.contains("装修") || ps.contains("家电") || ps.contains("旅游") || ps.contains("消费") || ps.contains("买"))) { score += 40; reasons.add("用途匹配消费场景"); }
                if (ps.contains("急") || ps.contains("快")) {
                    if (p.tags.contains("极速放款") || p.tags.contains("当日放款")) { score += 20; reasons.add("放款速度满足急用"); }
                }
            }
            if (amount != null && amount > 0) {
                if (amount < p.minAmount) { score -= 100; reasons.add("低于该产品起借额度 ¥" + (int) p.minAmount); }
                else if (amount > p.maxAmount) { score -= 100; reasons.add("超出该产品上限 ¥" + (int) p.maxAmount); }
                else { score += 15; reasons.add("金额在可贷区间内"); }
            }
            if (monthlyIncome > 0) {
                double safeMonthly = monthlyIncome * 0.5;
                double minMonthly = equalPaymentMonthly(Math.max(amount == null ? p.minAmount : amount, p.minAmount), p.annualRate, p.maxTermMonths);
                if (minMonthly <= safeMonthly) { score += 10; reasons.add("按最低月供测算压力可控"); }
            }
            score += (6.0 - p.annualRate) * 5; // 利率越低分越高
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", p.id);
            item.put("name", p.name);
            item.put("category", p.category);
            item.put("annualRatePct", p.annualRate);
            item.put("amountRange", "¥" + (int) p.minAmount + " ~ ¥" + (int) p.maxAmount);
            item.put("termRange", p.minTermMonths + "~" + p.maxTermMonths + " 期");
            item.put("suitableFor", p.suitableFor);
            item.put("tags", p.tags);
            item.put("score", Math.round(score * 10) / 10.0);
            item.put("reasons", reasons);
            ranked.add(item);
        }
        ranked.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
        return ranked;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
