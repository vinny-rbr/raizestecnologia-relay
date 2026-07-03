package com.raizestecnologia.relay.auth.dto;

/**
 * Body de POST /api/admin/users/{id}/empresas.
 */
public record EmpresaRequest(String cnpj) {
}
