package com.raizestecnologia.relay.loja;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LojaRepository extends JpaRepository<Loja, String> {
    Optional<Loja> findByAsaasCustomerId(String asaasCustomerId);
}
