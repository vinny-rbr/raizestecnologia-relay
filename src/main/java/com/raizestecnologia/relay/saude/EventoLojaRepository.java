package com.raizestecnologia.relay.saude;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface EventoLojaRepository extends JpaRepository<EventoLoja, Long> {

    List<EventoLoja> findByOrderByCriadoEmDesc(Pageable p);

    List<EventoLoja> findByLojaOrderByCriadoEmDesc(String loja, Pageable p);

    boolean existsByLojaAndChaveAndPushTrueAndCriadoEmAfter(String loja, String chave, Instant depois);

    java.util.Optional<EventoLoja> findTopByLojaAndChaveAndResolvendoTrueOrderByResolvendoEmDesc(String loja, String chave);

    java.util.Optional<EventoLoja> findTopByLojaAndChaveAndNivelOrderByCriadoEmDesc(String loja, String chave, String nivel);

    /** Último evento desse problema (qualquer nível). */
    java.util.Optional<EventoLoja> findTopByLojaAndChaveOrderByCriadoEmDesc(String loja, String chave);

    /** Já avisou (push) esse problema depois do instante? */
    boolean existsByLojaAndChaveAndPushTrueAndNivelNotAndCriadoEmAfter(String loja, String chave, String nivel, Instant depois);

    /** Eventos da janela, pra montar pendentes e o relatório. */
    List<EventoLoja> findByCriadoEmAfterOrderByCriadoEmAsc(Instant depois);

    List<EventoLoja> findByLojaInOrderByCriadoEmDesc(java.util.Collection<String> lojas, org.springframework.data.domain.Pageable p);
}
