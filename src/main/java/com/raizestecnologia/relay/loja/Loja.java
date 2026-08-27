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

    /** Codigo da revenda dona desta loja (vem do instalador do revendedor). null = venda direta. */
    @Column(name = "revenda_codigo", length = 20)
    private String revendaCodigo;

    /** true = revenda ja pagou os R$30 e liberou. false + revendaCodigo != null = aguardando ativacao. */
    @Column(name = "revenda_ativada", nullable = false, columnDefinition = "boolean not null default false")
    private boolean revendaAtivada = false;

    /** Grupo/pasta pra organizar lojas (ex.: um cliente com varias lojas). Rotulo livre. null = sem grupo. */
    @Column(name = "grupo", length = 80)
    private String grupo;

    /** Quando o cliente começou a usar (1ª ativação). Base do "há quantos dias usa" e da cobrança proporcional. */
    @Column(name = "ativada_em")
    private Instant ativadaEm;

    /** Valor da mensalidade deste cliente (R$). Definido pelo master. */
    @Column(name = "mensalidade")
    private Double mensalidade;

    /** Dia do mês em que vence a mensalidade (1..28). Default 5. Negociável por cliente. */
    @Column(name = "dia_vencimento", nullable = false, columnDefinition = "integer not null default 5")
    private int diaVencimento = 5;

    /** Id do cliente no Asaas (cus_...) e da assinatura mensal (sub_...). */
    @Column(name = "asaas_customer_id", length = 40)
    private String asaasCustomerId;

    @Column(name = "asaas_subscription_id", length = 40)
    private String asaasSubscriptionId;

    /** Data/hora do último pagamento confirmado pelo Asaas (webhook). */
    @Column(name = "ultimo_pagamento")
    private Instant ultimoPagamento;

    /** Implantação (R$50) quitada (Asaas ou manual). Enquanto false, é o que está pendente. */
    @Column(name = "implantacao_paga", nullable = false, columnDefinition = "boolean not null default false")
    private boolean implantacaoPaga = false;

    @Column(name = "implantacao_paga_em")
    private Instant implantacaoPagaEm;

    /** Vencimento da implantação (definido pelo master). null = padrão (3 dias após a instalação). */
    @Column(name = "implantacao_vence")
    private java.time.LocalDate implantacaoVence;

    /** Mensalidade paga ATÉ este dia 5 (inclusive). null = nenhuma mensalidade paga ainda. */
    @Column(name = "mensalidade_paga_ate")
    private java.time.LocalDate mensalidadePagaAte;

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
    public String getRevendaCodigo() { return revendaCodigo; }
    public void setRevendaCodigo(String c) { this.revendaCodigo = c; }
    public boolean isRevendaAtivada() { return revendaAtivada; }
    public void setRevendaAtivada(boolean a) { this.revendaAtivada = a; }
    public String getGrupo() { return grupo; }
    public void setGrupo(String grupo) { this.grupo = grupo == null || grupo.isBlank() ? null : grupo.trim(); }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(Instant atualizadoEm) { this.atualizadoEm = atualizadoEm; }
    public Instant getAtivadaEm() { return ativadaEm; }
    public void setAtivadaEm(Instant ativadaEm) { this.ativadaEm = ativadaEm; }
    public Double getMensalidade() { return mensalidade; }
    public void setMensalidade(Double mensalidade) { this.mensalidade = mensalidade; }
    public int getDiaVencimento() { return diaVencimento < 1 || diaVencimento > 28 ? 5 : diaVencimento; }
    public void setDiaVencimento(int dia) { this.diaVencimento = dia; }
    public String getAsaasCustomerId() { return asaasCustomerId; }
    public void setAsaasCustomerId(String v) { this.asaasCustomerId = v; }
    public String getAsaasSubscriptionId() { return asaasSubscriptionId; }
    public void setAsaasSubscriptionId(String v) { this.asaasSubscriptionId = v; }
    public Instant getUltimoPagamento() { return ultimoPagamento; }
    public void setUltimoPagamento(Instant v) { this.ultimoPagamento = v; }
    public boolean isImplantacaoPaga() { return implantacaoPaga; }
    public void setImplantacaoPaga(boolean v) { this.implantacaoPaga = v; }
    public Instant getImplantacaoPagaEm() { return implantacaoPagaEm; }
    public void setImplantacaoPagaEm(Instant v) { this.implantacaoPagaEm = v; }
    public java.time.LocalDate getImplantacaoVence() { return implantacaoVence; }
    public void setImplantacaoVence(java.time.LocalDate v) { this.implantacaoVence = v; }
    public java.time.LocalDate getMensalidadePagaAte() { return mensalidadePagaAte; }
    public void setMensalidadePagaAte(java.time.LocalDate v) { this.mensalidadePagaAte = v; }
    public boolean isBloqueada() { return bloqueada; }
    public void setBloqueada(boolean bloqueada) { this.bloqueada = bloqueada; }
    public String getMotivoBloqueio() { return motivoBloqueio; }
    public void setMotivoBloqueio(String motivoBloqueio) { this.motivoBloqueio = motivoBloqueio; }
}
