package com.raizestecnologia.relay.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
    List<Auditoria> findTop200ByOrderByTsDesc();
    List<Auditoria> findTop200ByCnpjOrderByTsDesc(String cnpj);
}
