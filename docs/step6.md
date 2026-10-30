# Étape 6 — GraphQL, MCP et agent bancaire

Java 21, Spring Boot 3.5.16 et Spring Cloud 2025.0.3 sont conservés.
Spring AI est fixé à **1.1.8** via son BOM. La branche 1.1.x cible Boot 3.5.x ; la branche AI 2.x cible Boot 4.
Références officielles consultées avant implémentation :

- [Compatibilité Spring AI / Boot](https://github.com/spring-projects/spring-ai/tree/1.1.x)
- [Serveur Streamable HTTP 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Client MCP 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-client-boot-starter-docs.html)
- [GPT-5 mini : Chat Completions et function calling](https://developers.openai.com/api/docs/models/gpt-5-mini)

Les contrôleurs et outils délèguent aux services existants. Aucun changement des règles métier, de Kafka, de la Gateway ou des services de découverte/configuration.

## Dépendances ajoutées

| Module | Dépendances |
| --- | --- |
| Parent | BOM `org.springframework.ai:spring-ai-bom:1.1.8` |
| Account, Transaction | `spring-boot-starter-graphql`, `spring-ai-starter-mcp-server-webmvc` |
| AI Agent | `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-ai-starter-model-openai`, `spring-ai-starter-mcp-client` |

Le BOM choisit les versions MCP transitives ; aucune version SDK indépendante n'est imposée.

## Fichiers créés

Les chemins Java ci-dessous sont relatifs à `src/main/java/ma/enset/<package>/` dans chaque module.

```text
.env.example
account-service/
  src/main/java/ma/enset/accountservice/
    web/graphql/AccountGraphQLController.java
    mcp/AccountMcpTools.java
    mcp/AccountMcpConfiguration.java
    security/McpAccessFilter.java
  src/main/resources/
    graphql/account.graphqls
    application-local.properties
transaction-service/
  src/main/java/ma/enset/transactionservice/
    web/graphql/TransactionGraphQLController.java
    mcp/TransactionMcpTools.java
    mcp/TransactionMcpConfiguration.java
    security/McpAccessFilter.java
  src/main/resources/
    graphql/transaction.graphqls
    application-local.properties
ai-agent-service/
  src/main/java/ma/enset/aiagentservice/
    config/McpClientConfiguration.java
    config/ChatClientConfiguration.java
    security/AgentAccessFilter.java
    service/BankingAgentService.java
    web/BankingAgentController.java
    web/dto/ChatRequest.java
    web/dto/ChatResponse.java
  src/main/resources/application-llm.properties
  src/test/java/ma/enset/aiagentservice/McpClientConfigurationIT.java
scripts/test-step6.ps1
docs/step6.md
docs/step6.postman_collection.json
```

Modifications : POM parent et trois POM métier/agent, trois `application.properties`, `.gitignore`, lien dans README.

## Démarrage

Compilation depuis la racine :

```powershell
.\mvnw.cmd -DskipTests clean package
```

Dans l'environnement de vérification, Maven résolvait son cache par défaut vers `C:\.m2\repository`, inaccessible. La commande effective réussie était :

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\marwa\.m2\repository' -DskipTests clean package
```

Dans chaque terminal de service, exporter le **même secret** `MCP_AUTH_TOKEN` avec une valeur aléatoire. `.env.example` décrit les variables ; Spring ne charge pas automatiquement un fichier `.env`.

```powershell
# Générer une valeur une fois puis la réutiliser dans les trois terminaux.
$env:MCP_AUTH_TOKEN = [guid]::NewGuid().ToString('N')
```

Lancer Eureka et Config Server selon [le guide existant](cloud-services.md), puis les services métier :

```powershell
java -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
java -jar transaction-service/target/transaction-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Ces commandes occupent chacune leur terminal. Le profil `local` active GraphiQL. Kafka reste configuré selon [le guide existant](kafka.md).

Lancer l'agent avec un vrai fournisseur LLM dans un troisième terminal contenant le même jeton MCP :

```powershell
$env:OPENAI_API_KEY = '<clé fournie par le développeur>'
$env:OPENAI_MODEL = 'gpt-5-mini'
$env:ACCOUNT_MCP_URL = 'http://localhost:8081'
$env:TRANSACTION_MCP_URL = 'http://localhost:8082'
# Secret indépendant conseillé pour le client REST ; sinon MCP_AUTH_TOKEN est utilisé.
$env:AGENT_AUTH_TOKEN = '<secret du client REST>'
java -jar ai-agent-service/target/ai-agent-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=llm
```

`OPENAI_BASE_URL` est optionnel, défaut `https://api.openai.com`. Une autre API compatible Chat Completions doit aussi prendre en charge le function calling. Les URLs MCP sont des URLs de base, sans `/mcp`.
Les deux serveurs MCP doivent être prêts avant le démarrage de l'agent en profil `llm`. Une connexion impossible fait échouer ce démarrage explicitement.

Sans clé, lancer sans profil `llm` : le service démarre sur 8083 et `/api/chat` authentifié répond **503**, indiquant qu'il attend la configuration LLM. Il ne génère aucune réponse artificielle.

Variables complémentaires conservées : `EUREKA_URL`, `CONFIG_SERVER_URL`, `KAFKA_BOOTSTRAP_SERVERS`.
Pour une démonstration isolée, les arguments `--eureka.client.enabled=false --spring.cloud.config.enabled=false` sont possibles ; Transaction nécessite alors `--bank.services.account-url=http://localhost:8081`. Cela ne modifie pas les fichiers de configuration partagés.

## Endpoints et exemples Postman

Importer [la collection Postman](step6.postman_collection.json). Renseigner `mcpToken` et `agentToken` sans les enregistrer dans Git. Exécuter les requêtes dans leur ordre : création des données, GraphQL, handshake MCP puis outils, agent.

| Interface | URL |
| --- | --- |
| GraphQL Account | `POST http://localhost:8081/graphql` |
| GraphQL Transaction | `POST http://localhost:8082/graphql` |
| GraphiQL local | `http://localhost:8081/graphiql`, `http://localhost:8082/graphiql` |
| MCP Account Streamable HTTP | `http://localhost:8081/mcp` |
| MCP Transaction Streamable HTTP | `http://localhost:8082/mcp` |
| Agent | `POST http://localhost:8083/api/chat` |

GraphQL Account, `Content-Type: application/json` :

```json
{"query":"{ accounts { id customerReference balance } }"}
```

GraphQL Transaction :

```json
{"query":"query($id: ID!) { transactionsByAccount(accountId: $id) { id amount currency createdAt } }","variables":{"id":"UUID-DU-COMPTE"}}
```

`amount` est converti avec `BigDecimal.toPlainString()`, `createdAt` avec `Instant.toString()` ; aucun scalaire additionnel n'est nécessaire. Le solde Account conserve son type métier `double`, exposé en GraphQL `Float`.

Les serveurs enregistrent les `@Tool` via `MethodToolCallbackProvider` et exposent un vrai transport HTTP. Handshake MCP : `initialize`, conserver `Mcp-Session-Id`, envoyer `notifications/initialized`, puis `tools/list` et `tools/call`. Chaque requête porte `Authorization: Bearer <MCP_AUTH_TOKEN>` et `Accept: application/json, text/event-stream`. Après initialisation, ajouter `Mcp-Session-Id` et `MCP-Protocol-Version` renvoyés par le serveur. La collection automatise ces variables et décode les réponses SSE.

Exemple `tools/call` après handshake :

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getAccountBalance","arguments":{"accountId":"UUID-DU-COMPTE"}}}
```

Outils Account : `getAccountDetails`, `getAccountBalance`, `getAccountsByCustomerReference`.
Outils Transaction : `getTransactionDetails`, `getTransactionsByAccount`.
Aucun outil d'écriture bancaire n'est enregistré.

Agent, `Content-Type: application/json`, `Authorization: Bearer <AGENT_AUTH_TOKEN>` :

```json
{"message":"Quel est le solde du compte UUID-DU-COMPTE ?"}
```

Contrat de réponse : `{"answer":"...","toolsUsed":["...getAccountBalance"]}`. Les noms peuvent être préfixés par Spring AI. `toolsUsed` est rempli après l'exécution effective des callbacks MCP, pas à partir du texte généré. Les logs indiquent les noms des outils terminés, sans arguments ni données bancaires. Le LLM choisit les outils et Spring AI exécute les appels et la boucle de réponse. Sans identifiant fourni, l'instruction système demande au modèle de solliciter un UUID ou une référence client.

## Vérifications réellement exécutées le 30 octobre 2026

- Build du réacteur complet : **BUILD SUCCESS**, six modules, Java 21 ; tests unitaires non exécutés par `-DskipTests`.
- Script HTTP réel `scripts/test-step6.ps1` : **18 assertions réussies**. Les quatre requêtes GraphQL retournent les données attendues ; `amount` et `createdAt` ont les formats explicites prévus.
- MCP sur les deux ports : handshake, `tools/list` (exactement 3 + 2 outils), `tools/call` sur **chacun des cinq outils**, fermeture des sessions. Les jetons absents et incorrects donnent 401.
- Données créées via REST : compte à 5000 EUR, transaction de 250.25 EUR ; GraphQL et MCP lisent le solde réel de **4749.75 EUR** et la transaction correspondante.
- Agent : requête sans jeton refusée avec 401 ; requête authentifiée sans LLM renvoyée avec 503 et un message explicite de configuration manquante.
- Test Java ciblé `McpClientConfigurationIT` : **1 test, 0 échec, 0 erreur**. Il utilise la configuration réelle de l'agent, initialise les deux clients HTTP authentifiés, découvre les cinq callbacks, consulte le solde par callback et appelle l'historique Transaction. Aucun LLM simulé.
- Démarrage complet de l'agent avec le profil `llm` : réussi, avec initialisation réelle des deux serveurs MCP. Pour cette vérification de câblage uniquement, une valeur API factice a permis de construire le client OpenAI ; aucune requête au fournisseur LLM n'a été envoyée. Cela ne valide donc pas la génération IA.

Commandes de reproduction, services déjà démarrés :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-step6.ps1
# Utiliser l'UUID affiché par le script précédent (solde de fixture 4749.75 EUR).
$env:STEP6_ACCOUNT_ID = '<UUID affiché>'
.\mvnw.cmd -pl ai-agent-service '-Dtest=McpClientConfigurationIT' test
# Après configuration d'un vrai LLM et redémarrage de l'agent avec le profil llm :
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-step6.ps1 -TestLlm
```

Le mode `-TestLlm` vérifie un appel MCP de consultation du compte dans `toolsUsed`, puis le montant exact et la devise de la réponse. Un simple HTTP 200 ne satisfait pas ce test.

## Limites restantes

- **Appel LLM réel non exécuté** : aucune clé `OPENAI_API_KEY` dans l'environnement. Le fonctionnement complet sélection d'outils → MCP → réponse du modèle reste à valider avec `-TestLlm`.
- Les vérifications HTTP ont désactivé Eureka, Config Client et les listeners Kafka par arguments de lancement. Kafka était absent ; la publication de la transaction de démonstration a signalé son échec après commit, selon le comportement existant. Aucun test Kafka ou cloud supplémentaire n'a été revendiqué.
- Le jeton partagé fournit un contrôle d'accès de service pour MCP et l'agent. Il ne représente pas l'identité ni les droits d'un client bancaire ; l'autorisation par propriétaire et TLS restent à ajouter avant une exposition en production. Les interfaces REST et GraphQL conservent le périmètre de sécurité du projet existant.
- Les bases H2 en mémoire perdent les fixtures au redémarrage. Aucun modèle ML, frontend, Keycloak, canal de messagerie ou changement DevOps n'a été ajouté.

Commit local demandé : `feat(ai): add graphql mcp servers and banking ai agent`.
