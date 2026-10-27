package com.raizestecnologia.relay.suporte;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChamadoRepository extends JpaRepository<Chamado, Long> {

    /** chamado em aberto (ABERTO ou ATENDIMENTO) do grupo */
    Optional<Chamado> findFirstByGrupoIdAndStatusInOrderByAbertoEmDesc(String grupoId, Collection<String> status);

    List<Chamado> findByStatusInOrderByAbertoEmAsc(Collection<String> status);

    List<Chamado> findByAbertoEmBetweenOrderByAbertoEmDesc(Instant de, Instant ate);

    List<Chamado> findByFinalizadoEmBetween(Instant de, Instant ate);
}
