package com.raizestecnologia.relay.catalogo;

import jakarta.persistence.*;

/**
 * Catalogo nacional de produtos por codigo de barras (EAN/GTIN). Alimenta o
 * "Buscar por codigo de barras" no cadastro de produto do app: digita o EAN e
 * puxa nome, NCM, CEST e unidade. Importado de uma base do cliente (HOST.FDB).
 * Tabela: catalogo_produto (chave = barras).
 */
@Entity
@Table(name = "catalogo_produto")
public class CatalogoProduto {

    /** Codigo de barras (EAN/GTIN), so digitos. Chave. */
    @Id
    @Column(name = "barras", length = 20, nullable = false)
    private String barras;

    @Column(name = "nome", length = 200)
    private String nome;

    @Column(name = "ncm", length = 10)
    private String ncm;

    @Column(name = "cest", length = 10)
    private String cest;

    @Column(name = "unidade", length = 10)
    private String unidade;

    @Column(name = "marca", length = 80)
    private String marca;

    public CatalogoProduto() {}

    public CatalogoProduto(String barras, String nome, String ncm, String cest, String unidade, String marca) {
        this.barras = barras;
        this.nome = nome;
        this.ncm = ncm;
        this.cest = cest;
        this.unidade = unidade;
        this.marca = marca;
    }

    public String getBarras() { return barras; }
    public void setBarras(String barras) { this.barras = barras; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getNcm() { return ncm; }
    public void setNcm(String ncm) { this.ncm = ncm; }
    public String getCest() { return cest; }
    public void setCest(String cest) { this.cest = cest; }
    public String getUnidade() { return unidade; }
    public void setUnidade(String unidade) { this.unidade = unidade; }
    public String getMarca() { return marca; }
    public void setMarca(String marca) { this.marca = marca; }
}
