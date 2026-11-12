# bbCéčko

A small, friendly C++ editor for students. Syntax highlighting, a big **Run**
button, the program's output and input in the window, and compiler errors you
can actually read. Six themes, from clean to hacker.

Written in [babashka](https://babashka.org) with
[gtkiccup](https://github.com/brdloush/gtkiccup): plain GTK4 + GtkSourceView 5, no libadwaita.

## Run it

```bash
bb run                  # last folder, or a welcome screen to pick one
bb run ~/skola/cpp      # this folder
bb run ukol.cpp         # this file, and its folder
```

`bb install` puts `bbcecko` on your PATH (`~/.local/bin`) and in the
application menu. Then `bbcecko .` in a terminal opens the folder you are in,
and plain `bbcecko` from a terminal does the same. `bb uninstall` removes it
again.

## What you need

- babashka 1.13.220 or later (the dynamically linked build)
- GTK 4.10+ and GtkSourceView 5 (`apt install libgtk-4-1 libgtksourceview-5-0`)
- a C++ compiler: g++ or clang++ (`apt install build-essential`). Without one,
  the app says so and shows the install command for your distribution.
- `git`: babashka fetches gtkiccup from GitHub on the first run

### macOS (not tried on a real Mac yet)

```bash
brew install borkdude/brew/babashka gtk4 gtksourceview5 adwaita-icon-theme
xcode-select --install     # Apple's clang++, if not there yet
bb run
```

CI runs the tests and the smoke test on a macOS runner on every push and keeps
the screenshots as an artifact (`.github/workflows/macos.yml`). On a Mac the
compiler is clang++, the shortcuts use ⌘ instead of Ctrl, and the window
buttons sit on the left.

## Using it

| | |
| --- | --- |
| **F5**, Ctrl+R | save, compile, run |
| Shift+F5 | stop the program |
| Ctrl+D | end of input (EOF) for the running program |
| Ctrl+S | save |
| Ctrl+N | new file (no extension → `.cpp` with a hello world) |
| Ctrl+W, Ctrl+Tab | close tab, next tab |
| Ctrl + / − / 0 | code font size |

- **Explorer** on the left: the root folder as a tree. New file, new folder,
  move to trash, refresh. It notices files added outside the app.
- **Errors**: the line turns red with an icon in the margin, and the *Chyby*
  tab shows one card per error: where, the original compiler message, the line
  of code, and a tip for the classic beginner mistakes. Click a card to jump.
- **Input**: while the program runs, type into the line under the output and
  press Enter. Prompts without a newline (`cout << "Zadej: "`) show up at once.
- **Themes** (🎨 in the top bar): Půlnoc, Sklo, Hacker, Synthwave, Cyberpunk,
  Svítání.
- **Language**: Czech or English, from the system locale (`LANG`).
- Remembers the last folder, its open tabs, the theme and the font size
  (`~/.config/bbcecko/config.edn`).

Programs are compiled with `-std=c++17 -Wall -g` into `~/.cache/bbcecko/build`,
so the student's folder only holds what they wrote. Each program runs in the
folder of its source file, so it can open files next to it.

## Code

| | |
| --- | --- |
| `src/bbcecko/app.clj` | state, actions, the whole view |
| `src/bbcecko/widgets.clj` | the GTK widgets added to gtkiccup: `:code-editor`, `:console`, `:paned`, `:window-handle`, `:window-controls`, `:popover-button` |
| `src/bbcecko/gtk.clj` | FFI bindings: GtkSourceView, text buffers, dialogs |
| `src/bbcecko/themes.clj` | the six themes: CSS and editor colour schemes |
| `src/bbcecko/compiler.clj` | finding a compiler, compiling, parsing errors |
| `src/bbcecko/runner.clj` | running the program with stdin/stdout |
| `src/bbcecko/files.clj` | settings, folder listings, creating files |
| `src/bbcecko/i18n.clj` | Czech and English strings |

`examples/` holds a few small programs: one that reads input, one that does not
compile, an endless loop.

`bb test` runs the tests (no window). `bb smoke [out-dir]` drives the real app
end to end (welcome, run with input, compile errors, stop, new file, trash,
every theme) and saves screenshots; it exits 1 if a check fails. `dev/shot.clj` drives the app headless
and takes screenshots:

```bash
../gtkiccup/scripts/headless bb --config bb.edn dev/shot.clj ~/skola/cpp /tmp/shot \
  '[[:theme/set :hacker]]' '[:shot "hacker"]'
```

## License

[MIT](LICENSE)
