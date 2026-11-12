# Homebrew tap

bbCéčko is installed on a Mac with

```bash
brew install brdloush/tap/bbcecko
```

That needs a tap: a GitHub repo named `brdloush/homebrew-tap` with the
formula at `Formula/bbcecko.rb`. The formula is generated, never edited by
hand.

## What the formula does

- depends on `gtk4`, `gtksourceview5` and `adwaita-icon-theme` (Homebrew's
  GTK ships no icons);
- carries its own babashka (a `resource`, from babashka's GitHub release, kept
  in the keg's `libexec`). babashka is not in homebrew-core, and Homebrew no
  longer taps a third-party tap for a dependency, so `depends_on
  "borkdude/brew/babashka"` would make every user `brew tap borkdude/brew`
  first. The version is `bb-version` in `formula.clj`;
- installs the jar that `.github/workflows/release.yml` attached to the
  GitHub release (the app, gtkiccup and Replicant in one file). Nothing is
  built inside `brew install`: Homebrew's sandbox would not let babashka
  fetch gtkiccup into `~/.gitlibs` there;
- installs a `bbcecko` launcher: `bbcecko`, `bbcecko <folder>`,
  `bbcecko --doctor`, `bbcecko --version`.

The compiler is Apple's clang++ from the command line tools, which Homebrew
itself requires, so a working `brew` means a working compiler.

## Releasing a version

1. Bump `version` in `src/bbcecko/main.clj`, commit, push.
2. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`. The Release
   workflow builds `bbcecko-0.1.0.jar` and attaches it to the release.
3. Write the formula into the tap and push it:
   ```bash
   bb formula release 0.1.0 ../homebrew-tap/Formula/bbcecko.rb
   cd ../homebrew-tap && git commit -am "bbcecko 0.1.0" && git push
   ```
4. Users get it with `brew update && brew upgrade bbcecko`.

## Testing

CI (`.github/workflows/macos.yml`, job `brew-formula`) builds a formula for
the pushed commit (`bb formula local`), installs it from a throwaway local
tap with `brew install`, runs `brew test` and `bbcecko --doctor`. A formula
that passes there is what goes into the tap.
