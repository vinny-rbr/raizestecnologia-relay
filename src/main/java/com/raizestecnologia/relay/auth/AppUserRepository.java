package com.raizestecnologia.relay.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);

    /** Usuarios MASTER/DONO ativos — destinatarios dos alertas do sistema. */
    List<AppUser> findByRoleIgnoreCaseAndAtivoTrue(String role);
}
