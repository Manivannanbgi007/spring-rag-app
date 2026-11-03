package com.example.ragapp.usage;

import com.example.ragapp.common.Caller;
import com.example.ragapp.common.InvalidRequestException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/usage?days=30 : what the tenant used (questions, tokens, estimated cost, speed).
 *
 * There is no login yet, so everybody who sends the tenant header can read it. When authentication is added,
 * this endpoint should be limited to administrators.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private static final int MAX_DAYS = 365;

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public UsageReport report(Caller caller, @RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidRequestException("days must be between 1 and " + MAX_DAYS + ".");
        }
        return usageService.report(caller.tenantId(), days);
    }
}
