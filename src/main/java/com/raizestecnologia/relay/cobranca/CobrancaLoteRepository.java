package com.raizestecnologia.relay.cobranca;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CobrancaLoteRepository extends JpaRepository<CobrancaLote, Long> {
    Optional<CobrancaLote> findByAsaasPaymentId(String asaasPaymentId);
}
