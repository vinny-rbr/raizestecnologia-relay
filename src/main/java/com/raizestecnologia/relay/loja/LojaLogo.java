package com.raizestecnologia.relay.loja;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Logo da loja enviado pelo painel (master/revenda): vai nos PDFs do app. Tabela própria pra não pesar a lista de lojas. */
@Entity
@Table(name = "loja_logo")
public class LojaLogo {

    @Id
    @Column(name = "cnpj", length = 20)
    private String cnpj;

    @Column(name = "imagem", nullable = false, columnDefinition = "bytea")
    private byte[] imagem;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm = Instant.now();

    public String getCnpj() { return cnpj; }
    public void setCnpj(String c) { this.cnpj = c; }
    public byte[] getImagem() { return imagem; }
    public void setImagem(byte[] i) { this.imagem = i; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(Instant a) { this.atualizadoEm = a; }
}
