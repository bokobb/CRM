package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.UserCreateRequest;
import com.confectionery.crm.api.dto.UserDto;
import com.confectionery.crm.api.dto.UserUpdateRequest;
import com.confectionery.crm.domain.User;
import com.confectionery.crm.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class UserApiController {

    private final UserService userService;

    @GetMapping
    public List<UserDto> list() {
        return userService.findAll().stream().map(UserDto::from).toList();
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return UserDto.from(userService.findById(id));
    }

    @PostMapping
    public UserDto create(@Valid @RequestBody UserCreateRequest req) {
        return UserDto.from(userService.create(req.username(), req.password(), req.fullName(), req.role()));
    }

    /** Логин/пароль/ФИО/активность может менять ADMIN или ACCOUNTANT, а вот роль — только ADMIN. */
    @PutMapping("/{id}")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req, Authentication authentication) {
        User existing = userService.findById(id);
        boolean roleChanged = existing.getRole() != req.role();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (roleChanged && !isAdmin) {
            throw new AccessDeniedException("Роль может менять только администратор");
        }
        return UserDto.from(userService.update(id, req.username(), req.fullName(), req.role(), req.enabled(), req.newPassword()));
    }
}
