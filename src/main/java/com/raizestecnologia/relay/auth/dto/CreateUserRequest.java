package com.raizestecnologia.relay.auth.dto;

import java.util.List;

/**
 * Body de POST /api/admin/users.
 */
public record CreateUserRequest(String nome, String email, String senha, String role,
                                List<String> cnpjs, List<String> permissoes) {
}
