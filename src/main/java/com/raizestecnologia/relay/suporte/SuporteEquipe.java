package com.raizestecnologia.relay.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Número de WhatsApp de quem é da equipe de suporte: mensagem dele no grupo não abre chamado, atende. */
@Entity
@Table(name = "suporte_equipe")
public class SuporteEquipe {

    /** só dígitos, com DDI (5596991234567) */
    @Id
    @Column(name = "numero", length = 30)
    private String numero;

    @Column(name = "nome", length = 80, nullable = false)
    private String nome;

    public SuporteEquipe() {}

    public SuporteEquipe(String numero, String nome) {
        this.numero = numero;
        this.nome = nome;
    }

    public String getNumero() { return numero; }
    public String getNome() { return nome; }
    public void setNome(String v) { this.nome = v; }
}
