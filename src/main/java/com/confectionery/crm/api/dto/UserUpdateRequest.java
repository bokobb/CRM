package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param newPassword необязателен — пусто/null означает "не менять пароль". Логин тоже можно
 *                     сменить (уникальность проверяется в UserService.update), а вот роль
 *                     менять может только ADMIN — см. UserApiController.update.
 */
public record UserUpdateRequest(
        @NotBlank String username,
        @NotBlank String fullName,
        @NotNull Role role,
        boolean enabled,
        String newPassword
) {
}
