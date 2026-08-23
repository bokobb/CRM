package com.confectionery.crm.api;

import com.confectionery.crm.api.dto.PasswordResetRequest;
import com.confectionery.crm.api.dto.UserCreateRequest;
import com.confectionery.crm.api.dto.UserDto;
import com.confectionery.crm.api.dto.UserUpdateRequest;
import com.confectionery.crm.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @PutMapping("/{id}")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
        return UserDto.from(userService.update(id, req.fullName(), req.role(), req.enabled()));
    }

    @PostMapping("/{id}/reset-password")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordResetRequest req) {
        userService.resetPassword(id, req.newPassword());
    }
}
