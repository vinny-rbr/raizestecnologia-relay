package com.raizestecnologia.relay.revenda;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RevendaRepository extends JpaRepository<Revenda, Long> {
    Optional<Revenda> findByEmail(String email);
    Optional<Revenda> findByCodigo(String codigo);
    boolean existsByEmail(String email);
    boolean existsByCodigo(String codigo);
}
