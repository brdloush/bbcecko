# TODO

- **gtkiccup by tag, not by commit.** `bb.edn` pins gtkiccup to a commit
  (`:git/sha`), because gtkiccup has no release yet. Once it is tagged, use
  `{:git/tag "v..." :git/sha "..."}` so the version reads as a version.
