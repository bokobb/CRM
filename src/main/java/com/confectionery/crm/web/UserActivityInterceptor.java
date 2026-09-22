package com.confectionery.crm.web;

import com.confectionery.crm.service.UserActivityTracker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Отмечает {@link UserActivityTracker} на каждый запрос от РЕАЛЬНОГО пользователя — веб-сессия
 * (форма логина) или мобильный /api/** с JWT (см. JwtAuthFilter, кладёт Authentication в
 * контекст ДО того, как запрос доходит сюда — интерсептор уровня MVC срабатывает уже после
 * цепочки Spring Security). /login и статику не различаем отдельно — анонимные запросы просто
 * не помечены Authentication и не трогают трекер.
 */
@Component
@RequiredArgsConstructor
public class UserActivityInterceptor implements HandlerInterceptor {

    private final UserActivityTracker tracker;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            tracker.touch();
        }
        return true;
    }
}
