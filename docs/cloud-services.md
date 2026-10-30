# Étape 4 — Eureka, Config Server et Gateway

Java 21, Spring Boot 3.5.16 et BOM Spring Cloud 2025.0.3 conservés.
Aucune règle bancaire n'est modifiée. Aucun Kafka, GraphQL, MCP, Spring AI, Docker ou Keycloak ajouté.

## Architecture et configuration

| Service | Port | Rôle |
|---|---|---|
| discovery-service | 8761 | Eureka Server, sans auto-enregistrement ni lecture de son propre registre |
| config-service | 8888 | Config Server, profil native, fichiers locaux embarqués |
| gateway-service | 8080 | Gateway réactive WebFlux, client Eureka, routes LoadBalancer |
| account-service | 8081 | Métier existant, désormais client Eureka |
| transaction-service | 8082 | Métier existant, client Eureka et Config Client |

Les trois clients s'enregistrent avec leur nom logique et une instance locale sur localhost.
Le registre est relu toutes les 5 secondes pour faciliter la démonstration locale.
La première réplication d'instance est également programmée après 5 secondes.
Ces réglages locaux peuvent être ajustés avant un déploiement distribué.

Eureka : `eureka.client.service-url.defaultZone=${EUREKA_URL:http://localhost:8761/eureka/}`.
Pas d'annotation EnableDiscoveryClient nécessaire : le starter client active l'intégration.

Config Server utilise `classpath:/config-repo/`. Le fichier partagé
`config-service/src/main/resources/config-repo/application.properties` contient exactement :

```properties
bank.transaction.max-amount=1000
```

Pour des fichiers locaux modifiables sans reconstruire le JAR, définir par exemple :

```powershell
$env:CONFIG_REPOSITORY='file:///C:/chemin/config-repo/'
```

Transaction importe `optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}`.
Sa propriété locale `bank.transaction.max-amount=1000` est le secours lorsque Config Server est absent.
La configuration distante importée prend priorité sur cette valeur locale.
La classe TransactionProperties et les règles de TransactionService sont conservées.
Aucun rafraîchissement dynamique n'est ajouté : redémarrer Transaction pour charger une modification.

Gateway emploie le starter `spring-cloud-starter-gateway-server-webflux` et les propriétés
`spring.cloud.gateway.server.webflux.routes` de Gateway 4.3, gérées par le BOM existant :

| Prédicat Path | Destination |
|---|---|
| /api/accounts, /api/accounts/** | lb://account-service |
| /api/transactions, /api/transactions/** | lb://transaction-service |

Aucun StripPrefix ni réécriture : les contrôleurs reçoivent les chemins d'origine.
Il n'y a pas de découverte automatique de routes supplémentaires.

Références officielles :
[starter Gateway 4.3](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/starter.html),
[OpenFeign et Spring Cloud LoadBalancer](https://docs.spring.io/spring-cloud-openfeign/docs/current/reference/html/index.html).

## OpenFeign et préservation bancaire

La valeur vide par défaut de `bank.services.account-url` sélectionne Eureka avec le nom account-service.
Le transport OkHttp existant est enveloppé dans FeignBlockingLoadBalancerClient.
Une URL explicite fait déballer ce wrapper par OpenFeign et conserve le mode HTTP direct.

Feign utilise toujours NEVER_RETRY, OkHttp conserve retryOnConnectionFailure(false), et
`spring.cloud.loadbalancer.retry.enabled=false` désactive les retries du LoadBalancer.
Les chemins REST, reçus d'opération, clés d'idempotence, verrous JPA et Resilience4j sont conservés.
Les tests métier existants désactivent Eureka/Config afin de rester isolés des serveurs techniques.

## Compilation et démarrage

Depuis la racine, PowerShell, JDK 21 :

```powershell
.\mvnw.cmd -DskipTests clean package
```

Lancer dans cinq terminaux distincts, dans cet ordre ; attendre Eureka et Config avant les clients :

```powershell
# Terminal 1
java -jar .\discovery-service\target\discovery-service-0.0.1-SNAPSHOT.jar

# Terminal 2
java -jar .\config-service\target\config-service-0.0.1-SNAPSHOT.jar

# Terminal 3
java -jar .\account-service\target\account-service-0.0.1-SNAPSHOT.jar

# Terminal 4
java -jar .\transaction-service\target\transaction-service-0.0.1-SNAPSHOT.jar

# Terminal 5
java -jar .\gateway-service\target\gateway-service-0.0.1-SNAPSHOT.jar
```

Autre possibilité : `.\mvnw.cmd -pl <module> spring-boot:run` pour chaque module.
Dans l'environnement de l'agent, MAVEN_OPTS a reçu `-Duser.home=C:\Users\marwa`
pour réutiliser le cache Maven utilisateur ; ce réglage ne fait pas partie de la configuration du projet.

Démarrage local direct de Transaction sans Eureka, avec Config Server facultatif :

```powershell
java -jar .\transaction-service\target\transaction-service-0.0.1-SNAPSHOT.jar `
    --eureka.client.enabled=false --bank.services.account-url=http://localhost:8081
```

Account peut lui aussi démarrer sans tentative d'enregistrement :

```powershell
java -jar .\account-service\target\account-service-0.0.1-SNAPSHOT.jar --eureka.client.enabled=false
```

L'URL directe peut aussi être fournie avec `ACCOUNT_SERVICE_URL=http://localhost:8081`.
Les URLs Eureka et Config peuvent être remplacées par EUREKA_URL et CONFIG_SERVER_URL.
L'indisponibilité de Config produit un avertissement, puis utilise la valeur de secours ; aucun fail-fast configuré.

## Vérifications reproductibles

| URL | Vérification |
|---|---|
| http://localhost:8761/ | Dashboard avec ACCOUNT-SERVICE, TRANSACTION-SERVICE et GATEWAY-SERVICE |
| http://localhost:8761/eureka/apps | Registre ; en-tête Accept: application/json |
| http://localhost:8888/transaction-service/default | propertySources avec bank.transaction.max-amount=1000 |
| http://localhost:8080/api/accounts | GET routé vers Account, HTTP 200 |
| http://localhost:8080/api/transactions | GET routé vers Transaction, HTTP 200 |
| http://localhost:8761/actuator/health | Santé Discovery |
| http://localhost:8888/actuator/health | Santé Config |
| http://localhost:8080/actuator/health | Santé Gateway |
| http://localhost:8081/actuator/health | Santé Account |
| http://localhost:8082/actuator/health | Santé Transaction |

Attendre quelques secondes après l'enregistrement pour la propagation des registres clients.
Le script de vérification ne modifie aucune donnée bancaire :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-cloud-services.ps1
```

Il vérifie les cinq health checks, le dashboard, le plafond partagé, les trois instances UP
et les deux routes GET. Il affiche PASS ou FAIL et retourne 0 ou 1.

## Fichiers créés ou modifiés

```text
README.md
docs/cloud-services.md                                      nouveau
scripts/test-cloud-services.ps1                            nouveau
discovery-service/
  pom.xml
  src/main/java/ma/enset/discoveryservice/DiscoveryServiceApplication.java
  src/main/resources/application.properties
config-service/
  pom.xml
  src/main/java/ma/enset/configservice/ConfigServiceApplication.java
  src/main/resources/application.properties
  src/main/resources/config-repo/application.properties     nouveau, une propriété exactement
gateway-service/
  pom.xml
  src/main/resources/application.properties
account-service/
  pom.xml
  src/main/resources/application.properties
  src/test/resources/application.properties
transaction-service/
  pom.xml
  src/main/java/ma/enset/transactionservice/config/AccountFeignConfiguration.java
  src/main/java/ma/enset/transactionservice/integration/AccountClient.java
  src/main/resources/application.properties
  src/test/resources/application.properties
```

Le pom.xml racine a été inspecté et reste inchangé. Aucun service métier n'a été recréé.

## Résultats réels

Vérifications effectuées le 30 octobre 2026 avec les JAR réellement démarrés :

| Contrôle | Résultat |
|---|---|
| `-DskipTests clean package` | BUILD SUCCESS, six modules SUCCESS, 29,027 s |
| Versions effectives | Gateway 4.3.5, Eureka Client et LoadBalancer 4.3.3, résolus par le BOM inchangé |
| Démarrage des cinq services | Health UP sur 8761, 8888, 8081, 8082 et 8080 |
| Dashboard Eureka | HTTP 200 ; ACCOUNT-SERVICE, TRANSACTION-SERVICE et GATEWAY-SERVICE enregistrés UP |
| Config native | /transaction-service/default fournit le plafond partagé 1000 |
| Origine du plafond dans Transaction | configserver:classpath:/config-repo/application.properties, valeur 1000 |
| Gateway | GET /api/accounts et GET /api/transactions : HTTP 200 |
| Feign par Eureka | Achat fictif de 250 via Gateway : solde 5000 vers 4750 ; répétition avec même clé sans second débit |
| Config Server arrêté | Transaction redémarre UP et charge le secours local 1000 |
| Feign URL directe | Sans Eureka ni Config Server, achat fictif de 10 : solde 5000 vers 4990 ; répétition sans second débit |
| Script minimal | 13 PASS, exit 0 |

L'origine de configuration a été inspectée via un endpoint Actuator env activé uniquement par arguments
du processus de démonstration. Cet endpoint n'est pas exposé dans la configuration livrée.
Les tests JUnit n'ont pas été exécutés, conformément au périmètre minimal demandé.

Aucune erreur bloquante de compilation ou de démarrage. Avertissements non bloquants observés :
cache LoadBalancer par défaut, absence de pairs pour le serveur Eureka autonome, résolution du hostname
local par InetUtils. Le refus de connexion à Config pendant le test de secours est attendu.

Les journaux sont dans `target/cloud-verification/`, ignoré par Git :
`cloud-package.log`, `smoke-checks.log`, `config-origin.json`, `feign-discovery.log`,
`fallback-origin.json`, `fallback-checks.log` et les logs des cinq processus.
Les processus lancés pour cette vérification sont arrêtés après les contrôles.

Commit local demandé :

```text
feat(cloud): configure eureka config server and api gateway
```

Aucun push GitHub n'est effectué pendant cette étape.
