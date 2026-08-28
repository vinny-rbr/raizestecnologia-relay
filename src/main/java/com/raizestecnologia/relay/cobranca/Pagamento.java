package com.raizestecnologia.relay.cobranca;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Baixa de um pagamento (implantação/mensalidade), manual ou Asaas. Histórico de parcelas pagas. */
@Entity
@Table(name = "pagamento", indexes = @Index(name = "ix_pag_cnpj", columnList = "cnpj"))
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cnpj", length = 20, nullable = false)
    private String cnpj;

    @Column(name = "item", length = 30)
    private String item;          // "Implantação" | "Mensalidade"

    @Column(name = "valor")
    private double valor;

    @Column(name = "competencia")
    private LocalDate competencia; // período coberto (vencimento pago); implantação = null

    @Column(name = "pago_em")
    private Instant pagoEm = Instant.now();

    @Column(name = "forma", length = 10)
    private String forma;          // "manual" | "asaas"

    public Pagamento() {}

    public Pagamento(String cnpj, String item, double valor, LocalDate competencia, String forma) {
        this.cnpj = cnpj;
        this.item = item;
        this.valor = valor;
        this.competencia = competencia;
        this.forma = forma;
    }

    public Long getId() { return id; }
    public String getCnpj() { return cnpj; }
    public String getItem() { return item; }
    public double getValor() { return valor; }
    public LocalDate getCompetencia() { return competencia; }
    public Instant getPagoEm() { return pagoEm; }
    public String getForma() { return forma; }
}
