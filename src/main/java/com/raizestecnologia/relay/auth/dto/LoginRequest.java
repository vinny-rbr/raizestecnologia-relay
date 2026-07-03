package com.raizestecnologia.relay.auth.dto;

/**
 * Body de POST /api/auth/login. Aceita "password" e tambem "senha".
 */
public record LoginRequest(String email, String password, String senha) {

    /** Senha efetiva: prioriza "password", cai para "senha". */
    public String senhaEfetiva() {
        if (password != null && !password.isBlank()) return password;
        return senha;
    }
}
