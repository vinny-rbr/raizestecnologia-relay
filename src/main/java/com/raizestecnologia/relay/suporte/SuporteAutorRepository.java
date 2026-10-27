package com.raizestecnologia.relay.suporte;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuporteAutorRepository extends JpaRepository<SuporteAutor, String> {

    List<SuporteAutor> findTop300ByOrderByUltimaVezDesc();
}
