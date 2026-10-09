package com.raizestecnologia.relay.painel;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Painel de pedidos (Link): atendimento marcado como PRONTO no app. O LinkPro nao tem esse status,
 * entao ele fica aqui, por loja. A TV mostra os prontos por alguns minutos (ou ate "entregue").
 * Tabela: painel_pronto (cnpj + id_atendimento unicos).
 */
@Entity
@Table(name = "painel_pronto",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cnpj", "id_atendimento"}),
        indexes = @Index(columnList = "cnpj, pronto_em"))
public class PainelPronto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    /** Chave da loja (a mesma do X-Empresa). */
    @Column(name = "cnpj", length = 20, nullable = false)
    String cnpj;

    @Column(name = "id_atendimento", nullable = false)
    Long idAtendimento;

    @Column(name = "numero")
    Long numero;

    @Column(name = "nome", length = 80)
    String nome;

    @Column(name = "pronto_em", nullable = false)
    Instant prontoEm;

    /** Retirado/entregue: sai da TV antes do tempo. */
    @Column(name = "entregue_em")
    Instant entregueEm;

    protected PainelPronto() {}

    PainelPronto(String cnpj, Long idAtendimento) {
        this.cnpj = cnpj;
        this.idAtendimento = idAtendimento;
    }
}
