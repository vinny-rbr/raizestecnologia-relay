package com.raizestecnologia.relay.painel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PainelProntoRepository extends JpaRepository<PainelPronto, Long> {

    Optional<PainelPronto> findByCnpjAndIdAtendimento(String cnpj, Long idAtendimento);

    /** Prontos ainda na tela (desde {@code desde}), mais novo primeiro. */
    List<PainelPronto> findByCnpjAndProntoEmAfterOrderByProntoEmDesc(String cnpj, Instant desde);

    @Modifying
    @Transactional
    @Query("DELETE FROM PainelPronto p WHERE p.prontoEm < :antes")
    int limpar(Instant antes);
}
