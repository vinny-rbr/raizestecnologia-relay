package com.raizestecnologia.relay.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByLogin(String login);

    /** Login do app: aceita o e-mail ou o nome de usuario. */
    default Optional<AppUser> porEmailOuLogin(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        Optional<AppUser> u = findByEmailIgnoreCase(s.trim());
        return u.isPresent() ? u : findByLogin(AppUser.normalizarLogin(s));
    }

    /** Usuarios MASTER/DONO ativos — destinatarios dos alertas do sistema. */
    List<AppUser> findByRoleIgnoreCaseAndAtivoTrue(String role);

    /** Usuarios-master de uma revenda (logins extras do painel da revenda). */
    List<AppUser> findByRevendaId(Long revendaId);
}
