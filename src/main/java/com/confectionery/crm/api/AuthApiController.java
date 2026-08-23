package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.LoginRequest;
import com.confectionery.crm.api.dto.LoginResponse;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.repository.UserRepository;
import com.confectionery.crm.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + req.username()));
        return LoginResponse.of(jwtService.issueToken(user), user);
    }

    @GetMapping("/me")
    public LoginResponse me(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + authentication.getName()));
        return LoginResponse.of(null, user);
    }
}
