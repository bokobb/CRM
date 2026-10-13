package com.confectionery.crm.service;

import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Кто сейчас работает: сотрудник, вошедший в админку (сессия) или в приложение (JWT), — из
 * SecurityContext. Пишется в историю заказа и в «кто менял» рецептуры. Вне запроса сотрудника
 * (покупатель на /shop, фоновые задачи, импорт из 1С) пользователя нет — тогда displayName()
 * возвращает null, и вызывающий подставляет свою подпись («Покупатель на сайте», «1С»).
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public Optional<String> username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(auth.getName());
    }

    public Optional<User> user() {
        return username().flatMap(userRepository::findByUsername);
    }

    /** ФИО сотрудника; null — действие не от сотрудника. */
    public String displayName() {
        return user().map(User::getFullName).orElse(null);
    }

    public Optional<Role> role() {
        return user().map(User::getRole);
    }

    public boolean hasRole(Role... roles) {
        Optional<Role> role = role();
        if (role.isEmpty()) return false;
        for (Role r : roles) {
            if (r == role.get()) return true;
        }
        return false;
    }
}
