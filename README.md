# SpeechKiosk

[English version](README.en.md)

SpeechKiosk transforme une tablette Android en écran de sous-titres en direct destiné notamment aux personnes sourdes ou malentendantes. L'interface volontairement minimale affiche la parole en gros caractères, blanc sur noir. En mode appareil dédié, la tablette ne sert plus qu'à la transcription et l'accès aux réglages reste protégé par un code administrateur.

> Nom provisoire : **SpeechKiosk**. Le projet était auparavant appelé MamieTurbo.

## Fonctionnalités

- transcription progressive à faible latence avec l'API Realtime OpenAI ;
- clé API saisie dans le menu administrateur, jamais intégrée au code ni à l'APK ;
- choix de la langue : français, anglais, allemand, espagnol, italien, portugais ou néerlandais ;
- filtrage local des silences pour réduire l'audio envoyé ;
- mode kiosque Android avec accès contrôlé au Wi-Fi et aux paramètres ;
- effacement de la conversation lorsque l'application passe en arrière-plan ou que la tablette est verrouillée ;
- moteur sherpa-onnx hors ligne expérimental et optionnel, actuellement limité au français.

## Matériel requis

- une tablette sous **Android 9 (API 28) ou version plus récente** ;
- un microphone fonctionnel ;
- du Wi-Fi pour le mode OpenAI ;
- un ordinateur avec ADB pour configurer le mode appareil dédié.

Les performances du mode hors ligne dépendent fortement du processeur de la tablette. Le mode OpenAI reste le mode recommandé pour une tablette ancienne ou peu puissante.

## Configurer l'application

1. Installez et ouvrez l'application.
2. Accordez l'autorisation d'utiliser le microphone.
3. Faites un appui long sur la ligne d'état en haut de l'écran.
4. Saisissez le PIN administrateur (par défaut `2468`).
5. Dans la colonne **OpenAI**, ouvrez **Clé API**, collez votre propre clé, puis choisissez la langue.

La clé est conservée dans le stockage privé de l'application sur la tablette. Elle n'est ni présente dans le dépôt, ni ajoutée à l'APK pendant la compilation. Une application installée sur un appareil que vous ne contrôlez pas ne peut toutefois pas protéger un secret aussi fortement qu'un serveur : pour une diffusion commerciale, utilisez plutôt un petit serveur intermédiaire et des jetons temporaires.

Vous pouvez créer une clé depuis le [tableau de bord OpenAI](https://platform.openai.com/api-keys). L'utilisation de l'API est facturée au compte associé à cette clé.

## Compiler

Prérequis : Android Studio récent, JDK 17 et Android SDK 35.

```powershell
.\gradlew.bat testCloudDebugUnitTest assembleCloudDebug
```

L'APK léger est généré dans `app/build/outputs/apk/cloud/debug/`. Aucun champ `OPENAI_API_KEY` n'est lu depuis `local.properties`.

### Tester le mode local optionnel

Le modèle français n'est volontairement pas versionné : son plus gros fichier dépasse la limite normale de GitHub. Sous Windows :

```powershell
.\download-offline-model.ps1
.\gradlew.bat testHybridDebugUnitTest assembleHybridDebug
```

Le script télécharge le modèle officiel sherpa-onnx dans `app/src/hybrid/assets/`, un dossier ignoré par Git. La variante `cloud` désactive le bouton local ; la variante `hybrid` vérifie également la présence de tous les fichiers avant de l'autoriser.

Le PIN administrateur peut être personnalisé localement sans être versionné :

```properties
ADMIN_PIN=1234
```

## Activer le mode kiosque

Le mode kiosque complet utilise le mécanisme Android **Device Owner**. Il doit être configuré sur une tablette fraîchement réinitialisée, avant d'ajouter un compte Google.

```powershell
adb install -r app-debug.apk
adb shell dpm set-device-owner fr.mamieturbo/.kiosk.MamieTurboDeviceAdminReceiver
adb shell pm grant fr.mamieturbo android.permission.RECORD_AUDIO
adb shell am start -n fr.mamieturbo/.ui.MainActivity
```

Une fois provisionnée, SpeechKiosk devient l'application d'accueil, entre automatiquement en Lock Task et masque les commandes permettant de quitter l'application. Pour la maintenance, faites un appui long sur la ligne d'état, saisissez le PIN, puis ouvrez le Wi-Fi ou les paramètres. Une réinitialisation de la tablette peut être nécessaire pour retirer un Device Owner.

Le nom de paquet historique `fr.mamieturbo` est conservé pour permettre la mise à jour des tablettes existantes sans les reprovisionner.

## Confidentialité

- aucun enregistrement audio ni transcript n'est sauvegardé par l'application ;
- en mode OpenAI, les segments de parole sont transmis à OpenAI pour transcription ;
- en mode hors ligne, l'audio reste sur la tablette ;
- le texte visible est effacé à chaque verrouillage ou sortie de l'application.

Consultez les conditions et politiques OpenAI applicables avant tout déploiement auprès de tiers.

## État du projet

SpeechKiosk est un projet expérimental fourni sans garantie. Testez soigneusement le microphone, la reconnexion réseau, le verrouillage et le mode kiosque sur chaque modèle de tablette avant de le confier à un utilisateur.

## Licence

Code distribué sous licence MIT. Le moteur sherpa-onnx et le modèle hors ligne optionnels restent soumis à leurs licences respectives ; voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
