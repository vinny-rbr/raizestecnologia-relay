# Raízes Tecnologia — Servidor Central (Relay)

Ponte na nuvem entre o **app** (Play/App Store) e os **agentes** instalados no PC de cada loja.

- O app fala HTTP com este servidor (`/api/...`), enviando o CNPJ da loja no cabeçalho `X-Empresa`.
- Cada agente da loja abre uma conexão **de saída** (WebSocket) em `/agent`, se registra pelo CNPJ
  (lido da tabela `EMITENTE`) e responde às requisições — sem precisar de IP fixo/abrir portas na loja.

## Endpoints (app)
- `GET  /api/health` — status + nº de lojas conectadas
- `GET  /api/empresas` — lojas atualmente online (`cnpj`, `nome`)
- `POST /api/auth/login` — login (emite token)
- `*    /api/**` — repassado ao agente da loja do cabeçalho `X-Empresa`

## Rodar local
```
mvn spring-boot:run    # sobe em :9090
```

## Deploy na Render
Suba este diretório num repositório Git e crie um **Web Service (Docker)** na Render apontando pro `Dockerfile`
(ou use o `render.yaml`). A Render injeta `PORT`; o app aponta pra URL pública gerada.
O agente de cada loja recebe `RELAY_URL=wss://<sua-url>.onrender.com/agent`.
