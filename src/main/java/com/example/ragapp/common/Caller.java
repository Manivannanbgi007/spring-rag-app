package com.example.ragapp.common;

/**
 * Who is calling: which tenant (company) and which user.
 *
 * There is no login yet, so both come from request headers (X-Tenant-Id and X-User-Id).
 * That is NOT secure: anyone can send any value. When real authentication is added later
 * (for example Spring Security), only CallerArgumentResolver changes: it will read the
 * logged-in user instead of the headers. The rest of the code already uses Caller.
 */
public record Caller(String tenantId, String userId) {
}
