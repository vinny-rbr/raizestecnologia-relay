package com.raizestecnologia.relay.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Mensagem do grupo enquanto o chamado estava aberto (pra ver a conversa no relatório). */
@Entity
@Table(name = "suporte_mensagem", indexes = {@Index(name = "ix_sup_msg_chamado", columnList = "chamado_id,em")})
public class SuporteMensagem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chamado_id", nullable = false)
    private Long chamadoId;

    @Column(name = "autor", length = 120)
    private String autor;

    /** true = técnico (equipe); false = cliente */
    @Column(name = "equipe", nullable = false)
    private boolean equipe;

    @Column(name = "texto", length = 1000)
    private String texto;

    @Column(name = "em", nullable = false)
    private Instant em;

    public SuporteMensagem() {}

    public SuporteMensagem(Long chamadoId, String autor, boolean equipe, String texto, Instant em) {
        this.chamadoId = chamadoId;
        this.autor = autor;
        this.equipe = equipe;
        this.texto = texto == null ? null : (texto.length() > 990 ? texto.substring(0, 990) + "…" : texto);
        this.em = em;
    }

    public Long getId() { return id; }
    public Long getChamadoId() { return chamadoId; }
    public String getAutor() { return autor; }
    public boolean isEquipe() { return equipe; }
    public String getTexto() { return texto; }
    public Instant getEm() { return em; }
}
