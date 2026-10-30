# Partie H ADRIA — Détection de fraude bancaire

Pipeline Python de démonstration : régression logistique pondérée, prétraitement sans fuite et évaluation adaptée aux classes déséquilibrées. Aucun microservice Java ni code Angular n'est modifié.

## Installation et exécution

Python 3.12 est utilisé pour la vérification. Depuis la racine du dépôt, sous PowerShell :

```powershell
cd fraud-detection
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python fraud_detection.py --data data/bank_transactions.csv
```

Si l'activation PowerShell n'est pas disponible, utiliser directement `.\.venv\Scripts\python.exe` à la place de `python` pour les deux dernières commandes. Les dépendances ne sont installées que dans cet environnement local.

Les sorties sont écrasées lors d'une nouvelle exécution. Par défaut, elles sont placées à côté du script ; `--output-dir autre-dossier` permet de changer leur destination. Le CSV est en lecture seule.

## Origine des données

Le fichier `data/bank_transactions.csv` est une copie exacte du `bank_transactions.csv` fourni à la racine du projet par l'utilisateur. Son empreinte SHA-256 est :

```text
4c74c38b646e10303f52e32673a4a2882a5d3c964e29b3c446866570f7124c24
```

Aucun dataset synthétique supplémentaire n'a été généré, puisque le fichier attendu était présent. L'origine réelle ou synthétique du CSV fourni n'est pas documentée : les scores ci-dessous décrivent exclusivement cet échantillon et ne constituent pas une mesure de performance bancaire en production. Si ce fichier est synthétique, ses performances reflètent aussi les règles de son générateur.

Les colonnes attendues sont `transaction_id`, `account_id`, `amount`, `hour`, `country`, `merchant_category`, `transactions_last_24h`, `average_amount_last_7d` et `is_fraud`.

## Méthode

Le script affiche les premières lignes, les dimensions, les types, les valeurs manquantes, les classes, leurs effectifs et le pourcentage de fraudes. Il vérifie les colonnes, une cible binaire sans valeur manquante, la présence des deux classes et des effectifs compatibles avec la stratification. Une séparation qui perd une classe est rejetée explicitement.

Les variables explicatives sont exclusivement :

- Numériques : `amount`, `hour`, `transactions_last_24h`, `average_amount_last_7d` ; imputation par médiane puis `StandardScaler`.
- Catégorielles : `country`, `merchant_category` ; imputation par modalité majoritaire puis `OneHotEncoder(handle_unknown="ignore")`.

`transaction_id`, `account_id` et `is_fraud` sont exclus des variables explicatives. Un `ColumnTransformer` réunit les deux traitements dans un `Pipeline` avec `LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42)`.

La séparation est stratifiée, avec 80 % de train, 20 % de test et `random_state=42`. L'imputation, la normalisation, l'encodage et le modèle sont ajustés uniquement sur le train. Les poids équilibrés donnent davantage d'importance à la classe minoritaire sans rééchantillonnage.

Le seuil de décision est **fixé à 0,5 avant toute évaluation**. Le test sert exclusivement au calcul des métriques finales ; il ne sélectionne ni le seuil ni le modèle. L'average precision utilise `predict_proba`, et non les prédictions binaires. Les probabilités d'une régression pondérée ne sont pas nécessairement calibrées à la prévalence réelle.

## Résultats de l'exécution

Les résultats vérifiés sont consignés dans `results/metrics.json` et `results/classification_report.txt`. Le JSON inclut également les effectifs train/test, le seuil, l'empreinte du CSV et les versions logicielles.

Le CSV contient **5 000 transactions, dont 257 fraudes (5,14 %)**. Les valeurs manquantes concernent `country` (66), `merchant_category` (84) et `average_amount_last_7d` (75). Le train contient 4 000 transactions, dont 206 fraudes ; le test en contient 1 000, dont 51 fraudes.

| Mesure sur le test, seuil 0,5 | Valeur |
|---|---:|
| Précision fraude | 0,122137 |
| Rappel fraude | 0,627451 |
| F1 fraude | 0,204473 |
| Average precision | 0,188605 |

Matrice de confusion : lignes = classes réelles, colonnes = classes prédites, ordre `[0, 1]`.

```text
                  Prédit légitime   Prédit fraude
Réel légitime           719              230
Réel fraude              19               32
```

Le modèle détecte 32 des 51 fraudes et en manque 19. Il déclenche aussi 230 fausses alertes : environ 87,8 % des alertes sont donc injustifiées sur ce test. La précision est faible et ce résultat ne justifie pas un déploiement. L'average precision dépasse la prévalence de fraude du test (0,051), mais ce seul écart ne valide pas l'utilité économique du modèle. L'accuracy est de 75,1 %, contre 94,9 % pour une règle prédisant toujours « légitime » sur ce test ; cette règle aurait toutefois un rappel fraude nul.

Les dépendances principales et SciPy sont épinglées dans `requirements.txt`. Le script limite par défaut les threads OpenBLAS et OpenMP à un, avant leurs imports, pour ce petit jeu de données ; une configuration utilisateur explicite est respectée.

Vérification effectuée avec Python 3.12.10 : la commande demandée termine avec le code 0, les trois PNG sont valides et le pipeline enregistré se recharge et reproduit les métriques. Un contrôle ponctuel vérifie que les médianes d'imputation correspondent uniquement au train et que l'inférence accepte des valeurs manquantes et une catégorie inconnue. `pip check` ne signale aucune dépendance incohérente.

## Réponses aux quatre questions ADRIA

### 1. Pourquoi l'accuracy seule est-elle insuffisante ?

Les transactions légitimes sont majoritaires. Avec 1 % de fraudes, prédire toujours « légitime » donne 99 % d'accuracy et un rappel fraude nul. Il faut examiner la précision fraude (part des alertes réellement frauduleuses), le rappel fraude (part des fraudes détectées), le F1, les faux positifs et faux négatifs, ainsi que l'average precision. Cette dernière résume la courbe Precision-Recall sur différents seuils ; sa référence pour un classement aléatoire correspond à la prévalence de fraude.

### 2. Quel compromis existe entre faux positifs et fraudes non détectées ?

Un faux positif déclenche une vérification inutile, peut gêner un client et consomme la capacité d'investigation. Un faux négatif laisse passer une fraude et expose à une perte financière. Le choix opérationnel dépend des montants, des coûts, de la capacité de revue et du niveau de risque accepté. Un bon F1 ne suffit donc pas à déterminer le seuil économique optimal.

### 3. Quel effet possible a une diminution du seuil de décision ?

À scores constants, diminuer le seuil augmente ou conserve le nombre d'alertes et le rappel, et augmente ou conserve les faux positifs. Cela réduit ou conserve les fraudes non détectées. La précision peut diminuer ; son évolution n'est pas mathématiquement monotone. Le F1 peut augmenter ou diminuer. Un seuil à adapter devrait être choisi sur une validation séparée issue du train, idéalement temporelle, selon les coûts métier, puis figé avant le test final.

### 4. Pourquoi privilégier une validation temporelle pour des données bancaires ?

En production, le passé sert à prédire le futur. Une séparation chronologique reproduit cette contrainte, révèle la dérive des comportements et limite le transfert d'informations du futur vers le passé. Un split aléatoire peut mélanger des transactions proches et des comptes présents dans les deux jeux, ce qui rend l'évaluation trop favorable. Le CSV contient une heure de journée, mais pas de date complète : une véritable validation temporelle n'est donc pas réalisable avec ce schéma seul.

## Fuite de données et limites

Le pipeline évite d'apprendre les statistiques de prétraitement sur le test. Les agrégats `transactions_last_24h` et `average_amount_last_7d` doivent toutefois être calculés uniquement avec les événements disponibles avant la décision. Le CSV ne permet pas de vérifier leur construction ; des agrégats calculés avec des événements futurs créeraient une fuite même avec un pipeline correct.

La suppression de `account_id` évite son utilisation directe, mais ne garantit pas l'indépendance des transactions d'un même compte entre train et test. Selon l'objectif, une validation par compte en complément de la validation temporelle peut être nécessaire. Un petit nombre de fraudes rend les métriques sensibles à quelques transactions. Ce test unique, sans validation externe, estimation d'incertitude, calibration ni étude des coûts, est une démonstration technique. Aucun réglage n'a été effectué après lecture des résultats du test.

## Livrables

```text
fraud-detection/
├── .gitignore
├── fraud_detection.py
├── requirements.txt
├── README.md
├── data/
│   └── bank_transactions.csv
├── models/
│   └── fraud_pipeline.joblib
└── results/
    ├── class_distribution.png
    ├── confusion_matrix.png
    ├── precision_recall_curve.png
    ├── classification_report.txt
    └── metrics.json
```

Le fichier `models/fraud_pipeline.joblib` contient le prétraitement ajusté et le classifieur. Pour l'inférence, le charger avec `joblib.load(...)`, puis appeler `predict_proba` sur les six variables explicatives et appliquer le seuil documenté. Ne charger que des fichiers joblib de confiance.

Commit prévu : `feat(ml): implement banking fraud detection pipeline`. Aucun push n'est effectué.
