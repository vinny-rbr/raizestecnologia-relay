package com.raizestecnologia.relay.notify;

/** Disparado quando a sessao WebSocket de um agente fecha (queda ou reinicio). */
public record AgentDisconnectedEvent(String cnpj, String nome) {}
