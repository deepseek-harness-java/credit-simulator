package cn.xiaofuge.credit.app;

/** 贷款产品。 */
public class LoanProduct {
    public String id;
    public String name;
    /** 消费贷 / 教育贷 / 经营贷 */
    public String category;
    /** 年利率（%），如 3.85 */
    public double annualRate;
    /** 最短/最长可贷期限（月） */
    public int minTermMonths;
    public int maxTermMonths;
    /** 单笔额度区间（元） */
    public double minAmount;
    public double maxAmount;
    /** 适合人群描述 */
    public String suitableFor;
    /** 特色标签，逗号分隔 */
    public String tags;

    public LoanProduct() { }

    public LoanProduct(String id, String name, String category, double annualRate,
                       int minTermMonths, int maxTermMonths, double minAmount, double maxAmount,
                       String suitableFor, String tags) {
        this.id = id; this.name = name; this.category = category; this.annualRate = annualRate;
        this.minTermMonths = minTermMonths; this.maxTermMonths = maxTermMonths;
        this.minAmount = minAmount; this.maxAmount = maxAmount;
        this.suitableFor = suitableFor; this.tags = tags;
    }
}
