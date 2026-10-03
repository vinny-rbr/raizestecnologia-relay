package com.raizestecnologia.relay.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Le o header Authorization: Bearer, valida o JWT e popula o SecurityContext
 * com um RelayPrincipal. Os cnpjs e o estado (ativo) sao lidos do banco a cada
 * request (nao confia apenas no token).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final AppUserRepository users;
    private final UserEmpresaRepository vinculos;
    private final com.raizestecnologia.relay.revenda.RevendaService revendas;

    public JwtAuthFilter(JwtService jwt, AppUserRepository users, UserEmpresaRepository vinculos,
                         com.raizestecnologia.relay.revenda.RevendaService revendas) {
        this.jwt = jwt;
        this.users = users;
        this.vinculos = vinculos;
        this.revendas = revendas;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwt.parse(header.substring(7).trim());
                Long userId = Long.valueOf(claims.getSubject());
                AppUser user = users.findById(userId).orElse(null);
                if (user != null && user.isAtivo() && sessaoValida(user, claims)) {
                    Set<String> cnpjs = vinculos.findByUserId(userId).stream()
                            .map(UserEmpresa::getCnpj)
                            .collect(Collectors.toSet());
                    Set<String> permissoes = new java.util.HashSet<>(user.permissoesList());
                    // Master de revenda: carrega o codigo da revenda pra enxergar as lojas dela.
                    String revendaCodigo = null;
                    if ("REVENDA".equalsIgnoreCase(user.getRole()) && user.getRevendaId() != null) {
                        revendaCodigo = revendas.porId(user.getRevendaId())
                                .map(com.raizestecnologia.relay.revenda.Revenda::getCodigo).orElse(null);
                    }
                    RelayPrincipal principal = new RelayPrincipal(
                            String.valueOf(user.getId()), user.getEmail(), user.getRole(), cnpjs, permissoes, revendaCodigo);
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                // token invalido/expirado -> segue sem autenticacao (Security decide 401/403)
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Sessao unica: quando ligada, so vale o token cujo sid bate com o sessaoId atual
     * do usuario. Se logou em outro aparelho (sessaoId mudou) ou o token nao tem sid,
     * a sessao antiga cai (nao autentica -> 401). Sem sessao unica, passa sempre.
     */
    private boolean sessaoValida(AppUser user, Claims claims) {
        if (!user.isSessaoUnica()) return true;
        Object sid = claims.get("sid");
        return sid != null && sid.equals(user.getSessaoId());
    }
}
