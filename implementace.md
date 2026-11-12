# bbcecko – průběh implementace

Start: 2026-11-12 13:22

## Milestony

- **2026-11-12 13:22** – start. Přečtené zadání (`diskuze-s-claudem.md`) a gtkiccup (README, docs, widgets, ffi, examply).
- **2026-11-12 13:38** – první funkční verze aplikace:
  - babashka + gtkiccup, čisté GTK4 (bez libadwaita), vlastní widgety v `src/bbcecko/widgets.clj`:
    `:code-editor` (GtkSourceView 5, buffer na soubor, značky chyb), `:console`, `:paned`,
    `:window-handle`, `:window-controls`, `:popover-button`.
  - Explorer (strom složek, nový soubor / složka, koš, auto-obnova), záložky, uložení (Ctrl+S).
  - Spustit: uloží, přeloží g++, spustí program; výstup + stdin v konzoli (stdbuf, výzvy bez \n fungují).
  - Chyby překladu: červeně zvýrazněný řádek + ikonka v okraji, karty s hláškou, úryvkem kódu a tipem.
  - 6 témat (Půlnoc, Sklo, Hacker, Synthwave, Cyberpunk, Svítání), CSS + barevná schémata editoru.
  - Čeština / angličtina podle LANG. Kontrola kompilátoru + návod k instalaci.
  - Pamatuje si poslední kořenovou složku, otevřené záložky, téma a velikost písma.
- **2026-11-12 13:44** – doladění a dokončení:
  - `bb run` task (+ `bb install` / `bb uninstall` pro menu aplikací a PATH, `bb test`).
  - Zahlcení výstupem (nekonečný cyklus): výstup se ořezává, konzole drží posledních 100k znaků, Zastavit funguje.
  - Konzole sama sjíždí na konec (jako terminál), když uživatel neodscrolloval nahoru.
  - Uvítací obrazovka s výběrem složky a témat; banner „Chybí kompilátor“ s příkazem pro distribuci + Kopírovat.
  - Nový soubor ukazuje, do které složky se vytvoří. Ikona aplikace (SVG).
  - Testy: 11 testů / 38 asercí (parsování chyb gcc/clang, soubory, spouštění se vstupem, zastavení floodu).
  - Ověřeno headless screenshoty: všech 6 témat, chyby, vstup, flood, menu témat, zoom, zkratky.
- **2026-11-12 13:45** – hotovo: konzole po doběhnutí programu spolehlivě sjede na konec. Appka připravená ke spuštění přes `bb run`. Další krok (fáze 2): GitHub page.
- **2026-11-12 14:03** – příprava na macOS: clang++ (jiné přepínače, detekce Xcode CLT, návod `xcode-select --install`), ⌘ místo Ctrl, tlačítka okna vlevo, mac písma, koš se zálohou bez GIO, parsování chyb linkeru od Applu. Ukázkové programy v `examples/`, `bb smoke` (proklikne appku, ověří a nafotí). GitHub Actions workflow pro macOS.
- **2026-11-12 14:14** – macOS v GitHub Actions: testy i smoke test prošly na macOS 26 (arm64, Apple clang 21). Opraveno: GTK musí na Macu běžet v hlavním vlákně (smoke/shot), nové API pro tmavý režim (GTK 4.20+), základní téma Adwaita, ikony (brew `adwaita-icon-theme`), jazyk z nastavení macOS při spuštění z Docku.
