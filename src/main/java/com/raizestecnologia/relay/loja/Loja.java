package com.raizestecnologia.relay.loja;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Loja conhecida pelo servidor: toda loja cujo agente ja conectou fica registrada aqui,
 * para o app poder listar tambem as que estao offline (com os ultimos dados). Tabela: loja.
 */
@Entity
@Table(name = "loja")
public class Loja {

    @Id
    @Column(name = "cnpj", length = 20)
    private String cnpj;

    @Column(name = "nome")
    private String nome;

    @Column(name = "atualizado_em")
    private Instant atualizadoEm = Instant.now();

    /** Quando o cliente começou a usar (1ª ativação). Base do "há quantos dias usa" e da cobrança proporcional. */
    @Column(name = "ativada_em")
    private Instant ativadaEm;

    /** Valor da mensalidade deste cliente (R$). Definido pelo master. */
    @Column(name = "mensalidade")
    private Double mensalidade;

    /** Id do cliente no Asaas (cus_...) e da assinatura mensal (sub_...). */
    @Column(name = "asaas_customer_id", length = 40)
    private String asaasCustomerId;

    @Column(name = "asaas_subscription_id", length = 40)
    private String asaasSubscriptionId;

    /** Data/hora do último pagamento confirmado pelo Asaas (webhook). */
    @Column(name = "ultimo_pagamento")
    private Instant ultimoPagamento;

    /** true = loja suspensa (pagamento pendente): os usuarios dela nao acessam o app. */
    @Column(name = "bloqueada", nullable = false, columnDefinition = "boolean not null default false")
    private boolean bloqueada = false;

    /** Motivo/observacao do bloqueio (mostrado ao usuario da loja). */
    @Column(name = "motivo_bloqueio", length = 200)
    private String motivoBloqueio;

    public Loja() {}

    public Loja(String cnpj, String nome) {
        this.cnpj = cnpj;
        this.nome = nome;
    }

    public String getCnpj() { return cnpj; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(Instant atualizadoEm) { this.atualizadoEm = atualizadoEm; }
    public Instant getAtivadaEm() { return ativadaEm; }
    public void setAtivadaEm(Instant ativadaEm) { this.ativadaEm = ativadaEm; }
    public Double getMensalidade() { return mensalidade; }
    public void setMensalidade(Double mensalidade) { this.mensalidade = mensalidade; }
    public String getAsaasCustomerId() { return asaasCustomerId; }
    public void setAsaasCustomerId(String v) { this.asaasCustomerId = v; }
    public String getAsaasSubscriptionId() { return asaasSubscriptionId; }
    public void setAsaasSubscriptionId(String v) { this.asaasSubscriptionId = v; }
    public Instant getUltimoPagamento() { return ultimoPagamento; }
    public void setUltimoPagamento(Instant v) { this.ultimoPagamento = v; }
    public boolean isBloqueada() { return bloqueada; }
    public void setBloqueada(boolean bloqueada) { this.bloqueada = bloqueada; }
    public String getMotivoBloqueio() { return motivoBloqueio; }
    public void setMotivoBloqueio(String motivoBloqueio) { this.motivoBloqueio = motivoBloqueio; }
}
