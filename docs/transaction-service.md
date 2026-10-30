# Étape 3 — Transaction Service

Implémentation locale Java 21 / Spring Boot 3.5.16 / Spring Cloud 2025.0.3, groupId `ma.enset`.
Aucun composant des étapes suivantes n'est activé. Aucun commit ni push automatique.

## Analyse préalable et contrats conservés

Le parent est un agrégateur de six modules. Transaction Service était une application vide.
Les 42 tests Account existants et les cinq tests de démarrage passaient avant les changements.
Account conserve son entité, son repository, sa couche métier et les contrats REST précédents :

| Méthode | Endpoint Account | Corps / réponse |
|---|---|---|
| POST | /api/accounts | CreateAccountRequest : customerReference, status facultatif, currency, balance ; 201 AccountResponse |
| GET | /api/accounts/{id} | AccountResponse : id, customerReference, status, currency, balance |
| GET | /api/accounts/{id}/balance | BalanceResponse : id, balance |
| PATCH | /api/accounts/{id}/debit | AmountRequest : amount ; AccountResponse |
| PATCH | /api/accounts/{id}/credit | AmountRequest : amount ; AccountResponse |
| PATCH | /api/accounts/{id}/status?status=BLOCKED | AccountResponse |
| GET | /api/accounts et /api/accounts/customer/{reference} | Liste AccountResponse |

Les erreurs sont des ProblemDetail : 400 validation, 404 absent, 409 conflit.
Le modèle Account existant persiste le solde en `double`. Les nouveaux contrats Feign lisent ses nombres JSON
en BigDecimal. Les nouvelles mutations vérifient que leur résultat est représentable sans perte dans ce
modèle, au lieu de migrer inutilement toute l'étape 2. Les anciennes opérations restent compatibles.

Ajouts strictement nécessaires dans Account Service :

| Méthode | Endpoint | Fonction |
|---|---|---|
| PUT | /api/accounts/operations/{operationId} | Débit/crédit/virement idempotent ; 200 reçu immuable |
| GET | /api/accounts/operations/{operationId} | Reçu validé ; 404 si aucun reçu **commis** visible |
| POST | /api/accounts/transfers | Même exécution atomique, type TRANSFER et en-tête Idempotency-Key obligatoires |

Corps interne : `{"type":"DEBIT","sourceAccountId":"UUID","destinationAccountId":null,"amount":250,"currency":"EUR"}`.
Pour TRANSFER, renseigner destinationAccountId. Le plafond est appliqué par Transaction Service ;
les API internes Account conservent la possibilité de montants supérieurs.

## Organisation des fichiers

```text
mvnw.cmd                                      correction Target nul sous PowerShell
README.md                                     point d'entrée actualisé
docs/
  transaction-service.md                      conception, exécution, limites
  transaction-service.postman_collection.json  scénario Postman exécutable
scripts/
  test-transaction-service.ps1
account-service/src/main/java/ma/enset/accountservice/
  domain/AccountOperation.java
  repository/AccountOperationRepository.java
  service/AccountOperationService.java
  web/rest/AccountOperationRestController.java
  web/rest/GlobalExceptionHandler.java         gestion des doublons concurrents
  web/rest/dto/AccountOperationRequest.java
  web/rest/dto/AccountOperationResponse.java
account-service/src/test/java/ma/enset/accountservice/
  AccountOperationIntegrationTests.java
transaction-service/
  pom.xml
  src/main/resources/application.properties
  src/main/java/ma/enset/transactionservice/
    TransactionServiceApplication.java
    config/
      AccountFeignConfiguration.java
      TransactionProperties.java
    domain/
      Transaction.java
      TransactionOperation.java
    repository/
      TransactionRepository.java
      TransactionOperationRepository.java
    service/
      TransactionService.java
      OperationStore.java
      exception/
        BusinessException.java
        AccountUnavailableException.java
        RecoveryRequiredException.java
    integration/
      AccountClient.java
      AccountGateway.java
      AccountClientResponse.java
      BalanceResponse.java
      AmountRequest.java
      AccountOperationRequest.java
      AccountOperationResponse.java
    web/rest/
      TransactionRestController.java
      GlobalExceptionHandler.java
      dto/
        CreateTransactionRequest.java
        TransactionResponse.java
        TransferRequest.java
        TransferResponse.java
        OperationResponse.java
    messaging/package-info.java                préparation uniquement
  src/test/resources/application.properties
  src/test/java/ma/enset/transactionservice/
    TransactionServiceApplicationTests.java    test initial conservé
    TransactionServiceTests.java
    TransactionRestIntegrationTests.java
    AccountFeignHttpIntegrationTests.java
```

`TransactionService` porte les règles et l'orchestration ; REST, puis les futurs GraphQL/MCP, appelleront
cette même couche. La validation Jakarta est appliquée au service aussi, avec `@Validated`.
`OperationStore` ouvre des transactions JPA locales courtes pour enregistrer l'intention ou les écritures.
`AccountGateway` applique le circuit et traduit les erreurs Feign ; `AccountClient` décrit les contrats HTTP.
`AccountOperationService` possède seul les mutations de soldes et reçus atomiques côté Account.
Il n'y a ni copie d'Account ni accès croisé aux repositories.

`Transaction` contient tous les champs ADRIA, avec UUID généré et Instant.now().
Les montants sont des BigDecimal, DECIMAL(19,2), strictement positifs, avec au maximum deux décimales.
Les virements créent deux écritures positives reliées par transferId et distinguées par direction DEBIT/CREDIT.
Leur catégorie est TRANSFER et leur pays technique INTERNAL : aucune géographie n'est déduite des comptes.

## Configuration

| Propriété | Valeur |
|---|---|
| Java / Boot / Cloud | 21 / 3.5.16 / 2025.0.3 |
| Account / Transaction | ports 8081 / 8082 |
| spring.application.name | transaction-service |
| Base Transaction | jdbc:h2:mem:transactiondb |
| Base Account | jdbc:h2:mem:accountdb |
| H2 | utilisateur sa, mot de passe vide, /h2-console |
| Schéma | create-drop, open-in-view=false |
| bank.transaction.max-amount | 1000, TransactionProperties validée |
| bank.services.account-url | http://localhost:8081 |
| Feign connexion / lecture | 1000 ms / 2000 ms |
| Circuit accountService | fenêtre 10, minimum 5, échec 50 %, attente OPEN 10 s |
| Retry Feign / OkHttp | NEVER_RETRY / retryOnConnectionFailure(false) |
| Actuator | /actuator/health, /actuator/info, /actuator/circuitbreakers |

La base de tests est distincte. Les tests HTTP réduisent les délais à 300 ms.
Le circuit ignore les erreurs métier 400/404/409 : elles ne traduisent pas une panne.
Aucun fallback de solde. Connexion refusée, timeout, 5xx et circuit OPEN donnent 503.
Les lectures n'ont pas non plus de retry automatique dans cette version simple.
Le nom logique Feign account-service prépare la découverte future ; l'URL explicite fonctionne sans Eureka.
Le plafond est externalisé et pourra être déplacé vers Config Server plus tard.

La configuration suit les documentations officielles :
[OpenFeign : transports, URL et retries](https://docs.spring.io/spring-cloud-openfeign/docs/current/reference/html/index.html),
[Resilience4j : configuration du circuit](https://resilience4j.readme.io/docs/circuitbreaker).

## Compilation et lancement

Depuis la racine, PowerShell avec JDK 21 :

```powershell
.\mvnw.cmd -pl transaction-service -am clean test
.\mvnw.cmd -pl transaction-service -am package -DskipTests
.\mvnw.cmd clean test
```

Les deux JAR peuvent être produits ensemble :

```powershell
.\mvnw.cmd -pl account-service,transaction-service -am package -DskipTests
```

Terminal 1 :

```powershell
.\mvnw.cmd -pl account-service spring-boot:run
# ou
java -jar .\account-service\target\account-service-0.0.1-SNAPSHOT.jar
```

Terminal 2 :

```powershell
.\mvnw.cmd -pl transaction-service spring-boot:run
# ou
java -jar .\transaction-service\target\transaction-service-0.0.1-SNAPSHOT.jar
```

Dans l'environnement d'exécution de l'agent, Java reçoit initialement un user.home incorrect.
Les commandes de vérification ont été lancées après
`$env:MAVEN_OPTS='-Duser.home=C:\Users\marwa'`, afin d'utiliser le cache Maven existant.
Cette variable de session n'est pas une configuration requise du projet sur une machine normale.

## API publique et exemples

Toutes les écritures publiques demandent `Idempotency-Key: <UUID>`, choisi et conservé par le client.
Une tentative répétée doit utiliser **la même clé et le même corps** ; une nouvelle opération utilise une nouvelle clé.
L'absence de clé ou un UUID mal formé donne 400. Une clé réutilisée avec un corps différent donne 409.
Une répétition achevée retourne 201 avec les identifiants déjà enregistrés.

| Méthode | Endpoint Transaction | Résultat |
|---|---|---|
| POST | /api/transactions | 201 TransactionResponse et Location |
| GET | /api/transactions | 200 liste |
| GET | /api/transactions/{id} | 200 ou 404 |
| GET | /api/transactions/account/{accountId} | 200 historique, éventuellement vide |
| POST | /api/transactions/transfers | 201 TransferResponse |
| GET | /api/transactions/operations/{id} | 200 état PENDING/COMPLETED ou 404 |
| POST | /api/transactions/operations/{id}/reconcile | 200 état COMPLETED ou erreur explicite |

Erreurs : 400 données invalides/plafond/auto-virement, 404 compte absent,
409 compte bloqué/devise incompatible/solde insuffisant/concurrence, 503 dépendance ou persistance indisponible.
La validation ajoute une liste errors au ProblemDetail. Un 503 après préparation indique operationId et recovery.
PENDING signifie « résultat à établir », pas « débit certainement effectué ».

Exemple Postman, POST `http://localhost:8082/api/transactions`, en-têtes
`Content-Type: application/json` et `Idempotency-Key: UUID-CHOISI` :

```json
{
  "accountId": "UUID-SOURCE",
  "amount": 250.00,
  "currency": "EUR",
  "merchantCategory": "FOOD",
  "country": "FR"
}
```

POST `http://localhost:8082/api/transactions/transfers`, avec une autre clé :

```json
{
  "sourceAccountId": "UUID-SOURCE",
  "destinationAccountId": "UUID-DESTINATION",
  "amount": 300.00,
  "currency": "EUR"
}
```

Importer `docs/transaction-service.postman_collection.json` et exécuter la collection dans l'ordre.
Elle crée les comptes fictifs, retient les UUID, génère les clés et vérifie les soldes/replays/rejets.

Exemple PowerShell indépendant, une fois les deux comptes créés :

```powershell
$sourceId = 'REMPLACER-PAR-UUID-SOURCE'
$destinationId = 'REMPLACER-PAR-UUID-DESTINATION'
$purchaseKey = [guid]::NewGuid().ToString()
$purchase = @{
    accountId = $sourceId; amount = 250; currency = 'EUR'
    merchantCategory = 'FOOD'; country = 'FR'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/api/transactions' `
    -Headers @{ 'Idempotency-Key' = $purchaseKey } -ContentType 'application/json' -Body $purchase

$transferKey = [guid]::NewGuid().ToString()
$transfer = @{
    sourceAccountId = $sourceId; destinationAccountId = $destinationId
    amount = 300; currency = 'EUR'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/api/transactions/transfers' `
    -Headers @{ 'Idempotency-Key' = $transferKey } -ContentType 'application/json' -Body $transfer
Invoke-RestMethod "http://localhost:8081/api/accounts/$sourceId/balance"

# Si 503 / résultat ambigu : conserver la clé ; consulter puis réconcilier.
Invoke-RestMethod "http://localhost:8082/api/transactions/operations/$purchaseKey"
Invoke-RestMethod -Method Post "http://localhost:8082/api/transactions/operations/$purchaseKey/reconcile"
```

Scénario complet, comptes fictifs créés automatiquement et exit code non nul au premier échec :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-transaction-service.ps1
```

Le script vérifie disponibilité, 5000/1000 EUR, achat 250 et source 4750, virement 300 et soldes 4450/1300,
absence de double débit sur répétitions, historique, refus de 1500 (400), blocage et refus (409).
Il affiche PASS pour chaque contrôle et FAIL en cas d'erreur.

## Cohérence distribuée et récupération

1. Valider la requête et le plafond, puis **commettre** une intention PENDING dans transactiondb.
   Une panne de cette première persistance empêche tout appel de mutation distant.
2. Consulter le reçu Account de cette clé. S'il existe, vérifier son identité, son montant, sa devise
   et l'équation sourceBalanceBefore - amount = sourceBalanceAfter, puis finaliser localement.
3. Sans reçu visible, lire compte(s) et solde source. Les validations locales sont préliminaires :
   Account les revérifie sous verrous juste avant la mutation.
4. Account réserve la clé par contrainte unique avant toute modification, verrouille les comptes
   par UUID dans un ordre stable et commit ensemble solde(s) et reçu. Une collision concurrente
   annule la tentative perdante, qui peut relire le reçu ou recevoir 409 et répéter la même clé.
5. Transaction Service enregistre la transaction, ou les deux écritures du virement, et l'état COMPLETED
   dans une même transaction locale, sous verrou de l'intention. Il ne répond succès qu'après commit.
6. Un timeout/5xx de mutation déclenche **une lecture** du reçu, jamais un nouveau débit automatique.
   Un reçu prouve le commit et permet de terminer. Un 404 ne prouve pas qu'une requête encore en cours
   ne commettra pas ensuite : conserver PENDING et retourner 503.
7. Après échec local, répéter le POST initial avec sa clé, ou appeler reconcile.
   La récupération recherche d'abord le reçu ; si aucun n'est visible, elle peut soumettre explicitement
   la même opération idempotente après validation. Même une première requête encore en vol ne peut
   produire un second débit grâce à la contrainte unique Account.
   reconcile n'est donc pas une simple lecture : c'est une reprise explicite de l'intention.

Il n'y a aucun crédit compensatoire. Le reçu figé est préféré à une nouvelle lecture du solde après débit :
d'autres opérations peuvent légitimement avoir modifié le solde courant entre-temps.
Le journal garde les opérations refusées ou ambiguës en PENDING ; leur reprise peut réussir après changement
de statut ou approvisionnement. Pour un abandon métier, ne pas appeler reconcile.
Les deux verrous et transactions ne prétendent pas former une transaction distribuée.

## Limites restantes

- H2 en mémoire et create-drop effacent soldes, reçus et intentions au redémarrage. La protection
  idempotente vaut pendant la conservation des journaux. Une reprise après redémarrage exige des bases
  persistantes, migrations, sauvegardes et une conservation coordonnée des clés ; ne pas rejouer des clés
  d'une ancienne session après perte d'une des bases.
- Le débit distant peut être confirmé alors que l'historique local reste temporairement absent.
  L'intention et le reçu permettent sa reprise tant que les bases survivent. La reprise est manuelle,
  sans ordonnanceur ni outbox/Kafka ; aucun succès n'est annoncé pendant cet intervalle.
- Une erreur de commit local peut elle-même être ambiguë : la reprise relit l'état avant de réécrire.
- Le solde Account historique reste double. Les nouveaux chemins refusent la perte de précision ;
  une migration coordonnée vers DECIMAL est nécessaire pour un usage sérieux.
- Les APIs internes et legacy ne sont pas authentifiées. Le plafond dépend de Transaction Service ;
  les appels directs à Account le contournent. L'authentification, autorisation, audit, limites d'accès
  et sécurité réseau appartiennent aux étapes ultérieures.
- Pas de pagination, rapprochement automatique, expiration de clés, annulation métier ou transactions XA.
  Un conflit concurrent peut nécessiter une répétition explicite de la même clé.
- Cette démonstration n'est pas un système prêt pour une banque réelle.

## Vérifications exécutées

Résultats réels du 30 octobre 2026, JDK Temurin 21.0.12.1 :

| Vérification exécutée | Résultat |
|---|---|
| `-pl transaction-service -am clean test` | BUILD SUCCESS, 57 tests, 0 échec, 0 erreur, 0 ignoré |
| `-pl transaction-service -am package -DskipTests` | BUILD SUCCESS, JAR exécutable produit, tests volontairement ignorés |
| `clean test` à la racine, après les dernières corrections métier | BUILD SUCCESS, six modules SUCCESS, 114 tests, 0 échec, 0 erreur, 0 ignoré |
| `-pl account-service,transaction-service -am package -DskipTests` | BUILD SUCCESS, les deux JAR de démonstration produits |
| Script réel sur 8081 / 8082, Windows PowerShell 5.1 | 45 PASS, exit 0 ; achat 250 : 4750 EUR ; virement 300 : 4450 / 1300 EUR ; replays sans double débit ; 400 et 409 vérifiés |
| Account réellement arrêté | Six requêtes retournent 503 avec intentions PENDING ; circuit accountService OPEN ; zéro transaction réussie enregistrée |
| Démarrage de Transaction seul | Health UP sans Account disponible ; processus arrêté après contrôle |
| Script lancé sans Account disponible | FAIL et exit 1 vérifiés |

Répartition : Account 53 tests (42 préexistants + 11 nouveaux), Transaction 57
(23 unitaires, 23 REST/JPA avec client Feign mocké, 10 HTTP Feign/OkHttp avec serveur TCP simulé,
1 contexte initial), quatre autres modules : un test chacun.
Les tests couvrent notamment les deux écritures de transfert annulées ensemble après échec de la seconde
insertion, la récupération locale sans second débit, le commit distant suivi d'un timeout,
les écritures concurrentes avec la même clé et les virements en sens opposés.

Le scénario réel a permis de corriger la lecture du JSON Actuator sous PowerShell 5.1 :
son media type fournisseur est exposé en byte[] et doit être décodé en UTF-8 avant ConvertFrom-Json.
Le wrapper racine a également été corrigé pour une propriété Target nulle sous PowerShell.

Les rapports détaillés JUnit sont dans les répertoires `target/surefire-reports` de chaque module.
Les journaux locaux de vérification sont regroupés dans `target/verification/` à la racine
(répertoire ignoré par Git). Les processus de démonstration sont arrêtés après les contrôles.

Commit proposé (non créé) :

```text
feat(transaction): implement banking transactions transfers feign and resilience4j
```
