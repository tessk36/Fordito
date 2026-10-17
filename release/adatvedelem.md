# Adatvédelmi Tájékoztató – Fülelő Hangjegyzet (`hu.orajegyzet`)

**Utolsó frissítés**: 2026. március  
**Alkalmazás**: Fülelő Hangjegyzet (v1.0.2)

## 1. Az Adatkezelő
A Fülelő Hangjegyzet alkalmazás adatkezelője a felhasználó saját eszköze és a fejlesztő csapat.  
Kapcsolat és adatvédelmi megkeresések: `adatvedelem@orajegyzet.hu`

## 2. Kezelt Adatok Kör és Célja
- **Hangfelvételek (Mikrofon hanganyagok)**:
  - **Cél**: Órai előadások, megbeszélések és jegyzetek helyi rögzítése, átírása és összefoglalása.
  - **Tárolás**: A nyers M4A/AAC hangfájlok a készülék privát belső tárhelyén (`filesDir`) tárolódnak, és a beállított megtartási idő (alapértelmezetten 30 perc) után automatikusan törlődnek.
- **Átiratok, Összefoglalók és Projektek**:
  - Helyi Room adatbázisban tárolódnak a készüléken (`orajegyzet.db`).
- **Hitelesítési Adatok és Beállítások**:
  - Gemini API-kulcs és SMTP fiókjelszó: Az Android KeyStore AES/GCM titkosításával védve tárolódik a helyi DataStore felületen.

## 3. Adattovábbítás és Harmadik Felek
- **Offline Mód és Adatvédelmi Zár (Privacy Lock)**:
  - Bekapcsolt adatvédelmi zár vagy offline feldolgozás esetén **a hangfelvétel és a leirat soha nem hagyja el a készüléket**. A beszédfelismerés (Whisper / Vosk) és összefoglalás (Gemma) 100%-ban helyben fut az eszközön.
- **Online Mód (Google Gemini API)**:
  - Felhasználói kezdeményezésre a rögzített hangfájl vagy átirat a **Google Gemini API** (Google LLC) szervereire kerül feldolgozásra HTTPS/TLS titkosított csatornán.
  - A feldolgozás befejeztével a feltöltött átmeneti audiofájl azonnal törlésre kerül a Google szervereiről.
- **Napi E-mail Kivonat**:
  - Kizárólag a felhasználó által megadott SMTP szerveren (pl. Gmail SMTP) keresztül kerül kiküldésre a megadott e-mail címre.

## 4. Adatbiztonság és Felhőmentések
- Az érzékeny beállítások (API-kulcs, SMTP jelszó) kizárásra kerültek az Android automatikus felhőmentéséből és eszközátviteli mentéseiből (`data_extraction_rules.xml`).
- A felhasználó az alkalmazáson belül bármikor kezdeményezheti az összes adat végleges törlését ("Gyári Reset").
