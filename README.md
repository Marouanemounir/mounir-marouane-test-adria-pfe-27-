# ADRIA ENSET PFE 2027 — Etape 5 : Communication asynchrone Kafka

L'étape 5 publie les transactions bancaires confirmées sur `bank.transactions.created`
après commit et les journalise dans le groupe `adria-transaction-logging`.
Voir [la démonstration Kafka et les résultats réels](docs/kafka.md).

L'étape 4 ajoute Eureka (8761), Config Server native (8888) et Gateway WebFlux (8080).
Account et Transaction s'enregistrent dans Eureka ; Transaction charge le plafond partagé depuis Config.
Consulter [la documentation de l'étape 4](docs/cloud-services.md) pour les commandes de démarrage,
les URLs, les fichiers modifiés et les résultats réels.
Vérification : `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-cloud-services.ps1`.

Les informations suivantes décrivent les étapes métier précédentes, conservées.

L'étape 3 ajoute Transaction Service (8082), OpenFeign, Resilience4j, les débits idempotents
et les virements atomiques dans Account Service (8081).
Voir [la documentation complète de l'étape 3](docs/transaction-service.md) pour les fichiers,
les commandes, les exemples HTTP, la cohérence distribuée, les tests et les limites.
Importer [la collection Postman](docs/transaction-service.postman_collection.json) ou exécuter
`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-transaction-service.ps1`.

La documentation ci-dessous décrit l'étape 2 conservée et ses vérifications initiales.

Le POM racine est un agregateur Maven `pom` et le parent local des six modules.
Java 21, Spring Boot 3.5.16 et le BOM Spring Cloud 2025.0.3 sont centralises dans ce parent.
Les quatre services des étapes suivantes conservent leurs applications et tests de démarrage existants.
Account Service conserve ses contrats REST ; les ajouts internes sont documentés dans l'étape 3.

Versions verifiees sur Maven Central et dans les sources officielles :

- [Spring Boot 3.5 : prerequis Java et Maven](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Compatibilite Spring Cloud 2025.0.x / Spring Boot 3.5.x](https://spring.io/projects/spring-cloud/)
- [Publication Spring Cloud 2025.0.3](https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2025.0-Release-Notes)

## Compilation et tests depuis la racine

```powershell
.\mvnw.cmd -pl account-service -am clean test
.\mvnw.cmd -pl account-service -am package -DskipTests
.\mvnw.cmd clean verify
```

Le Wrapper utilise Maven 3.10.0. Aucun Maven global n'est necessaire.
Si `mvn` est disponible, il peut remplacer `.\mvnw.cmd`.

Resultats reellement executes le 30 octobre 2026 avec Java 21.0.12.1 :

| Verification | Resultat |
| --- | --- |
| `-pl account-service -am clean test` | BUILD SUCCESS, 42 tests, 0 echec, 0 erreur, 0 ignore |
| `-pl account-service -am package -DskipTests` | BUILD SUCCESS, JAR executable produit, tests ignores comme demande |
| `clean verify` sur tout le reacteur | BUILD SUCCESS, 47 tests (42 Account + 5 tests existants), les six modules SUCCESS |
| Script REST contre le JAR sur le port 8081 | Succes : 5000 / 4750 / 5250 EUR, 409 apres blocage, 404 compte absent, health UP, H2 HTTP 200 |

La suite Account Service utilise Spring Boot, MockMvc et H2. Elle couvre aussi le credit bloque,
les montants internes superieurs a 1000, les erreurs de format HTTP, les calculs decimaux,
le debordement du solde et deux scenarios concurrents (credits sans perte et debits sans decouvert).
Les rapports JUnit se trouvent dans `account-service/target/surefire-reports/` et dans le dossier
`target/surefire-reports/` de chaque autre module.
Le processus lance pour la verification REST est arrete apres les controles.

## Lancement

PowerShell, depuis la racine :

```powershell
.\mvnw.cmd -pl account-service spring-boot:run
```

Ou apres le packaging :

```powershell
java -jar .\account-service\target\account-service-0.0.1-SNAPSHOT.jar
```

IntelliJ IDEA : importer/recharger le `pom.xml` racine comme projet Maven,
choisir le SDK Java 21, puis executer `main` dans
`ma.enset.accountservice.AccountServiceApplication`, avec le classpath du module `account-service`.
Equivalent dans une configuration Application : main class identique, module `account-service`, JRE 21.
Aucun Eureka, Config Server ou autre service n'est requis.

## API REST

Base : `http://localhost:8081`.

| Methode | Chemin | Corps / parametre | Succes |
| --- | --- | --- | --- |
| POST | `/api/accounts` | `{"customerReference":"TEST-001","currency":"EUR","balance":5000}` | 201 + Location |
| GET | `/api/accounts` | | 200 |
| GET | `/api/accounts/{id}` | | 200 |
| GET | `/api/accounts/customer/{reference}` | | 200, liste eventuellement vide |
| GET | `/api/accounts/{id}/balance` | | 200, `{"id":"...","balance":5000}` |
| PATCH | `/api/accounts/{id}/debit` | `{"amount":250}` | 200 |
| PATCH | `/api/accounts/{id}/credit` | `{"amount":500}` | 200 |
| PATCH | `/api/accounts/{id}/status?status=BLOCKED` | statut ACTIVE ou BLOCKED | 200 |

La creation accepte aussi `status`; son absence donne ACTIVE.
Les devises sont des codes ISO 4217 de trois lettres majuscules reconnus par Java.
Les requetes invalides retournent 400, les comptes absents 404,
les comptes bloques, les soldes insuffisants et les conflits de concurrence 409.
Les erreurs utilisent `ProblemDetail` ; la validation ajoute une liste `errors`.

Les regles metier et transactions sont dans `AccountService`.
Les calculs intermediaires utilisent `BigDecimal`; le champ persiste reste un `double`,
conformement au sujet, avec les limites de precision de ce type.
Debit, credit et modification du statut prennent un verrou JPA `PESSIMISTIC_WRITE`
sur le compte jusqu'a la fin de la transaction.
Aucun plafond de 1000 n'est applique aux operations internes.

## Verification HTTP automatisee

Lancer Account Service, puis dans un second terminal PowerShell :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-account-service.ps1
```

Le script utilise `Invoke-RestMethod`, cree une reference fictive unique et affiche l'UUID.
Il verifie successivement 5000 EUR, le debit de 250 (4750), le credit de 500 (5250),
le blocage, le rejet d'un debit avec 409, l'absence de modification du solde,
un compte inconnu avec 404, la sante UP et l'acces HTTP a la console H2.
Une verification incorrecte termine le script en erreur.

## H2 et Actuator

- Sante : `http://localhost:8081/actuator/health`
- Console locale : `http://localhost:8081/h2-console`
- JDBC : `jdbc:h2:mem:accountdb`
- Utilisateur : `sa`, mot de passe vide.

La base H2 est en memoire et le schema est recree a chaque demarrage (`create-drop`).
Les tests utilisent une base H2 distincte `account-tests`.

## Perimetre

Les packages `web.graphql` et `mcp` sont reserves aux etapes correspondantes et ne contiennent
aucune implementation a cette etape. Les integrations Cloud, Kafka, IA et l'interface Angular
restent hors perimetre. Transaction Service est maintenant implémenté à l'étape 3.

Message de commit propose :

```text
feat(account): implement account REST API with JPA H2 and integration tests
```
