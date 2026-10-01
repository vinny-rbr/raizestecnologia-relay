package com.raizestecnologia.relay.catalogo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CatalogoProdutoRepository extends JpaRepository<CatalogoProduto, String> {

    /** Busca por nome (contém) ou código de barras (começa com). */
    @Query("SELECT c FROM CatalogoProduto c WHERE LOWER(c.nome) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR c.barras LIKE CONCAT(:q, '%') ORDER BY c.nome")
    List<CatalogoProduto> buscar(@Param("q") String q, Pageable pageable);
}
