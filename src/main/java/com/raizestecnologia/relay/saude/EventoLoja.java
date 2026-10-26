package com.raizestecnologia.relay.saude;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Saúde das lojas: o que o agente viu acontecer na rede da loja (IP do PC mudou, impressora mudou de IP ou
 * caiu/voltou, conflito de IP, terminal do LinkPro com IP novo/repetido). Vai pro push do master e pro histórico.
 */
@Entity
@Table(name = "evento_loja", indexes = {@Index(name = "ix_evento_loja_criado", columnList = "criado_em"),
        @Index(name = "ix_evento_loja_loja", columnList = "loja,criado_em")})
public class EventoLoja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loja", length = 20, nullable = false)
    private String loja;

    @Column(name = "loja_nome", length = 120)
    private String lojaNome;

    /** ip_pc | impressora_ip | impressora_fora | impressora_voltou | conflito_ip | terminal_ip | terminal_conflito */
    @Column(name = "tipo", length = 30, nullable = false)
    private String tipo;

    /** grave | aviso | ok */
    @Column(name = "nivel", length = 10, nullable = false)
    private String nivel = "aviso";

    @Column(name = "titulo", length = 200, nullable = false)
    private String titulo;

    @Column(name = "detalhe", length = 1000)
    private String detalhe;

    /** Identifica "o mesmo problema" (não repete o push dentro de um tempo). */
    @Column(name = "chave", length = 200)
    private String chave;

    @Column(name = "push", nullable = false)
    private boolean push;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm = Instant.now();

    /** Master marcou "estou resolvendo": não repete o aviso desse problema até ele se resolver. */
    @Column(name = "resolvendo", nullable = false, columnDefinition = "boolean not null default false")
    private boolean resolvendo;

    @Column(name = "resolvendo_por", length = 120)
    private String resolvendoPor;

    @Column(name = "resolvendo_em")
    private Instant resolvendoEm;

    /** Última vez que o agente relatou esse problema (repetições não viram linha nova). */
    @Column(name = "visto_em")
    private Instant vistoEm;

    public Instant getVistoEm() { return vistoEm; }
    public void setVistoEm(Instant v) { this.vistoEm = v; }

    public boolean isResolvendo() { return resolvendo; }
    public void setResolvendo(boolean r) { this.resolvendo = r; }
    public String getResolvendoPor() { return resolvendoPor; }
    public void setResolvendoPor(String p) { this.resolvendoPor = p; }
    public Instant getResolvendoEm() { return resolvendoEm; }
    public void setResolvendoEm(Instant e) { this.resolvendoEm = e; }

    public Long getId() { return id; }
    public String getLoja() { return loja; }
    public void setLoja(String loja) { this.loja = loja; }
    public String getLojaNome() { return lojaNome; }
    public void setLojaNome(String lojaNome) { this.lojaNome = lojaNome; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public String getNivel() { return nivel; }
    public void setNivel(String nivel) { this.nivel = nivel; }
    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public String getDetalhe() { return detalhe; }
    public void setDetalhe(String detalhe) { this.detalhe = detalhe; }
    public String getChave() { return chave; }
    public void setChave(String chave) { this.chave = chave; }
    public boolean isPush() { return push; }
    public void setPush(boolean push) { this.push = push; }
    public Instant getCriadoEm() { return criadoEm; }
}
