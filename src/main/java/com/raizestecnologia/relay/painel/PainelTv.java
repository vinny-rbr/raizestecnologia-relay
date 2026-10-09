package com.raizestecnologia.relay.painel;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * TV ligada ao painel de pedidos. A TV abre a pagina, ganha um token (guardado no navegador dela)
 * e mostra um codigo de 6 digitos; o app digita o codigo e prende a TV na loja.
 * Tabela: painel_tv.
 */
@Entity
@Table(name = "painel_tv", indexes = @Index(columnList = "codigo"))
public class PainelTv {

    /** Segredo da TV (fica no navegador dela). */
    @Id
    @Column(name = "token", length = 40)
    String token;

    /** Codigo mostrado na tela enquanto nao esta ligada. */
    @Column(name = "codigo", length = 6)
    String codigo;

    /** Loja ligada (null = aguardando o codigo). */
    @Column(name = "cnpj", length = 20)
    String cnpj;

    @Column(name = "criado_em", nullable = false)
    Instant criadoEm;

    @Column(name = "visto_em")
    Instant vistoEm;

    protected PainelTv() {}

    PainelTv(String token, String codigo) {
        this.token = token;
        this.codigo = codigo;
        this.criadoEm = Instant.now();
    }
}
