# Politique de confidentialité — Reader's Scanner

*Dernière mise à jour : 8 novembre 2026*

Reader's Scanner (`com.freedomfighter.readersscanner`) est publiée par Pierre Gallaz. Elle numérise des documents avec l'appareil photo du téléphone, en lit le texte sur le téléphone et les classe en PDF cherchables. Elle est gratuite, sans publicité, sans compte et sans pistage de sa part.

## Données collectées par l'application

L'application elle-même ne collecte, ne conserve ni ne transmet aucune donnée personnelle à l'éditeur, et elle ne contient aucune bibliothèque d'analyse d'audience, de publicité ou de rapport de plantage.

## Moteurs de lecture du texte

Au premier démarrage, vous choisissez comment les pages sont trouvées et lues :

- **les moteurs libres** : le détecteur de page de l'application et Tesseract. Tout tourne sur le téléphone ; rien n'en sort.
- **Google ML Kit** (numériseur de documents et reconnaissance de texte, tous deux sur le téléphone). Ce composant à code fermé des services Google Play peut envoyer des statistiques d'utilisation à Google ; il n'envoie jamais vos documents. Son usage relève de la politique de confidentialité de Google. Vous pouvez changer de moteur à tout moment dans les réglages.

Le texte en russe est toujours lu par Tesseract, sur le téléphone.

## Utilisation du réseau

Le réseau n'est utilisé qu'à votre demande :

- **Dossier WebDAV**, si vous en configurez un dans les réglages : vos numérisations (PDF et petits fichiers de description) sont copiées vers et depuis le serveur que vous indiquez (par exemple kDrive ou Nextcloud), avec l'identifiant que vous donnez. Rien d'autre n'est envoyé, et l'application ne contacte jamais un serveur que vous n'avez pas configuré.
- **Meilleurs modèles de langue**, si vous les téléchargez dans les réglages : les fichiers viennent de github.com (projet tesseract-ocr). Cela n'a lieu que lorsque vous touchez le bouton de téléchargement.

Les connexions se font en HTTPS, sauf vers l'adresse de bouclage d'une machine, pour les tests.

## Données conservées sur le téléphone

Vos documents, leur texte, vos dossiers, vos réglages et, si vous les avez saisis, votre adresse et votre identifiant WebDAV restent dans l'espace privé de l'application sur le téléphone. Ils sont supprimés avec l'application. La sauvegarde Android est désactivée pour l'application : le système ne copie rien vers un compte Google.

Les réglages permettent d'exporter l'identifiant WebDAV dans un fichier de votre choix, et d'en importer un fait par une autre application Reader's, uniquement quand vous le demandez.

## Permissions

- **Appareil photo** : pour photographier les pages. Des photos ne sont prises que sur l'écran de capture.
- **Internet** : uniquement pour le dossier WebDAV et le téléchargement facultatif de modèles décrits ci-dessus.

## Enfants

L'application ne s'adresse pas aux enfants et ne collecte de données de personne.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Code source : https://github.com/funkypitt/readers-scanner
