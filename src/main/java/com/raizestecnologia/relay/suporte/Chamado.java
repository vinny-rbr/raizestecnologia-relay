package com.raizestecnologia.relay.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Chamado de suporte aberto a partir dos grupos "SUP..." do WhatsApp do Lucas: o cliente escreve no grupo e,
 * se não tem chamado em aberto ali, abre um. A equipe atende (respondendo no grupo, #atendendo ou botão) e
 * finaliza (#finalizado ou botão). Vai pra TV de suporte e pros relatórios.
 */
@Entity
@Table(name = "suporte_chamado", indexes = {@Index(name = "ix_sup_chamado_status", columnList = "status"),
        @Index(name = "ix_sup_chamado_aberto", columnList = "aberto_em"),
        @Index(name = "ix_sup_chamado_grupo", columnList = "grupo_id,status")})
public class Chamado {

    public static final String ABERTO = "ABERTO";
    public static final String ATENDIMENTO = "ATENDIMENTO";
    public static final String FINALIZADO = "FINALIZADO";
    /** aberto de brincadeira/por engano: some da TV e dos relatórios (finalizadoPor/Em = quem descartou e quando) */
    public static final String DESCARTADO = "DESCARTADO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** id do grupo no WhatsApp (...@g.us) */
    @Column(name = "grupo_id", length = 80, nullable = false)
    private String grupoId;

    @Column(name = "grupo_nome", length = 150)
    private String grupoNome;

    /** quem abriu (cliente) */
    @Column(name = "cliente_nome", length = 120)
    private String clienteNome;

    @Column(name = "cliente_numero", length = 30)
    private String clienteNumero;

    @Column(name = "primeira_mensagem", length = 500)
    private String primeiraMensagem;

    @Column(name = "ultima_mensagem", length = 500)
    private String ultimaMensagem;

    @Column(name = "ultima_mensagem_em")
    private Instant ultimaMensagemEm;

    @Column(name = "qtd_mensagens", nullable = false)
    private int qtdMensagens = 1;

    /** ABERTO | ATENDIMENTO | FINALIZADO */
    @Column(name = "status", length = 15, nullable = false)
    private String status = ABERTO;

    @Column(name = "aberto_em", nullable = false)
    private Instant abertoEm = Instant.now();

    /** primeira mensagem da equipe no grupo depois que o chamado abriu (tempo de resposta) */
    @Column(name = "primeira_resposta_em")
    private Instant primeiraRespostaEm;

    /** tipo de problema (Categorias), pelas mensagens do cliente */
    @Column(name = "categoria", length = 60)
    private String categoria;

    public String getCategoria() { return categoria; }
    public void setCategoria(String v) { this.categoria = v; }

    /** categoria escolhida no painel: o robô não troca mais ao finalizar */
    @Column(name = "categoria_manual")
    private Boolean categoriaManual;

    public boolean isCategoriaManual() { return Boolean.TRUE.equals(categoriaManual); }
    public void setCategoriaManual(boolean v) { this.categoriaManual = v; }

    /** última mensagem da equipe no grupo (cliente "esperando" = mandou mensagem depois disso e ninguém respondeu) */
    @Column(name = "ultima_resposta_em")
    private Instant ultimaRespostaEm;

    @Column(name = "atendido_por", length = 80)
    private String atendidoPor;

    @Column(name = "atendido_em")
    private Instant atendidoEm;

    @Column(name = "finalizado_por", length = 80)
    private String finalizadoPor;

    @Column(name = "finalizado_em")
    private Instant finalizadoEm;

    public Long getId() { return id; }
    public String getGrupoId() { return grupoId; }
    public void setGrupoId(String v) { this.grupoId = v; }
    public String getGrupoNome() { return grupoNome; }
    public void setGrupoNome(String v) { this.grupoNome = v; }
    public String getClienteNome() { return clienteNome; }
    public void setClienteNome(String v) { this.clienteNome = v; }
    public String getClienteNumero() { return clienteNumero; }
    public void setClienteNumero(String v) { this.clienteNumero = v; }
    public String getPrimeiraMensagem() { return primeiraMensagem; }
    public void setPrimeiraMensagem(String v) { this.primeiraMensagem = v; }
    public String getUltimaMensagem() { return ultimaMensagem; }
    public void setUltimaMensagem(String v) { this.ultimaMensagem = v; }
    public Instant getUltimaMensagemEm() { return ultimaMensagemEm; }
    public void setUltimaMensagemEm(Instant v) { this.ultimaMensagemEm = v; }
    public int getQtdMensagens() { return qtdMensagens; }
    public void setQtdMensagens(int v) { this.qtdMensagens = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getAbertoEm() { return abertoEm; }
    public void setAbertoEm(Instant v) { this.abertoEm = v; }
    public Instant getPrimeiraRespostaEm() { return primeiraRespostaEm; }
    public void setPrimeiraRespostaEm(Instant v) { this.primeiraRespostaEm = v; }
    public Instant getUltimaRespostaEm() { return ultimaRespostaEm; }
    public void setUltimaRespostaEm(Instant v) { this.ultimaRespostaEm = v; }

    /** Desde quando o cliente espera resposta (null = não está esperando). Aguardando: desde que abriu. */
    public Instant esperandoDesde() {
        if (FINALIZADO.equals(status)) return null;
        if (ABERTO.equals(status) && ultimaRespostaEm == null) return abertoEm;
        if (ultimaMensagemEm == null) return null;
        return ultimaRespostaEm == null || ultimaMensagemEm.isAfter(ultimaRespostaEm) ? ultimaMensagemEm : null;
    }

    /** último aviso no celular de "chamado parado" (não repete enquanto o cliente continua na mesma espera) */
    @Column(name = "alerta_em")
    private Instant alertaEm;

    public Instant getAlertaEm() { return alertaEm; }
    public void setAlertaEm(Instant v) { this.alertaEm = v; }

    /** o mesmo grupo já teve um chamado finalizado nas últimas 24h: este é um retorno daquele (id) */
    @Column(name = "retorno_de")
    private Long retornoDe;

    public Long getRetornoDe() { return retornoDe; }
    public void setRetornoDe(Long v) { this.retornoDe = v; }

    /** já avisou no celular que o chamado passou de 15 min aberto */
    @Column(name = "alerta_longo")
    private Boolean alertaLongo;

    public boolean isAlertaLongo() { return Boolean.TRUE.equals(alertaLongo); }
    public void setAlertaLongo(boolean v) { this.alertaLongo = v; }

    /** o técnico escreveu primeiro no grupo (contato ativo): não entra na média de tempo de resposta */
    @Column(name = "iniciado_pela_equipe")
    private Boolean iniciadoPelaEquipe;

    public boolean isIniciadoPelaEquipe() { return Boolean.TRUE.equals(iniciadoPelaEquipe); }
    public void setIniciadoPelaEquipe(boolean v) { this.iniciadoPelaEquipe = v; }

    public String getAtendidoPor() { return atendidoPor; }
    public void setAtendidoPor(String v) { this.atendidoPor = v; }
    public Instant getAtendidoEm() { return atendidoEm; }
    public void setAtendidoEm(Instant v) { this.atendidoEm = v; }
    public String getFinalizadoPor() { return finalizadoPor; }
    public void setFinalizadoPor(String v) { this.finalizadoPor = v; }
    public Instant getFinalizadoEm() { return finalizadoEm; }
    public void setFinalizadoEm(Instant v) { this.finalizadoEm = v; }
}
