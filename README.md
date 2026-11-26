# E-Bike Navigator Pro — 2.0

Application Android de navigation pour vélo électrique (Montréal d'abord), sans clé ni abonnement.
Reconstruite à partir de la V1.0.4 (testée sur Samsung S10+) et des idées de la V0.3.

## Nouveautés 2.0

| Fonction | Détail |
|---|---|
| **Suivi façon Google Maps** | La carte avance avec vous, tournée dans le sens de la route, inclinée en 3D, curseur en bas de l'écran pour voir loin devant |
| **Curseur collé à la route** | La position GPS est recalée sur le trajet (fini le point qui « flotte » à côté) |
| **Zoom automatique** | Se resserre à l'approche d'un virage, s'élargit en ligne droite et à vitesse élevée |
| **Recentrage** | Bouton « ◎ Recentrer » + retour automatique au suivi 8 s après avoir bougé la carte |
| **Trajet parcouru grisé** | Le vert = ce qui reste, le gris = ce qui est fait |
| **Feux, arrêts, obstacles** | 🚦 feux · 🛑 arrêts · 🔻 cédez · 🚆 passages à niveau · 〰️ dos d'âne · 🚧 bornes et barrières (OpenStreetMap) : icônes sur la carte, bandeau « 🚦 Feu · 80 m » et annonce vocale |
| **Mes signalements** | Bouton « ⚠ Signaler » en roulant : nid-de-poule, travaux, obstacle, neige/glace, danger. Gardés sur le téléphone et annoncés aux passages suivants |
| **Profil du vélo** (⚙) | Tension, Ah, charge actuelle, Wh/km, réserve, poids → batterie à l'arrivée, avec montées et froid |
| **Répéter** | Appui sur le bandeau vert = la voix répète l'instruction |
| **Voix on/off** | Bouton 🔊 / 🔇 |

## Toujours présent depuis la V1

Carte plan / satellite · recherche d'adresse ou appui long · 3 modes (Rapide, Équilibré, Sécurisé) ·
voix en français sur haut-parleur (écouteurs interdits à vélo au Québec) · recalcul automatique ·
guidage écran verrouillé · musique baissée pendant les annonces · 🧪 simulation à la maison.

## Ce qui a été repris de la V0.3 (et amélioré)

- Profil batterie avec réserve et énergie des montées → gardé, sans clé payante
- Bouton « Répéter » et voix activable → gardés
- Clé OpenRouteService obligatoire → **supprimée** (Valhalla gratuit)
- Satellite à configurer soi-même → **supprimé** (fonctionne directement)

## Installation

L'APK est fabriqué par GitHub à chaque modification (onglet **Releases**) et s'installe
**par-dessus** la version 1.0.4 (même signature, numéro de version supérieur).

## Services utilisés (gratuits, usage personnel)

Valhalla (FOSSGIS) · Photon · OpenFreeMap · Overpass API · Esri World Imagery
© Contributeurs OpenStreetMap · Imagerie © Esri

## Tests automatiques

GitHub vérifie avant chaque APK : annonces des virages dans l'ordre, arrivée, sortie de trajet,
GPS imprécis, recalage du curseur, orientation de la carte, annonces des feux (une seule fois),
fusion des doublons, calcul de batterie.
