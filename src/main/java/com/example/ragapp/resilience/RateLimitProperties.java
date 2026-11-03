package com.example.ragapp.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the "app.rate-limit" section of application.yml. 0 switches a limit off.
 *
 * @param userPerMinute          questions per minute for one user
 * @param tenantPerMinute        questions per minute for all users of one tenant together
 * @param uploadsPerHourPerTenant document uploads per hour for one tenant
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(int userPerMinute, int tenantPerMinute, int uploadsPerHourPerTenant) {
}
