package com.raizestecnologia.relay.audit;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Registro de auditoria: quem fez o que, quando e em qual loja.
 * Populado em pontos sensiveis (login, ajuste de estoque). Tabela: auditoria.
 */
@Entity
@Table(name = "auditoria", indexes = {
        @Index(name = "idx_auditoria_ts", columnList = "ts"),
        @Index(name = "idx_auditoria_cnpj", columnList = "cnpj")
})
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts", nullable = false)
    private Instant ts = Instant.now();

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "email")
    private String email;

    @Column(name = "nome")
    private String nome;

    @Column(name = "cnpj")
    private String cnpj;

    @Column(name = "acao", nullable = false)
    private String acao;

    @Column(name = "detalhe", length = 1000)
    private String detalhe;

    public Auditoria() {}

    public Auditoria(Long userId, String email, String nome, String cnpj, String acao, String detalhe) {
        this.userId = userId;
        this.email = email;
        this.nome = nome;
        this.cnpj = cnpj;
        this.acao = acao;
        this.detalhe = detalhe;
    }

    public Long getId() { return id; }
    public Instant getTs() { return ts; }
    public Long getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getNome() { return nome; }
    public String getCnpj() { return cnpj; }
    public String getAcao() { return acao; }
    public String getDetalhe() { return detalhe; }
}
