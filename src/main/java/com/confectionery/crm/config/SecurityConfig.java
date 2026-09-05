package com.confectionery.crm.config;

import com.confectionery.crm.security.JwtAuthFilter;
import com.confectionery.crm.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Три фильтр-чейна: /api/** — stateless JWT (мобильное приложение), /product-images/** —
 * публичная статика со своим Cache-Control (см. ниже, почему отдельным чейном), всё
 * остальное (Thymeleaf-веб) — сессионный логин через форму, теми же учётками/ролями, что и у
 * мобильного приложения (см. CrmUserDetailsService — общий для обоих). H2-консоль
 * оставлена открытой сознательно — это dev-инструмент, не часть бизнес-функциональности.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;

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

    @Bean
    @Order(4)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/h2-console/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", false)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login")
                        .permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.disable())); // нужно для /h2-console
        return http.build();
    }

    private void writeApiError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
