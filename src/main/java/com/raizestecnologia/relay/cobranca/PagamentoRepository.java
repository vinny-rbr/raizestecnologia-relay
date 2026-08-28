package com.raizestecnologia.relay.cobranca;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {
    List<Pagamento> findByCnpjOrderByPagoEmDesc(String cnpj);
}
