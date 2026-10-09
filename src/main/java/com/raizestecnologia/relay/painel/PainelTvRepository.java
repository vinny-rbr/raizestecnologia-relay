package com.raizestecnologia.relay.painel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PainelTvRepository extends JpaRepository<PainelTv, String> {

    Optional<PainelTv> findFirstByCodigoAndCnpjIsNull(String codigo);

    List<PainelTv> findByCnpjOrderByCriadoEm(String cnpj);

    @Modifying
    @Transactional
    @Query("DELETE FROM PainelTv t WHERE t.cnpj IS NULL AND t.criadoEm < :antes")
    int limparNaoLigadas(Instant antes);
}
