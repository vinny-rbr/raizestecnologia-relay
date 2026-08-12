package com.raizestecnologia.relay.auth;

import java.util.Set;

/**
 * Identidade do usuario autenticado no SecurityContext.
 * Os cnpjs e as permissoes vem do banco (nao confie apenas no token).
 */
public record RelayPrincipal(String userId, String email, String role, Set<String> cnpjs, Set<String> permissoes) {

    public boolean isDono() {
        return "DONO".equalsIgnoreCase(role);
    }

    public boolean podeVer(String cnpj) {
        if (isDono()) return true;
        if (cnpj == null) return false;
        return cnpjs != null && cnpjs.contains(cnpj);
    }

    /**
     * true se o usuario pode acessar uma rota que exige {@code requeridos} (basta ter QUALQUER um).
     * DONO e usuario sem restricao (permissoes vazias) passam sempre.
     */
    public boolean podeModulo(Set<String> requeridos) {
        if (isDono()) return true;
        if (requeridos == null || requeridos.isEmpty()) return true;   // rota livre
        if (permissoes == null || permissoes.isEmpty()) return true;   // sem restricao (legado)
        for (String m : requeridos) {
            if (permissoes.contains(m)) return true;
        }
        return false;
    }
}
