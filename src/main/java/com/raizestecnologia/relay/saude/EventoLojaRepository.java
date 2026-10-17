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
}
