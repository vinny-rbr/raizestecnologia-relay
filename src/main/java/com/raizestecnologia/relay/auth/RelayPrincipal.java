package com.raizestecnologia.relay.auth;

import java.util.Set;

/**
 * Identidade do usuario autenticado no SecurityContext.
 * Os cnpjs vem do banco (nao confie apenas no token).
 */
public record RelayPrincipal(String userId, String email, String role, Set<String> cnpjs) {

    public boolean isDono() {
        return "DONO".equalsIgnoreCase(role);
    }

    public boolean podeVer(String cnpj) {
        if (isDono()) return true;
        if (cnpj == null) return false;
        return cnpjs != null && cnpjs.contains(cnpj);
    }
}
