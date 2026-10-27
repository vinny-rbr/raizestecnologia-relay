package com.raizestecnologia.relay.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Configurações do suporte (chave do conector, chave da TV, nome do dono do número, último sinal do conector). */
@Entity
@Table(name = "suporte_config")
public class SuporteConfig {

    @Id
    @Column(name = "chave", length = 40)
    private String chave;

    @Column(name = "valor", length = 300)
    private String valor;

    public SuporteConfig() {}

    public SuporteConfig(String chave, String valor) {
        this.chave = chave;
        this.valor = valor;
    }

    public String getChave() { return chave; }
    public String getValor() { return valor; }
    public void setValor(String v) { this.valor = v; }
}
