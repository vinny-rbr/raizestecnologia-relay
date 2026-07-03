package com.raizestecnologia.relay.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Helper estatico para obter o usuario autenticado do request atual.
 * Usado pelo RelayController para autorizar o X-Empresa.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** RelayPrincipal do SecurityContext, ou null se nao autenticado. */
    public static RelayPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof RelayPrincipal p) {
            return p;
        }
        return null;
    }
}
