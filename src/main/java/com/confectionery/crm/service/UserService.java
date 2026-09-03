package com.confectionery.crm.service;

import com.confectionery.crm.domain.Role;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + id));
    }

    /** Роль пользователя по логину — для фильтрации уведомлений по роли текущего сотрудника. */
    public Role roleOf(String username) {
        return userRepository.findByUsername(username).map(User::getRole).orElse(null);
    }

    public User create(String username, String rawPassword, String fullName, Role role) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Логин уже занят: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName);
        user.setRole(role);
        return userRepository.save(user);
    }

    public User update(Long id, String fullName, Role role, boolean enabled) {
        User user = findById(id);
        user.setFullName(fullName);
        user.setRole(role);
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    public void resetPassword(Long id, String newRawPassword) {
        User user = findById(id);
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        userRepository.save(user);
    }
}
