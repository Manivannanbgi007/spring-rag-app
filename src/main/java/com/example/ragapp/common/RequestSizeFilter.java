package com.example.ragapp.common;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized JSON requests before they are read.
 *
 * File uploads have their own limit (spring.servlet.multipart.max-file-size). A question is at most
 * 1000 characters, so a JSON body of more than a few kilobytes is never legitimate. Without this check
 * a client could send a huge body and make the server read and parse all of it.
 *
 * It looks at the Content-Length header, which browsers and normal clients always send for JSON.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeFilter extends OncePerRequestFilter {

    static final long MAX_JSON_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean isJson = request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE);

        if (isJson && request.getContentLengthLong() > MAX_JSON_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":413,\"title\":\"Content Too Large\","
                    + "\"detail\":\"The request is too large (maximum " + (MAX_JSON_BYTES / 1024) + " KB).\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
