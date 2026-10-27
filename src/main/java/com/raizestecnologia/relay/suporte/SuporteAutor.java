package com.raizestecnologia.relay.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Quem já escreveu nos grupos SUP (pra marcar no painel quem é técnico/comercial sem precisar saber o número). */
@Entity
@Table(name = "suporte_autor")
public class SuporteAutor {

    /** número (ou o id que o WhatsApp mandou no lugar dele) */
    @Id
    @Column(name = "numero", length = 40)
    private String numero;

    @Column(name = "nome", length = 120)
    private String nome;

    @Column(name = "ultimo_grupo", length = 150)
    private String ultimoGrupo;

    @Column(name = "ultima_vez")
    private Instant ultimaVez;

    @Column(name = "mensagens")
    private Integer mensagens;

    public SuporteAutor() {}

    public SuporteAutor(String numero) { this.numero = numero; }

    public String getNumero() { return numero; }
    public String getNome() { return nome; }
    public void setNome(String v) { this.nome = v; }
    public String getUltimoGrupo() { return ultimoGrupo; }
    public void setUltimoGrupo(String v) { this.ultimoGrupo = v; }
    public Instant getUltimaVez() { return ultimaVez; }
    public void setUltimaVez(Instant v) { this.ultimaVez = v; }
    public int getMensagens() { return mensagens == null ? 0 : mensagens; }
    public void setMensagens(int v) { this.mensagens = v; }
}
