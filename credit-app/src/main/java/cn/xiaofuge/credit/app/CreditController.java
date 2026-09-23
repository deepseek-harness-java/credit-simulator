package cn.xiaofuge.credit.app;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CreditController {

    private final CreditStore store;

    public CreditController(CreditStore store) {
        this.store = store;
    }

    @GetMapping("/products")
    public Map<String, Object> products(@RequestParam(required = false) String category) {
        return Map.of("code", 0, "data", store.list(category));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<Map<String, Object>> product(@PathVariable String id) {
        try {
            return ResponseEntity.ok(Map.of("code", 0, "data", store.productOf(id)));
        } catch (IllegalArgumentException e) {
            return error(e);
        }
    }

    /** 试算（等额本息+等额本金对比）；withSchedule=1 时附完整还款计划表 */
    @GetMapping("/amortize")
    public ResponseEntity<Map<String, Object>> amortize(@RequestParam double principal,
                                                        @RequestParam double rate,
                                                        @RequestParam int months,
                                                        @RequestParam(required = false) Double monthlyIncome,
                                                        @RequestParam(defaultValue = "0") int withSchedule) {
        try {
            Map<String, Object> data = store.compare(principal, rate, months, monthlyIncome);
            if (withSchedule == 1) {
                data.put("equalPaymentSchedule", CreditStore.equalPaymentSchedule(principal, rate, months));
                data.put("equalPrincipalSchedule", CreditStore.equalPrincipalSchedule(principal, rate, months));
            }
            return ResponseEntity.ok(Map.of("code", 0, "data", data));
        } catch (IllegalArgumentException e) {
            return error(e);
        }
    }

    @GetMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommend(@RequestParam(required = false) Double monthlyIncome,
                                                         @RequestParam(required = false) String purpose,
                                                         @RequestParam(required = false) Double amount) {
        try {
            return ResponseEntity.ok(Map.of("code", 0, "purpose", purpose == null ? "" : purpose,
                    "data", store.recommend(monthlyIncome == null ? 0 : monthlyIncome, purpose, amount)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "推荐参数有误: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> error(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", 1, "message", e.getMessage()));
    }
}
