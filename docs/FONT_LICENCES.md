# AUTOANDROID — BUNDLED FONT INVENTORY, LICENCES & APK-SIZE IMPACT

## 1. Summary & APK Size Impact

* **Existing Bundled Fonts Preserved (`4` files)**: `inter_regular.otf`, `notoserif_regular.ttf`, `nimbusmono_regular.otf`, `z003_mediumitalic.otf` (`1,039,552` bytes / `1.00 MB`).
* **New Fonts Copied from Supplied `FONTS.zip` (`59` files)**: All 59 `.ttf` binaries copied unmodified to `app/src/main/res/font/` with Android-safe lowercase `[a-z0-9_]` resource names (`13,000,276` bytes / `12.40 MB`).
* **Total Bundled Font Assets (`63` files)**: `14,039,828` bytes (`13.39 MB` uncompressed; approximately `6.8 MB` compressed inside the APK `res/font/` entries).
* **Licence**: All bundled fonts are distributed under the **SIL Open Font License Version 1.1** (`app/src/main/assets/OFL.txt`).

---

## 2. Script Coverage & Availability

| Script Tag | Bundled Font Families Available Offline | System / Shaping Fallback | Status |
|---|---|---|---|
| **Latin** (`ScriptTag.LATIN`) | All 53 available font families (`Inter`, `Poppins`, `Roboto`, `Open Sans`, `Lato`, `Playfair Display`, `Cinzel`, `Bebas Neue`, `Anton`, `Dancing Script`, etc.) | Android system `sans-serif` / `serif` / `monospace` | **Available (`63` files)** |
| **Devanagari** (`ScriptTag.DEVANAGARI`) | `Poppins` (`poppins_regular`, `poppins_semibold`, `poppins_bold`) and `Baloo 2` (`baloo2_wght` variable `400..800`) | Automatic fallback to `Poppins` / Android HarfBuzz `sans-serif` when a Latin-only font is active | **Available (`4` files)** |
| **Telugu** (`ScriptTag.TELUGU`) | Catalog entry `telugu_bundled` marked `available = false` (`Soon`: no Telugu TTF binary was included in `FONTS.zip`; filenames are never guessed) | Automatic fallback to Android system `sans-serif` (Noto Sans Telugu HarfBuzz shaping) | **System Fallback Active / Dedicated File Soon** |
| **Malayalam** (`ScriptTag.MALAYALAM`) | Catalog entry `malayalam_bundled` marked `available = false` (`Soon`: no Malayalam TTF binary was included in `FONTS.zip`; filenames are never guessed) | Automatic fallback to Android system `sans-serif` (Noto Sans Malayalam HarfBuzz shaping) | **System Fallback Active / Dedicated File Soon** |

---

## 3. Complete Resource Inventory (`app/src/main/res/font/`)

| Resource Name | Source File | Category | Variable Axes / Static Weights | Script Tags |
|---|---|---|---|---|
| `inter_regular.otf` | Existing repo | Sans-Serif | Static `400` | Latin |
| `inter_opsz_wght.ttf` | `Inter_opsz_wght.ttf` | Sans-Serif | Variable `opsz(14..32)`, `wght(100..900)` | Latin |
| `notoserif_regular.ttf` | Existing repo | Serif | Static `400` | Latin |
| `nimbusmono_regular.otf` | Existing repo | Monospace | Static `400` | Latin |
| `z003_mediumitalic.otf` | Existing repo | Handwriting | Static `500 Italic` | Latin |
| `poppins_regular.ttf`, `poppins_semibold.ttf`, `poppins_bold.ttf` | `Poppins-*.ttf` | Indian Scripts / Sans | Static `400`, `600`, `700` | Latin, Devanagari |
| `baloo2_wght.ttf` | `Baloo2_wght.ttf` | Indian Scripts / Sans | Variable `wght(400..800)` | Latin, Devanagari |
| `roboto_wdth_wght.ttf` | `Roboto_wdth_wght.ttf` | Sans-Serif | Variable `wght(100..900)`, `wdth(75..100)` | Latin |
| `opensans_wdth_wght.ttf` | `OpenSans_wdth_wght.ttf` | Sans-Serif | Variable `wght(300..800)`, `wdth(75..100)` | Latin |
| `oswald_wght.ttf` | `Oswald_wght.ttf` | Sans-Serif | Variable `wght(200..700)` | Latin |
| `comfortaa_wght.ttf` | `Comfortaa_wght.ttf` | Sans-Serif | Variable `wght(300..700)` | Latin |
| `lato_regular.ttf`, `lato_bold.ttf`, `lato_black.ttf` | `Lato-*.ttf` | Sans-Serif | Static `400`, `700`, `800` | Latin |
| `bevietnampro_regular.ttf`, `bevietnampro_bold.ttf` | `BeVietnamPro-*.ttf` | Sans-Serif | Static `400`, `700` | Latin |
| `kanit_regular.ttf`, `kanit_bold.ttf` | `Kanit-*.ttf` | Sans-Serif | Static `400`, `700` | Latin |
| `pt_sans_web_regular.ttf`, `pt_sans_web_bold.ttf` | `PT_Sans-Web-*.ttf` | Sans-Serif | Static `400`, `700` | Latin |
| `fjallaone_regular.ttf` | `FjallaOne-Regular.ttf` | Sans-Serif | Static `400` | Latin |
| `playfairdisplay_wght.ttf` | `PlayfairDisplay_wght.ttf` | Serif | Variable `wght(400..900)` | Latin |
| `cinzel_wght.ttf` | `Cinzel_wght.ttf` | Serif | Variable `wght(400..900)` | Latin |
| `pt_serif_web_regular.ttf`, `pt_serif_web_bold.ttf` | `PT_Serif-Web-*.ttf` | Serif | Static `400`, `700` | Latin |
| `alice_regular.ttf` | `Alice-Regular.ttf` | Serif | Static `400` | Latin |
| `abrilfatface_regular.ttf` | `AbrilFatface-Regular.ttf` | Serif | Static `400` | Latin |
| `alfaslabone_regular.ttf` | `AlfaSlabOne-Regular.ttf` | Serif | Static `400` | Latin |
| `orbitron_wght.ttf` | `Orbitron_wght.ttf` | Display | Variable `wght(400..900)` | Latin |
| `leaguegothic_wdth.ttf` | `LeagueGothic_wdth.ttf` | Display | Variable `wdth(75..100)` | Latin |
| `anton_regular.ttf`, `archivoblack_regular.ttf`, `bangers_regular.ttf`, `bebasneue_regular.ttf`, `blackopsone_regular.ttf`, `bowlbyone_regular.ttf`, `bungee_regular.ttf`, `bungeeshade_regular.ttf`, `changaone_regular.ttf`, `creepster_regular.ttf`, `fugazone_regular.ttf`, `monoton_regular.ttf`, `passionone_regular.ttf`, `passionone_bold.ttf`, `rammettoone_regular.ttf`, `righteous_regular.ttf`, `russoone_regular.ttf`, `staatliches_regular.ttf`, `titanone_regular.ttf` | `FONTS/*.ttf` | Display | Static `400` (`Passion One` `400` & `700`) | Latin |
| `dancingscript_wght.ttf`, `caveat_wght.ttf` | `*_wght.ttf` | Handwriting | Variable `wght(400..700)` | Latin |
| `amaticsc_regular.ttf`, `amaticsc_bold.ttf`, `courgette_regular.ttf`, `greatvibes_regular.ttf`, `indieflower_regular.ttf`, `kaushanscript_regular.ttf`, `lobster_regular.ttf`, `pacifico_regular.ttf`, `patrickhand_regular.ttf`, `sacramento_regular.ttf` | `FONTS/*.ttf` | Handwriting | Static `400` (`Amatic SC` `400` & `700`) | Latin |
