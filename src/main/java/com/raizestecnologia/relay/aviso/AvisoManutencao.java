package com.raizestecnologia.relay.aviso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Aviso de manutenção que o master liga no painel (uma linha só, id 1). Aparece no app (login, escolha da
 * empresa e início) e no painel das revendas desde que é ligado até o fim da manutenção.
 */
@Entity
@Table(name = "aviso_manutencao")
public class AvisoManutencao {

    /** o sistema fica fora do ar no período */
    public static final String INDISPONIVEL = "INDISPONIVEL";
    /** só um aviso (atualização, instabilidade): o sistema continua funcionando */
    public static final String AVISO = "AVISO";

    @Id
    private Long id = 1L;

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    @Column(name = "tipo", length = 20)
    private String tipo = INDISPONIVEL;

    @Column(name = "inicio")
    private Instant inicio;

    @Column(name = "fim")
    private Instant fim;

    @Column(name = "mensagem", length = 500)
    private String mensagem;

    @Column(name = "atualizado_em")
    private Instant atualizadoEm;

    public Long getId() { return id; }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean v) { this.ativo = v; }
    public String getTipo() { return tipo; }
    public void setTipo(String v) { this.tipo = v; }
    public Instant getInicio() { return inicio; }
    public void setInicio(Instant v) { this.inicio = v; }
    public Instant getFim() { return fim; }
    public void setFim(Instant v) { this.fim = v; }
    public String getMensagem() { return mensagem; }
    public void setMensagem(String v) { this.mensagem = v; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(Instant v) { this.atualizadoEm = v; }
}
