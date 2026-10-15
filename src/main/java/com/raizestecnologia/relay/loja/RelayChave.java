package com.raizestecnologia.relay.loja;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Valor de configuração interno do relay que precisa sobreviver aos deploys (ex.: chaves do passe local). */
@Entity
@Table(name = "relay_chave")
public class RelayChave {

    @Id
    @Column(name = "nome", length = 60)
    private String nome;

    @Column(name = "valor", columnDefinition = "text", nullable = false)
    private String valor;

    public RelayChave() {}

    public RelayChave(String nome, String valor) {
        this.nome = nome;
        this.valor = valor;
    }

    public String getNome() { return nome; }
    public String getValor() { return valor; }
}
