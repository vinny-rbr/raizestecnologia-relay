package com.raizestecnologia.relay.notify;

/** Disparado quando um agente de loja (re)conecta no relay. */
public record AgentConnectedEvent(String cnpj, String nome) {}
