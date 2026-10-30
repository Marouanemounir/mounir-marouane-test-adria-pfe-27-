# ADRIA Banking Platform — Partie G

Frontend Angular standalone, TypeScript strict, Router, HttpClient, Reactive Forms,
RxJS, Tailwind CSS 4 et icônes SVG locales. Aucun changement du backend.

## Lancement

Prérequis vérifié : Node **24.19.0**, npm **11.17.0**. Angular installé : **21.2.25**,
CLI/build **21.2.29**, TypeScript **5.9.3**. Angular 21 est une version stable compatible
avec Node 24 ([matrice officielle](https://angular.dev/reference/versions)).

```powershell
cd banking-web
npm.cmd install
# Uniquement pour le chat : même jeton que le service IA, jamais une clé LLM.
$env:AGENT_AUTH_TOKEN = '<jeton du service agent>'
npm.cmd start
```

Application : **http://127.0.0.1:4200/** (également accessible via localhost).
`npm.cmd` évite le blocage de `npm.ps1` par la politique PowerShell de ce poste.
Sur les autres shells, `npm install`, `npm start` et `npm run build` conviennent.
Le serveur de développement reste lié à l’interface locale.

## Contrats REST inspectés

Sources : `AccountRestController`, `TransactionRestController`, `BankingAgentController`,
leurs DTO, les DTO d’opérations, `GlobalExceptionHandler`, `AgentAccessFilter`,
`McpAccessFilter`, les services métier et les configurations application/Gateway.

| Action | Méthode et chemin exact | Payload / paramètre |
| --- | --- | --- |
| Comptes | GET `/api/accounts` | Liste de `AccountResponse` |
| Détails | GET `/api/accounts/{id}` | `AccountResponse` |
| Solde | GET `/api/accounts/{id}/balance` | `{ id, balance }` ; méthode disponible dans le service Angular |
| Création compte | POST `/api/accounts` | `{ customerReference, status, currency, balance }` |
| Bloquer/débloquer | PATCH `/api/accounts/{id}/status?status=BLOCKED` ou `ACTIVE` | Corps `null` |
| Transactions | GET `/api/transactions` | Liste de `TransactionResponse` |
| Historique | GET `/api/transactions/account/{accountId}` | Liste de `TransactionResponse` |
| Débit | POST `/api/transactions` | `{ accountId, amount, currency, merchantCategory, country }` |
| Virement | POST `/api/transactions/transfers` | `{ sourceAccountId, destinationAccountId, amount, currency }` |
| État opération | GET `/api/transactions/operations/{id}` | Méthode disponible dans le service Angular |
| Chat | POST `/api/chat` | `{ message }` → `{ answer, toolsUsed }` |

Les POST financiers exigent un header **`Idempotency-Key` UUID**.
`AccountResponse` contient `id`, `customerReference`, `status`, `currency`, `balance`.
`TransactionResponse` contient `id`, `accountId`, `amount`, `currency`,
`merchantCategory`, `country`, `createdAt`, `operationId`, `transferId`, `direction`.
Le virement retourne `transferId`, les deux comptes, le montant, la devise,
`sourceBalance`, `destinationBalance`, `debitTransactionId`, `creditTransactionId`.

## Gateway, CORS et secrets

`src/environments/environment.ts` contient uniquement des URLs publiques relatives.
Le proxy `proxy.conf.cjs` s’exécute dans Node :

- `/api/accounts` et `/api/transactions` → **http://localhost:8080**, avec leurs sous-chemins ;
- `/api/chat` → **http://localhost:8083**, car aucune route IA n’existe dans Gateway ;
- le header `Authorization: Bearer …` du chat est injecté par le proxy à partir de
  `AGENT_AUTH_TOKEN`, ou `MCP_AUTH_TOKEN` en repli. Il n’est jamais embarqué dans Angular.

Les cibles peuvent être remplacées par `GATEWAY_URL` et `AGENT_URL` dans le terminal.
Les fichiers `.env` ne sont pas chargés automatiquement. Voir `.env.example`.
Le backend n’expose aucune configuration CORS explicite ; les appels same-origin via
le proxy évitent de modifier le backend. Les REST bancaires ne sont pas protégés par
les filtres MCP, qui ne couvrent que `/mcp`. Le filtre agent protège `/api/chat` :
jeton non configuré → 503, jeton invalide → 401.

Pour une livraison en production, servir `dist/banking-web/browser/` derrière un
reverse proxy/BFF authentifié qui route ces mêmes URLs et conserve le jeton agent
côté serveur. Le proxy CLI est destiné au développement local uniquement ; le
bundle statique ne fournit pas de proxy ni d’authentification utilisateur.
Prévoir le fallback SPA vers `index.html` pour les routes Angular.

## Configurer réellement l’IA

Dans le terminal **backend** qui démarre l’agent : définir `MCP_AUTH_TOKEN` identique
à celui des services Account et Transaction, `AGENT_AUTH_TOKEN` (ou son repli MCP),
`OPENAI_API_KEY`, `OPENAI_MODEL` et éventuellement `OPENAI_BASE_URL`, puis activer le
profil `llm`. Voir [le guide backend existant](../docs/step6.md) et
[les variables backend](../.env.example).

```powershell
# Après définition des variables dans ce terminal backend :
java -jar ai-agent-service/target/ai-agent-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=llm
```

Ne définir **aucune clé LLM dans Angular**, dans `environment.ts` ou dans le terminal
frontend. Les secrets du fournisseur restent dans le processus backend.
L’agent est configuré en **lecture seule** et consulte les outils MCP. Sans profil
`llm`, le backend retourne 503 ; une défaillance du fournisseur/MCP retourne 502.
Le chat montre ces erreurs et ne crée aucune réponse artificielle. Le DTO n’offre
pas de `conversationId` : l’historique affiché reste local au composant ; seul le
message courant est envoyé. Le texte est affiché par interpolation Angular,
sans HTML injecté.

## Composants et comportement

- `AppComponent` : sidebar, navigation responsive, header, lien d’évitement et shell.
- `DashboardComponent` : comptes, nombre de transactions, transactions récentes triées,
  soldes agrégés **séparément par devise**. Une donnée indisponible apparaît « — ».
- `AccountsComponent` : création avec validations, détails récupérés depuis l’API,
  solde, statut et blocage/déblocage. Lien vers l’historique du compte.
- `TransactionsComponent` : formulaire de débit, liste et filtre d’historique backend.
  Une nouvelle sélection annule la précédente requête d’historique.
- `TransfersComponent` : comptes distincts, formulaire, reçu et soldes confirmés par API.
- `AssistantComponent` : chat, suggestions de saisie, chargement, erreurs et outils utilisés.
- `TransactionTableComponent`, `IconComponent` : éléments partagés.
- `BankingStore` : données et états HTTP partagés ; rafraîchit comptes et transactions
  après une opération confirmée. Aucun jeu de données de secours.
- `AccountApiService`, `TransactionApiService`, `BankingAgentApiService` : HttpClient
  et contrats typés ; affichage des `ProblemDetail`, `errors` et `operationId`.
- `PendingOperationService` : payload et clé par type d’opération conservés en
  `sessionStorage`, avec repli mémoire. Un retry identique garde sa clé après
  navigation/rechargement. Aucune relance financière automatique.

Montant frontend : 0,01 à 1 000, deux décimales maximum ; codes devise de trois
majuscules. Le backend décide de la validité métier, de la devise ISO, du statut et
du solde disponible. La confirmation UI attend toujours une réponse HTTP réussie.

En cas de résultat ambigu (réseau, concurrence ou 5xx), les champs financiers
restent verrouillés et le bouton relance le même payload avec la même clé. Après
un rejet explicite 400 ou un rejet métier connu (compte bloqué, devise incompatible,
solde insuffisant), les champs sont modifiables : une correction du payload démarre
une nouvelle opération ; une relance sans correction conserve la clé. La permission
de modifier est retirée avant chaque nouvel envoi. Une nouvelle opération indépendante
ne peut pas remplacer une opération dont le résultat reste ambigu. La session du
navigateur n’est pas un registre durable : ne pas effacer son stockage tant qu’une
opération reste à vérifier. Le backend expose aussi des endpoints d’état/réconciliation.

## Arborescence

```text
banking-web/
├── package.json / package-lock.json
├── angular.json
├── tsconfig.json / tsconfig.app.json
├── .postcssrc.json
├── proxy.conf.cjs
├── .env.example / .gitignore
├── README.md
├── public/favicon.svg
└── src/
    ├── index.html / main.ts / styles.css
    ├── environments/environment.ts
    └── app/
        ├── app.component.ts / app.routes.ts
        ├── core/
        │   ├── models.ts
        │   ├── api.services.ts
        │   ├── banking.store.ts
        │   └── pending-operation.service.ts
        ├── shared/
        │   ├── icon.component.ts
        │   └── transaction-table.component.ts
        └── pages/
            ├── dashboard.component.ts
            ├── accounts.component.ts
            ├── transactions.component.ts
            ├── transfers.component.ts
            └── assistant.component.ts
```

## Vérifications réelles — 30 octobre 2026

- `npm.cmd install --no-audit --no-fund` : réussi, 418 packages installés. npm a
  signalé des scripts d’installation non approuvés ; ils n’ont pas empêché le build.
- `npm.cmd run build` : réussi, compilation Angular/TypeScript stricte sans erreur.
  Bundle initial : **302,39 kB**, transfert estimé **83,95 kB** (avant compression serveur).
- `npm.cmd start` : compilation de développement réussie, serveur sur `127.0.0.1:4200`.
- Vérification ciblée exécutée sur le code du gestionnaire d’idempotence : clé
  stable, contenu immuable après échec ambigu, persistance après recréation du
  service, correction après rejet explicite, reverrouillage et nettoyage après
  confirmation : **PASS**. Cette vérification isolée ne prouve pas le comportement
  distribué du backend.
- GET `/`, `/dashboard`, `/accounts`, `/transactions`, `/transfers`, `/assistant` : HTTP 200.
  Ceci vérifie la livraison SPA, pas une exécution navigateur des interactions.
- Ports 8080/8081/8082/8083 : fermés. GET via proxy `/api/accounts` et
  `/api/transactions` : HTTP 500, traité comme service indisponible par le frontend.
- Contrôle visuel navigateur : non réalisé ; hôte d’accès navigateur indisponible,
  puis fenêtre Chrome minimisée inaccessible à l’outil.
- Non testés de bout en bout : liste backend réussie, création de compte,
  blocage/déblocage, débit, virement, retries financiers et vraie réponse LLM.
  Aucun succès backend ou IA n’est simulé.

Le CSV fourni et la partie Python/ML n’ont pas été utilisés ni modifiés.
