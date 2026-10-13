package com.confectionery.crm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Не пускает POST /admin/login, пока у логина идёт пауза после пяти неудачных попыток (LoginAttemptService). */
public class AdminLoginLockFilter extends OncePerRequestFilter {

    private final LoginAttemptService attempts;

    public AdminLoginLockFilter(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/admin/login".equals(request.getServletPath())) {
            String username = request.getParameter("username");
            long left = attempts.secondsLeft(username);
            if (left > 0) {
                request.getSession().setAttribute("ADMIN_LOGIN_USER", username);
                response.sendRedirect(request.getContextPath() + "/admin/login?locked=" + left);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
