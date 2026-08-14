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
}
