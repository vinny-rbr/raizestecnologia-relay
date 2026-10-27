package com.raizestecnologia.relay.suporte;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuporteMensagemRepository extends JpaRepository<SuporteMensagem, Long> {

    List<SuporteMensagem> findByChamadoIdOrderByEmAsc(Long chamadoId);

    List<SuporteMensagem> findTop8ByChamadoIdAndEquipeFalseOrderByEmAsc(Long chamadoId);
}
