package com.raizestecnologia.relay.cancelamento;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Pedido de cancelamento feito pelo garçom (sem permissão) e aprovado pelo celular de quem pode.
 * acao (JSON): {"tipo":"item","idAtendimento":..,"idItem":..,"qtd":..} (item já lançado — o agente cancela
 * na aprovação) ou {"tipo":"pedido-novo"} (tirar item/limpar o pedido ainda não enviado — só libera).
 */
@Entity
@Table(name = "solicitacao_cancelamento")
public class SolicitacaoCancelamento {

    public static final String PENDENTE = "PENDENTE", APROVADO = "APROVADO", RECUSADO = "RECUSADO",
            EXPIRADO = "EXPIRADO", ERRO = "ERRO";

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @Column(name = "loja", length = 20, nullable = false)
    private String loja;

    @Column(name = "loja_nome", length = 120)
    private String lojaNome;

    @Column(name = "solicitante_id")
    private Long solicitanteId;

    @Column(name = "solicitante_nome", length = 120)
    private String solicitanteNome;

    @Column(name = "descricao", length = 500, nullable = false)
    private String descricao;

    @Column(name = "acao", columnDefinition = "text", nullable = false)
    private String acao;

    @Column(name = "status", length = 12, nullable = false)
    private String status = PENDENTE;

    @Column(name = "aprovador_id")
    private Long aprovadorId;

    @Column(name = "aprovador_nome", length = 120)
    private String aprovadorNome;

    @Column(name = "resultado", length = 500)
    private String resultado;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm = Instant.now();

    @Column(name = "decidido_em")
    private Instant decididoEm;

    public SolicitacaoCancelamento() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLoja() { return loja; }
    public void setLoja(String loja) { this.loja = loja; }
    public String getLojaNome() { return lojaNome; }
    public void setLojaNome(String lojaNome) { this.lojaNome = lojaNome; }
    public Long getSolicitanteId() { return solicitanteId; }
    public void setSolicitanteId(Long solicitanteId) { this.solicitanteId = solicitanteId; }
    public String getSolicitanteNome() { return solicitanteNome; }
    public void setSolicitanteNome(String solicitanteNome) { this.solicitanteNome = solicitanteNome; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getAcao() { return acao; }
    public void setAcao(String acao) { this.acao = acao; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAprovadorId() { return aprovadorId; }
    public void setAprovadorId(Long aprovadorId) { this.aprovadorId = aprovadorId; }
    public String getAprovadorNome() { return aprovadorNome; }
    public void setAprovadorNome(String aprovadorNome) { this.aprovadorNome = aprovadorNome; }
    public String getResultado() { return resultado; }
    public void setResultado(String resultado) { this.resultado = resultado; }
    public Instant getCriadoEm() { return criadoEm; }
    public void setCriadoEm(Instant criadoEm) { this.criadoEm = criadoEm; }
    public Instant getDecididoEm() { return decididoEm; }
    public void setDecididoEm(Instant decididoEm) { this.decididoEm = decididoEm; }
}
