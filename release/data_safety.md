# Google Play Console – Adatbiztonsági (Data Safety) Nyilatkozat Piszkozat

**Alkalmazás**: Fülelő Hangjegyzet (`hu.orajegyzet`)

## 1. Adatgyűjtés és Megosztás (Data Collection & Sharing)

### Gyűjtött Adattípusok
- **Audio (Hangfelvételek)**:
  - *Hang- és beszédjelátvitel / felvétel*: Igen.
  - *Cél*: Alkalmazásfunkció (App functionality) – beszédfelismerés és jegyzetkészítés.
  - *Személyhez kötött*: Nem.
  - *Ephemeral (átmeneti)*: Igen, a nyers hangfájlok feldolgozás után automatikusan törlődnek.

### Adatmegosztás (Data Sharing)
- **Harmadik féllel való megosztás**:
  - Online módon való használatkor a hangfájl/leirat kizárólag a **Google Cloud (Gemini API)** felületére továbbítódik feldolgozásra a felhasználó által kezdeményezett kérésre.

## 2. Biztonsági Gyakorlatok (Security Practices)
- **Titkosítás átvitel közben**: Igen (minden hálózati kommunikáció HTTPS / TLS csatornán fut).
- **Adattörlési kérelem**: Igen (a felhasználó az alkalmazáson belül a Beállítások menüben egyetlen gombnyomással törölheti a jegyzeteket és az adatbázist).
- **Titkosított tárolás**: Igen (Android KeyStore AES-GCM titkosítás az API-kulcsokhoz és jelszavakhoz).
