package com.raizestecnologia.relay.cobranca;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Cobrança combinada: um único pagamento no Asaas que cobre VÁRIAS lojas do mesmo
 * cliente (ele marca quais quer pagar). Quando o pagamento é confirmado (webhook),
 * todas as lojas do lote recebem baixa e são liberadas.
 *
 * itens: "cnpj:TIPO;cnpj:TIPO" — TIPO = IMPLANTACAO | MENSALIDADE.
 */
@Entity
@Table(name = "cobranca_lote", indexes = @Index(name = "idx_lote_payment", columnList = "asaas_payment_id"))
public class CobrancaLote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asaas_payment_id", length = 40)
    private String asaasPaymentId;

    @Column(name = "itens", length = 2000)
    private String itens;

    @Column(name = "valor")
    private double valor;

    @Column(name = "pago", nullable = false, columnDefinition = "boolean not null default false")
    private boolean pago = false;

    @Column(name = "criado_em")
    private Instant criadoEm = Instant.now();

    public CobrancaLote() {}

    public CobrancaLote(String asaasPaymentId, String itens, double valor) {
        this.asaasPaymentId = asaasPaymentId;
        this.itens = itens;
        this.valor = valor;
    }

    public Long getId() { return id; }
    public String getAsaasPaymentId() { return asaasPaymentId; }
    public void setAsaasPaymentId(String v) { this.asaasPaymentId = v; }
    public String getItens() { return itens; }
    public void setItens(String v) { this.itens = v; }
    public double getValor() { return valor; }
    public void setValor(double v) { this.valor = v; }
    public boolean isPago() { return pago; }
    public void setPago(boolean v) { this.pago = v; }
    public Instant getCriadoEm() { return criadoEm; }
}
