# TODO

- **gtkiccup as a git dependency.** `bb.edn` points at a local checkout,
  `{:local/root "../gtkiccup"}`, so a fresh clone of this repo only works with
  gtkiccup cloned next to it. Once gtkiccup has a tagged release, switch to
  `{:git/url "https://github.com/brdloush/gtkiccup" :git/tag "..." :git/sha "..."}`
  and drop the "checked out next to this folder" line from the README.
