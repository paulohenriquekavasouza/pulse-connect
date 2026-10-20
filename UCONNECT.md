# API Uconnect — referência técnica

Este documento descreve como o Pulse Connect conversa com a nuvem Uconnect/Stellantis (FCA Global Connected Vehicle). É a mesma API usada pelo cliente web oficial, já documentada pela comunidade na biblioteca [py-uconnect](https://github.com/hass-uconnect/py-uconnect).

## Região

As contas brasileiras são atendidas pela configuração **`FIAT_US`** da py-uconnect. Não existe um backend LATAM separado.

| Item | Valor |
|---|---|
| Login (Gigya/SAP CDC) | `https://login-us.fiat.com` |
| Troca de token | `https://authz.sdpr-02.fcagcv.com/v2/cognito/identity/token` |
| API de veículos | `https://channels.sdpr-02.fcagcv.com` |
| Autorização por PIN | `https://mfa.fcl-02.fcagcv.com` |
| Cognito / região AWS | `cognito-identity.us-east-1.amazonaws.com` / `us-east-1` |

As chaves de cliente (`LOGIN_API_KEY`, `API_KEY`, `AUTH_KEY`) são públicas, as mesmas do cliente web, e estão em `Brand.kt`.

## Autenticação

1. `GET accounts.webSdkBootstrap?apiKey=…`: inicia a sessão Gigya (cookies).
2. `POST accounts.login`: e-mail e senha na query string, com User-Agent de navegador. Retorna `sessionInfo.login_token`.
3. `POST accounts.getJWT`: troca o `login_token` por um JWT (`id_token`). O `sub` do JWT é o `uid` da conta.
4. `POST authz…/v2/cognito/identity/token` com `{"gigya_token": <JWT>}`: retorna `Token` e `IdentityId`. O nome do campo de token varia entre `Token`, `token` e outros, então o parsing é tolerante.
5. `POST cognito-identity` com `GetCredentialsForIdentity`: devolve credenciais AWS temporárias.
6. Todas as chamadas seguintes são assinadas com **AWS SigV4** (serviço `execute-api`).

Cabeçalhos enviados em toda requisição: `x-clientapp-name: CWP`, `x-clientapp-version`, `clientrequestid` (hex aleatório), `x-api-key`, `locale` e `x-originator-type: web`.

## Endpoints de leitura

Prefixo: `/accounts/{uid}/vehicles`.

| Dado | Endpoint |
|---|---|
| Lista de veículos | `GET /v4/accounts/{uid}/vehicles?stage=ALL&sdp=ALL` |
| Status (combustível, hodômetro, bateria, pneus) | `GET /v4/…/{vin}/status/` (se falhar, `/v3`) |
| Status remoto (portas e vidros) | `GET /v1/…/{vin}/remote/status` |
| Última localização | `GET /v1/…/{vin}/location/lastknown` |
| Extras | `vhr`, `maintenance/history`, `notifications`, `subscription`, `svla/status` |

Observações:
- `remote/status` pode vir vazio (`{}`).
- `fuelAmountLevel` é a porcentagem e `fuelAmount` o volume em litros.
- Os comandos disponíveis vêm em `services[]`: só valem os que têm `vehicleCapable` e `serviceEnabled` iguais a `true`.

## Comandos

1. `POST {mfa}/v1/accounts/{uid}/ignite/pin/authenticate` com `{"pin": base64(PIN)}`: retorna `token`.
2. `POST /v1/accounts/{uid}/vehicles/{vin}/remote` (ou `/location` para `VF`) com `{"command": <código>, "pinAuth": <token>}`.

| Código | Ação |
|---|---|
| `RDL` | Travar portas |
| `RDU` | Destravar portas |
| `REON` | Ligar o motor |
| `REOFF` | Desligar o motor |
| `VF` | Atualizar a localização |
| `HBLF` | Buzina e pisca (suportado pela API, ainda não usado no app) |

Um comando é assíncrono: a nuvem repassa ao veículo pela rede celular e o resultado leva alguns segundos. Depois de um `VF`, o app consulta `lastknown` até o carimbo de tempo mudar.

## Imagens dos veículos

As imagens sem fundo vêm do serviço de assets da FCA LATAM:

```
https://asset-service.k8s.fcalatam.com.br/asset/getPngImage
  ?source=connectivity&consumer=responsive&market=3433&brand=00&model=363
  &mmvs=00363ASZ1743&body=743&year=2026&view=EXT&angle=1&width=780&height=480
```

- **Obrigatórios:** `market`, `brand`, `model`, `mmvs` e `body`; o `body` é obrigatório, mas qualquer valor serve.
- **Opcional:** `opt`, com os opcionais do carro.
- **O que define a imagem:** só o `mmvs` exato.
- **Como o `mmvs` é montado:** a API de veículos não o retorna. O app usa `brandCode` + `model` + posições 7–9 do VIN, mais um sufixo de série conhecido, guardado na tabela de `VehicleImage.kt`.

## Testando o login fora do app

`tools/uconnect_login_test.py` usa a py-uconnect para entrar com a sua conta e listar os veículos. As credenciais ficam só na sua máquina.

```bash
pip install py-uconnect
python tools/uconnect_login_test.py
```

## Avisos

- **Termos de uso:** clientes não oficiais podem contrariar os termos da Stellantis e levar ao bloqueio da conta.
- **Dados sensíveis:** `login_token`, JWT, `uid`, VIN e coordenadas são dados pessoais. Não os publique em issues ou logs.
