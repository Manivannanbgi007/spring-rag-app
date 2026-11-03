package com.example.ragapp.common;

import java.util.regex.Pattern;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controller methods declare a parameter of type Caller.
 * Spring calls this class to create it from the request headers.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String USER_HEADER = "X-User-Id";
    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_USER = "anonymous";

    private static final Pattern TENANT_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    private static final Pattern USER_PATTERN = Pattern.compile("[A-Za-z0-9_.@-]{1,100}");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String tenant = headerOrDefault(request, TENANT_HEADER, DEFAULT_TENANT);
        String user = headerOrDefault(request, USER_HEADER, DEFAULT_USER);

        if (!TENANT_PATTERN.matcher(tenant).matches()) {
            throw new InvalidRequestException("Invalid " + TENANT_HEADER + ". Use 1-50 letters, digits, '-' or '_'.");
        }
        if (!USER_PATTERN.matcher(user).matches()) {
            throw new InvalidRequestException("Invalid " + USER_HEADER + ". Use 1-100 letters, digits, '-', '_', '.' or '@'.");
        }
        return new Caller(tenant, user);
    }

    private String headerOrDefault(NativeWebRequest request, String name, String defaultValue) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
