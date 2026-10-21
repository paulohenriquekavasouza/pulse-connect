# Pulse Connect

**Controle o seu Fiat pelo celular, direto da tela inicial.**

Pulse Connect é um cliente Android leve e de código aberto para a nuvem Uconnect/Stellantis. Ele trava, destrava, liga e localiza o veículo, mostra o painel completo de dados e traz um widget com proteção biométrica. É Kotlin nativo, sem dependências pesadas e sem servidores intermediários: o app fala direto com a API oficial usando a sua conta.

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Target SDK 36](https://img.shields.io/badge/targetSdk-36-informational)
![Licença MIT](https://img.shields.io/badge/licen%C3%A7a-MIT-blue)

> Testado no **Pulse Abarth** no Brasil. Outros veículos Fiat com Uconnect/conectividade de fábrica tendem a funcionar, já que o app mostra só os comandos que a própria API informa para cada carro.

## Destaques

- **Comandos remotos:** travar, destravar, ligar e desligar o motor. Aparecem só os comandos que o veículo realmente suporta.
- **Localização:** mapa com a última posição conhecida e há quanto tempo foi atualizada. O app pede uma posição nova ao carro só quando a atual tem mais de 10 minutos, ou quando você pede.
- **Widget para a tela inicial:**
  - Formato compacto em uma linha ou completo em 4×2, no mesmo visual do app.
  - Todos os comandos exigem rosto ou impressão digital, com o bloqueio de tela do aparelho como alternativa.
- **Vários veículos:** cards com a imagem oficial de cada carro. Marque um favorito e o app abre direto nele.
- **Apelidos:** dê um nome próprio a cada carro, vinculado ao VIN e salvo no aparelho. Ele aparece no app e no widget.
- **Painel de dados:** combustível (% e litros), autonomia, hodômetro, bateria, pneus, portas e vidros. Também mostra o JSON bruto da API para quem quer ir além.
- **Mensagens claras:** em falhas de rede, o app diz se o comando chegou ou não a ser enviado.
- **Interface escura e direta**, pensada para uso rápido.

## Privacidade e segurança

- **Sem intermediários:** nenhum servidor próprio. As requisições vão do aparelho direto para a nuvem da Stellantis. As únicas outras conexões são o mapa (OpenStreetMap, com a biblioteca Leaflet via unpkg) e as imagens oficiais dos veículos da FCA.
- **Credenciais protegidas:** e-mail, senha e PIN ficam apenas no aparelho, cifrados com AES/GCM numa chave do Android Keystore.
- **Sem rastreamento:** nenhuma telemetria, analytics ou envio de logs.
- **Biometria:** com o login salvo, o app pede rosto ou impressão digital ao abrir, e cada comando do widget também passa por ela.

## Como usar

1. Instale o APK e entre com o e-mail, a senha e o PIN da sua conta Fiat/Uconnect.
2. Deixe **Manter conectado** ativo para o app entrar sozinho, só com a sua biometria, e para o widget funcionar em segundo plano.
3. Para o widget, segure na tela inicial → **Widgets** → **Pulse Connect**. Ele funciona em uma linha ou em 4×2.

## Como funciona

O Pulse Connect reproduz o fluxo de autenticação do cliente web oficial:

```
Gigya (login-us.fiat.com) → JWT → token Cognito (authz.sdpr-02)
  → credenciais AWS temporárias → requisições assinadas com SigV4 (channels.sdpr-02)
```

Os comandos são autorizados pelo PIN da conta e enviados ao veículo pela nuvem. A referência completa de endpoints, cabeçalhos e comandos está em [UCONNECT.md](UCONNECT.md).

## Compilando

Requisitos: JDK 17+ e Android SDK 36.

```bash
./gradlew test            # testes unitários
./gradlew assembleDebug   # APK de desenvolvimento
./gradlew assembleRelease # APK otimizado (R8)
```

Versões: AGP 8.13, Kotlin 2.2, `compileSdk`/`targetSdk` 36 e `minSdk` 26. As únicas dependências são `androidx.core` e `kotlinx-coroutines`.

## Estrutura

```
app/src/main/java/br/com/obdpulse/connect/
├── UconnectClient.kt, AwsSigV4.kt, Brand.kt, UconnectModels.kt     cliente da API
├── AuthFlow.kt, AuthRules.kt, SessionHolder.kt, UconnectStore.kt    sessão e credenciais
├── VehicleCatalog.kt, VehicleCommands.kt, VehicleImage.kt           veículos, comandos e imagens
├── VehicleParser.kt, VehicleFormatter.kt                             leitura e formatação dos dados
├── LoginActivity.kt, GarageActivity.kt, VehicleActivity.kt,
│   StatusActivity.kt, JsonActivity.kt                                telas
└── VehicleWidgetProvider.kt, CommandService.kt,
    WidgetConfirmActivity.kt, WidgetState.kt                          widget
app/src/test/   testes unitários (SigV4 contra o vetor oficial da AWS, sessão,
                catálogo de veículos, imagens, roteamento e formatação)
tools/          script Python para testar o login fora do app
```

## Contribuindo

Contribuições são bem-vindas. Um bom ponto de partida é ampliar a **tabela de imagens de veículos**: a API de veículos não retorna o código `mmvs` completo exigido pelo serviço de imagens da FCA. O app monta esse código com `brandCode` + `model` + posições 7–9 do VIN e um sufixo de série conhecido, que fica em `VehicleImage.kt`. Se você tem outro modelo ou versão, abra um PR com o sufixo correspondente.

Antes de enviar, rode `./gradlew test`.

## Licença

Distribuído sob a licença [MIT](LICENSE).

## Aviso

Projeto independente e não oficial, **sem qualquer vínculo com a Stellantis, a Fiat ou o Uconnect**. Os nomes e marcas citados pertencem aos seus donos. O uso de clientes não oficiais pode contrariar os termos de serviço da Stellantis e levar ao bloqueio da conta. Use por sua conta e risco e lembre-se de que os comandos agem sobre um veículo real.

## Créditos

O entendimento da API se apoia no trabalho da comunidade, em especial na biblioteca [py-uconnect](https://github.com/hass-uconnect/py-uconnect) e na integração [hass-uconnect](https://github.com/hass-uconnect/hass-uconnect) para Home Assistant.
