package com.confectionery.crm.config;

import com.confectionery.crm.security.JwtAuthFilter;
import com.confectionery.crm.security.JwtService;
import com.confectionery.crm.security.AdminLoginLockFilter;
import com.confectionery.crm.security.LoginAttemptService;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Фильтр-чейны: /api/** — stateless JWT (мобильное приложение И веб-сборка Flutter, см.
 * /app/** ниже — та же авторизация, ApiClient), /product-images/** — публичная статика со
 * своим Cache-Control (см. ниже, почему отдельным чейном), /app/** — статика веб-сборки
 * Flutter (см. WebAppConfig) — сама по себе публичная (index.html/JS/иконки), авторизация
 * происходит уже ВНУТРИ приложения через тот же /api/auth/login, что и у мобильного, всё
 * остальное: /admin/** — админка (сессионный логин через форму, теми же учётками/ролями, что и у
 * мобильного приложения, см. CrmUserDetailsService — общий для обоих), публичные страницы сайта. H2-консоль
 * оставлена открытой сознательно — это dev-инструмент, не часть бизнес-функциональности.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Value("${crm.remember-me.secret}")
    private String rememberMeSecret;

    @Value("${crm.remember-me.validity-seconds}")
    private int rememberMeValiditySeconds;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/whatsapp/incoming").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> writeApiError(res, HttpServletResponse.SC_UNAUTHORIZED, "Требуется авторизация"))
                        .accessDeniedHandler((req, res, ex) -> writeApiError(res, HttpServletResponse.SC_FORBIDDEN, "Недостаточно прав")))
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Отдельным чейном (не просто permitAll в webFilterChain), потому что Spring Security по
     * умолчанию ставит на КАЖДЫЙ ответ заголовки против кеширования ("Cache-Control:
     * no-store" и т.п.) — это правильно для HTML/API с чувствительными данными, но ломает
     * HTTP-кеширование фото товаров, из-за которого мобильное приложение перекачивало бы
     * их заново при каждом запуске вместо переиспользования сохранённых на диске (см.
     * UploadConfig.setCacheControl). .cacheControl().disable() убирает только эти заголовки,
     * не саму защиту (доступ всё равно только на чтение статики из заранее известной папки).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain productImagesFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/product-images/**", "/purchase-images/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.cacheControl(cache -> cache.disable()));
        return http.build();
    }

    /**
     * Публичная витрина для клиентов (см. web.ShopController) — без логина, свой чейн по
     * тому же образцу, что и productImagesFilterChain, чтобы не трогать anyRequest().authenticated()
     * в основном webFilterChain и не плодить исключения в его permitAll()-списке.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain shopFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/shop/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Статика веб-сборки Flutter (index.html/main.dart.js/иконки и т.п., см. WebAppConfig) —
     * публичная, как и /product-images: сама страница ничего секретного не содержит, вход
     * происходит внутри неё через /api/auth/login (JWT), а не через сессию этого чейна.
     */
    @Bean
    @Order(4)
    public SecurityFilterChain webAppFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/app/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Админка (/admin/**, см. пакет admin): вход формой /admin/login теми же учётками и ролями, что
     * у приложения (CrmUserDetailsService, пароли BCrypt), CSRF включён, «Запомнить меня» — токены
     * в таблице persistent_logins, после пяти неудачных попыток подряд — минута паузы
     * (LoginAttemptService). Права по разделам — AdminAccessInterceptor.
     */
    @Bean
    @Order(5)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, LoginAttemptService attempts,
                                                PersistentTokenRepository tokenRepository) throws Exception {
        http.securityMatcher("/admin/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login", "/admin/assets/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .successHandler((req, res, a) -> {
                            attempts.succeeded(a.getName());
                            req.getSession().removeAttribute("ADMIN_LOGIN_USER");
                            var saved = new org.springframework.security.web.savedrequest.HttpSessionRequestCache().getRequest(req, res);
                            res.sendRedirect(saved != null && saved.getRedirectUrl().contains("/admin/")
                                    ? saved.getRedirectUrl() : req.getContextPath() + "/admin/dashboard");
                        })
                        .failureHandler((req, res, ex) -> {
                            String username = req.getParameter("username");
                            attempts.failed(username);
                            req.getSession().setAttribute("ADMIN_LOGIN_USER", username);
                            long left = attempts.secondsLeft(username);
                            res.sendRedirect(req.getContextPath() + "/admin/login?" + (left > 0 ? "locked=" + left : "error"));
                        })
                        .permitAll())
                .rememberMe(rm -> rm
                        .key(rememberMeSecret)
                        .rememberMeParameter("remember-me")
                        .tokenRepository(tokenRepository)
                        .tokenValiditySeconds(rememberMeValiditySeconds)
                        .userDetailsService(userDetailsService))
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout")
                        .permitAll())
                .addFilterBefore(new AdminLoginLockFilter(attempts), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Токены «Запомнить меня» админки — в БД (таблица создаётся при старте, если её нет). */
    @Bean
    public PersistentTokenRepository persistentTokenRepository(javax.sql.DataSource dataSource) {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists persistent_logins (username varchar(64) not null, "
                + "series varchar(64) primary key, token varchar(64) not null, last_used timestamp not null)");
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    /**
     * Всё остальное: публичные страницы (реклама "/", контакты, вакансии, статика). Старой
     * веб-админки больше нет — любой другой адрес требует входа и ведёт на /admin/login.
     */
    @Bean
    @Order(6)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/promo/**").permitAll()
                        .requestMatchers("/contacts", "/vacancies", "/vacancies/reply").permitAll()
                        .requestMatchers("/login", "/images/**", "/h2-console/**", "/firebase-messaging-sw.js", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        (req, res, ex) -> res.sendRedirect(req.getContextPath() + "/admin/login")))
                .headers(headers -> headers.frameOptions(frame -> frame.disable())); // нужно для /h2-console
        return http.build();
    }

    private void writeApiError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
