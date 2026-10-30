# Étape 5 — Kafka (Partie F)

Java 21, Spring Boot 3.5.16 et Spring Cloud 2025.0.3 restent inchangés.
`spring-kafka` est géré par le BOM Spring Boot (version résolue : 3.3.16).

## Fonctionnement

`TransactionService` vérifie les règles bancaires et le reçu idempotent d'Account Service.
`OperationStore.complete()` verrouille l'opération, persiste ses écritures, passe à `COMPLETED`
et émet les événements Spring dans la même transaction locale `REQUIRES_NEW`.
`TransactionEventPublisher` les envoie avec `KafkaTemplate` uniquement à `AFTER_COMMIT`.
Un rollback supprime donc la publication. Un refus, un débit non confirmé ou une opération
`PENDING` ne produit aucun événement. Une réconciliation produit les événements seulement
si elle confirme et termine l'opération. Le retour anticipé sur `COMPLETED`, protégé par le
verrou existant, évite une nouvelle publication lors d'un rejeu HTTP, même concurrent.

Un achat produit un événement ; un virement produit un événement pour chaque écriture
persistée (débit et crédit), avec le compte concerné comme clé Kafka. Le consumer journalise
seulement les événements : il n'appelle aucun repository ni Account Service.

Topic : `bank.transactions.created`, une partition et une réplique pour le broker local.
Groupe : `adria-transaction-logging`. La désérialisation impose le type du record, ignore
les en-têtes de type externes, limite les packages de confiance au package `messaging`
et utilise `ErrorHandlingDeserializer`. Le champ `occurredAt` est sérialisé en ISO 8601.

## Démarrer Kafka

Depuis la racine, après démarrage de Docker Desktop avec le moteur Linux :

```powershell
docker compose -f docker-compose.kafka.yml config --quiet
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.kafka.yml ps
docker compose -f docker-compose.kafka.yml logs -f kafka
```

Attendre l'état `healthy`. L'image officielle est fixée à `apache/kafka:3.9.1`, en KRaft
sans ZooKeeper. Les applications lancées sur Windows utilisent `localhost:9092` ;
le listener interne du réseau Compose annonce `kafka:19092` et le contrôleur utilise 9093.
Seul le port client 9092 est exposé. `NewTopic` crée le topic au démarrage du service.

## Compiler et lancer les services

```powershell
.\mvnw.cmd -pl transaction-service -am -DskipTests package
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
.\mvnw.cmd -pl transaction-service spring-boot:run
```

Le parcours habituel Eureka / Config Server / Gateway reste utilisable ; voir
[l'étape 4](cloud-services.md). Pour une démonstration Kafka autonome, lancer seulement
Account Service et Transaction Service dans deux terminaux (Kafka doit être healthy) :

```powershell
# Terminal 1 : Account Service
.\mvnw.cmd -pl account-service spring-boot:run '-Dspring-boot.run.arguments=--eureka.client.enabled=false'
```

```powershell
# Terminal 2 : Transaction Service, Feign en URL directe
$env:ACCOUNT_SERVICE_URL = 'http://localhost:8081'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
.\mvnw.cmd -pl transaction-service spring-boot:run '-Dspring-boot.run.arguments=--eureka.client.enabled=false --spring.cloud.config.enabled=false'
```

Équivalent après packaging :

```powershell
java -jar .\transaction-service\target\transaction-service-0.0.1-SNAPSHOT.jar --eureka.client.enabled=false --spring.cloud.config.enabled=false
```

## Démonstration minimale

Dans un troisième terminal PowerShell :

```powershell
$account = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/accounts -ContentType application/json -Body '{"customerReference":"KAFKA-DEMO","currency":"EUR","balance":5000}'
$operationId = [guid]::NewGuid().ToString()
$body = @{ accountId = $account.id; amount = 250; currency = 'EUR'; merchantCategory = 'FOOD'; country = 'MA' } | ConvertTo-Json
$transaction = Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/transactions -ContentType application/json -Headers @{ 'Idempotency-Key' = $operationId } -Body $body
$transaction
Invoke-RestMethod "http://localhost:8081/api/accounts/$($account.id)/balance"
```

Résultats attendus : transaction créée, solde 4750 EUR, puis les deux logs ci-dessous
portant le même `eventId` et le même `transactionId`. Le log `Kafka published` est écrit
seulement après acquittement du broker ; l'ordre d'affichage des deux logs peut varier.

```text
Kafka published eventId=... transactionId=... topic=bank.transactions.created partition=0 offset=...
Kafka received eventId=... transactionId=... accountId=... amount=250.00 currency=EUR occurredAt=2026-10-30T11:00:00Z
```

Lecture indépendante des messages et de leur clé (Ctrl+C pour terminer) :

```powershell
docker compose -f docker-compose.kafka.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic bank.transactions.created --from-beginning --property print.key=true
```

Exemple du JSON publié (les UUID et la date varient) :

```json
{
  "eventId": "6d273ac8-0f5b-40cd-9bb2-e20138c99a2a",
  "eventType": "TransactionCreated",
  "transactionId": "d4fdfb74-b545-48b8-aa0a-819295d94481",
  "accountId": "197ba153-cc8d-4f58-a63d-a74651837619",
  "amount": 250.00,
  "currency": "EUR",
  "occurredAt": "2026-10-30T11:00:00Z"
}
```

Clé Kafka de cet exemple : `197ba153-cc8d-4f58-a63d-a74651837619`.

Arrêt :

```powershell
docker compose -f docker-compose.kafka.yml down
```

## Résultats réels du 30 octobre 2026

- Compilation et packaging : `BUILD SUCCESS` avec Java 21. Les sources des tests existants
  sont compilées, mais les tests JUnit ne sont pas exécutés, conformément au périmètre demandé.
- `docker compose ... config --quiet` : code de sortie 0 (configuration valide).
- Chargement de Transaction Service sans broker : `Started TransactionServiceApplication`
  observé sur le port de vérification 18082, groupe et désérialiseur corrects, puis arrêt du
  processus de vérification. KafkaAdmin signale un timeout et le consumer des connexions
  refusées sur 9092 : ce démarrage ne valide pas Kafka.
- `docker compose ... up -d` : échec, moteur Docker indisponible (`docker_engine` introuvable).
  Aucun broker n'a été démarré. Publication, réception et création d'une transaction avec
  Kafka ne sont donc pas validées réellement ; utiliser la démonstration ci-dessus.

## Limites

La publication après commit ne garantit pas une livraison parfaite : un arrêt du processus
entre commit et envoi ou un échec Kafka peut perdre un événement. L'échec est journalisé
sans annuler ni faire échouer artificiellement une opération bancaire déjà commitée.
Le rejeu HTTP d'une opération `COMPLETED` ne répare pas une publication perdue.
Un Transactional Outbox persistant, écrit avec les transactions et relayé avec reprise,
serait nécessaire pour fermer cette fenêtre. L'idempotence du producer Kafka ne remplace
pas cet outbox. Des doublons peuvent aussi être reçus après redémarrage ou rejeu des offsets.

Le consumer n'a aucun effet métier et peut journaliser à nouveau un événement. Le broker
unique en PLAINTEXT et les bases H2 en mémoire existantes conviennent à la démonstration,
sans garantie de conservation au redémarrage. Aucun Compose complet de la plateforme n'est ajouté.
Les tests bancaires existants désactivent les connexions Kafka et remplacent le publisher
dans les tests REST ; aucune nouvelle suite de tests n'est créée.

## Fichiers

Créés : `docker-compose.kafka.yml`, `docs/kafka.md`, et dans
`transaction-service/src/main/java/ma/enset/transactionservice/messaging/` :
`TransactionCreatedEvent.java`, `TransactionEventPublisher.java`,
`TransactionEventConsumer.java`, `KafkaConfiguration.java`.

Modifiés : `README.md`, `transaction-service/pom.xml`,
`transaction-service/src/main/java/ma/enset/transactionservice/service/OperationStore.java`,
`transaction-service/src/main/resources/application.properties`,
`transaction-service/src/test/resources/application.properties`,
`transaction-service/src/test/java/ma/enset/transactionservice/TransactionRestIntegrationTests.java`.

Références : [sérialisation Spring Kafka](https://docs.spring.io/spring-kafka/reference/3.3-SNAPSHOT/kafka/serdes.html),
[image Docker officielle Kafka 3.9.1](https://kafka.apache.org/39/getting-started/docker/).

Commit local : `feat(kafka): publish and consume banking transaction events`. Aucun push.
