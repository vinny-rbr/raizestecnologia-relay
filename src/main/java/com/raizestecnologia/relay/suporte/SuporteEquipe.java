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

    /** TECNICO (atende e assume chamado) | COMERCIAL (pode abrir chamado, nunca atende). null = técnico. */
    @Column(name = "funcao", length = 15)
    private String funcao;

    public SuporteEquipe() {}

    public SuporteEquipe(String numero, String nome, String funcao) {
        this.numero = numero;
        this.nome = nome;
        this.funcao = funcao;
    }

    public String getNumero() { return numero; }
    public String getNome() { return nome; }
    public void setNome(String v) { this.nome = v; }
    public String getFuncao() { return "COMERCIAL".equals(funcao) ? "COMERCIAL" : "TECNICO"; }
    public boolean isTecnico() { return !"COMERCIAL".equals(funcao); }
}
