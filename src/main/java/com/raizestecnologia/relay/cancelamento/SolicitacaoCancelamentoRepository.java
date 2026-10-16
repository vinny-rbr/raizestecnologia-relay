package com.raizestecnologia.relay.cancelamento;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SolicitacaoCancelamentoRepository extends JpaRepository<SolicitacaoCancelamento, String> {

    List<SolicitacaoCancelamento> findByLojaInAndStatusAndCriadoEmAfterOrderByCriadoEmDesc(Collection<String> lojas, String status, Instant depois);

    List<SolicitacaoCancelamento> findTop30ByLojaInOrderByCriadoEmDesc(Collection<String> lojas);
}
