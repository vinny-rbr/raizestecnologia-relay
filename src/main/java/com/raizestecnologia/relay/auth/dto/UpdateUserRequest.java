package com.raizestecnologia.relay.auth.dto;

/**
 * Body de PUT /api/admin/users/{id}.
 */
public record UpdateUserRequest(String nome, String role, Boolean ativo, java.util.List<String> permissoes) {
}
