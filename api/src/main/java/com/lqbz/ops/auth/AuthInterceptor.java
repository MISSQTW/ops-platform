package com.lqbz.ops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessions;

    private final ObjectMapper objectMapper;

    private final boolean enabled;

    public AuthInterceptor(SessionService sessions, ObjectMapper objectMapper, @Value("${ops.auth.enabled:true}") boolean enabled) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled || sessions.isValid(AuthController.extractToken(request.getHeader("Authorization"))))
            return true;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), com.lqbz.ops.compat.Java8.map("message", "登录已失效，请重新登录", "errors", com.lqbz.ops.compat.Java8.list()));
        return false;
    }
}
