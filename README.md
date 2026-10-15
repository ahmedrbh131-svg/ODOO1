# Odoo Mobile — Android (MVP initial)

Application Android native Kotlin + Jetpack Compose pour se connecter à Odoo via JSON-RPC.

## Fonctionnalités du premier prototype
- Connexion configurable : URL HTTPS, base de données, identifiant et mot de passe.
- Recherche de transferts `stock.picking` par numéro.
- Téléchargement du PDF d'un transfert via `stock.report_picking`.
- Création d'un transfert interne avec plusieurs lignes produit.
- Recherche produit par nom, référence interne ou code-barres.
- Modification du nom, du code-barres et du prix de vente.
- Les actions sont exécutées dans Odoo avec l'utilisateur connecté : ses droits Odoo s'appliquent.

## Important avant une utilisation réelle
1. Pour votre configuration Wi-Fi locale, utilisez l'adresse locale, par exemple `http://192.168.0.3`, uniquement lorsque le téléphone et le PC sont connectés au même Wi-Fi. Le prototype autorise HTTP pour ce réseau local. N'exposez jamais ce HTTP sur Internet. Pour un accès extérieur, utilisez un VPN ou HTTPS avec une configuration serveur sécurisée.
2. Le rapport PDF est configuré par défaut sur `stock.report_picking`. Si votre Odoo utilise le rapport personnalisé « DCP Transfer Slip », il faudra configurer le vrai nom technique du rapport.
3. La création de transfert est un premier flux générique pour les transferts internes. Les règles métier particulières S13/S23/P30 et les boutons « Prêt sans valider » du Streamlit doivent être ajoutés et testés avec votre configuration Odoo avant usage quotidien.
4. Le prototype ne conserve pas le mot de passe après fermeture de l'application, mais utilise une session Odoo pendant que l'application est ouverte. Pour la production, ajouter Android Keystore, expiration de session et meilleure gestion des erreurs.
5. Le nom, le code-barres et le prix de vente sont modifiables dans ce prototype. Le prix de vente est écrit via `product.template.list_price`; aucun champ de prix d'achat n'est envoyé.
6. Pour impression, l'app télécharge le PDF dans le dossier Téléchargements. L'impression physique peut être lancée depuis le lecteur PDF du téléphone.

## Ouvrir / compiler
- Ouvrir le dossier dans Android Studio (version récente).
- Installer JDK 17 et Android SDK Platform 35.
- Synchroniser Gradle puis lancer sur un appareil Android ou émulateur.

## Format des lignes pour créer un transfert
Une ligne par produit, séparateur point-virgule :
```
REF-001;2
0123456789012;1,5
```
Référence interne ou code-barres, puis quantité. Les deux emplacements doivent être choisis dans la liste.

## Note d'intégration
Votre Odoo local utilise `192.168.0.3`. Dans l'application, saisissez `http://192.168.0.3` et connectez le téléphone au même Wi-Fi que le PC. Cette adresse locale ne fonctionne pas hors du réseau local. Pour l'accès depuis l'extérieur, configurez un VPN ou HTTPS sécurisé.
