package com.llm.okf.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final String START_TIME_ATTR = "requestStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        log.info("--> {} {}{}", request.getMethod(), request.getRequestURI(), queryString(request));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long duration = System.currentTimeMillis() - (long) request.getAttribute(START_TIME_ATTR);
        log.info("<-- {} {} {} ({}ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        if (ex != null) {
            log.error("Request failed with exception", ex);
        }
    }

    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }
}
