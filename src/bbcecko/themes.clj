(ns bbcecko.themes
  "The look. Each theme is one palette map; from it come three things:

     css      the whole stylesheet, loaded into ONE provider that is reloaded
              on a theme switch (so no rule ever needs a theme prefix)
     scheme   a GtkSourceView style-scheme XML for the code colours
     console  the colours of program output in the console

   GTK has no backdrop blur, so the \"liquid glass\" is made the other way
   round: the window background is already soft (layered radial gradients),
   and the panels over it are translucent with a light top edge. What shows
   through looks frosted because it was blurry to begin with."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ui-font "Inter, Cantarell, \"SF Pro Text\", \"Helvetica Neue\", \"Noto Sans\", \"Segoe UI\", \"DejaVu Sans\", sans-serif")
(def code-font "\"JetBrains Mono\", \"Fira Code\", \"SF Mono\", Menlo, Hack, \"Noto Sans Mono\", \"DejaVu Sans Mono\", monospace")

(def themes
  "In menu order."
  [{:id :midnight
    :emoji "🌙"
    :name {:cs "Půlnoc" :en "Midnight"}
    :dark? true
    :bg ["radial-gradient(circle at 12% 18%, rgba(99,102,241,0.55) 0%, rgba(99,102,241,0) 42%)"
         "radial-gradient(circle at 88% 12%, rgba(20,184,166,0.38) 0%, rgba(20,184,166,0) 38%)"
         "radial-gradient(circle at 70% 95%, rgba(168,85,247,0.40) 0%, rgba(168,85,247,0) 45%)"
         "linear-gradient(160deg, #0b1020 0%, #121735 55%, #0d1226 100%)"]
    :swatch ["#6366f1" "#14b8a6" "#0b1020"]
    :panel "rgba(255,255,255,0.065)" :panel-border "rgba(255,255,255,0.12)"
    :panel-hi "rgba(255,255,255,0.10)" :shadow "rgba(0,0,0,0.45)"
    :popover "rgba(22,26,52,0.97)"
    :text "#e6e9f5" :muted "#9aa3c7" :faint "#5d6690"
    :accent "#7c8cff" :accent2 "#2dd4bf" :accent-text "#0b1020"
    :danger "#ff5d73" :warning "#ffc145" :success "#4ade80"
    :hover "rgba(255,255,255,0.07)" :selected "rgba(124,140,255,0.22)"
    :input "rgba(0,0,0,0.25)"
    :editor "rgba(9,12,28,0.72)" :gutter "#0f1430" :gutter-fg "#4b557f"
    :line "#161c40" :selection "#2e3a7a" :cursor "#9fb0ff"
    :syntax {:keyword "#c792ea" :type "#7dcfff" :string "#c3e88d" :number "#ff9e64"
             :comment "#5c6a9c" :preproc "#89ddff" :include "#c3e88d"
             :constant "#ff9e64" :escape "#f78c6c" :function "#82aaff" :error "#ff5d73"}
    :console {:out "#e6e9f5" :err "#ff7b8d" :input "#2dd4bf" :sys "#8a93bd" :ok "#4ade80"}}

   {:id :glass
    :emoji "🧊"
    :name {:cs "Sklo" :en "Glass"}
    :dark? false
    :bg ["radial-gradient(circle at 10% 15%, rgba(147,197,253,0.85) 0%, rgba(147,197,253,0) 45%)"
         "radial-gradient(circle at 90% 10%, rgba(244,114,182,0.45) 0%, rgba(244,114,182,0) 40%)"
         "radial-gradient(circle at 75% 90%, rgba(167,139,250,0.55) 0%, rgba(167,139,250,0) 45%)"
         "radial-gradient(circle at 20% 95%, rgba(110,231,183,0.45) 0%, rgba(110,231,183,0) 40%)"
         "linear-gradient(160deg, #eef2ff 0%, #f8f5ff 100%)"]
    :swatch ["#93c5fd" "#f472b6" "#f8f5ff"]
    :panel "rgba(255,255,255,0.55)" :panel-border "rgba(255,255,255,0.85)"
    :panel-hi "rgba(255,255,255,0.95)" :shadow "rgba(60,72,120,0.18)"
    :popover "rgba(255,255,255,0.97)"
    :text "#1e2438" :muted "#5b6382" :faint "#9aa1bd"
    :accent "#4f6bff" :accent2 "#d946ef" :accent-text "#ffffff"
    :danger "#e11d48" :warning "#b7791f" :success "#16a34a"
    :hover "rgba(79,107,255,0.08)" :selected "rgba(79,107,255,0.16)"
    :input "rgba(255,255,255,0.7)"
    :editor "rgba(255,255,255,0.78)" :gutter "#f3f4fb" :gutter-fg "#a3a9c4"
    :line "#eef1ff" :selection "#cdd7ff" :cursor "#4f6bff"
    :syntax {:keyword "#a626a4" :type "#0184bc" :string "#3a8a2a" :number "#c75b00"
             :comment "#9097b3" :preproc "#4f6bff" :include "#3a8a2a"
             :constant "#c75b00" :escape "#d14d00" :function "#4078f2" :error "#e11d48"}
    :console {:out "#1e2438" :err "#d0103a" :input "#7c3aed" :sys "#6b7394" :ok "#15803d"}
    :run "#22c55e"}

   {:id :hacker
    :emoji "💻"
    :name {:cs "Hacker" :en "Hacker"}
    :dark? true
    :mono-ui? true
    :bg ["repeating-linear-gradient(0deg, rgba(0,255,102,0.025) 0px, rgba(0,255,102,0.025) 1px, rgba(0,0,0,0) 1px, rgba(0,0,0,0) 3px)"
         "radial-gradient(circle at 50% 0%, rgba(0,255,102,0.16) 0%, rgba(0,255,102,0) 55%)"
         "radial-gradient(circle at 50% 120%, rgba(0,255,102,0.10) 0%, rgba(0,255,102,0) 50%)"
         "linear-gradient(180deg, #010401 0%, #020a03 100%)"]
    :swatch ["#00ff66" "#00aa44" "#010401"]
    :panel "rgba(0,255,102,0.035)" :panel-border "rgba(0,255,102,0.28)"
    :panel-hi "rgba(0,255,102,0.10)" :shadow "rgba(0,255,102,0.10)"
    :popover "rgba(2,14,5,0.98)"
    :text "#39ff7a" :muted "#1fae52" :faint "#127a37"
    :accent "#00ff66" :accent2 "#b6ff00" :accent-text "#001a08"
    :danger "#ff3b3b" :warning "#ffd000" :success "#00ff66"
    :hover "rgba(0,255,102,0.08)" :selected "rgba(0,255,102,0.18)"
    :input "rgba(0,20,6,0.85)"
    :editor "rgba(0,6,2,0.88)" :gutter "#010a03" :gutter-fg "#127a37"
    :line "#032010" :selection "#0b4d22" :cursor "#00ff66"
    :syntax {:keyword "#eaffea" :type "#00e5ff" :string "#ffd000" :number "#ff9b3d"
             :comment "#1a7a3a" :preproc "#b6ff00" :include "#ffd000"
             :constant "#ff9b3d" :escape "#ff9b3d" :function "#7dffb0" :error "#ff3b3b"}
    :console {:out "#39ff7a" :err "#ff4d4d" :input "#eaffea" :sys "#1fae52" :ok "#b6ff00"}
    :extra "
label, button, entry { text-shadow: 0 0 6px rgba(0,255,102,0.45); }
.brand { letter-spacing: 3px; }
.run-btn { text-shadow: none; }
.panel { border-radius: 4px; }
"}

   {:id :synthwave
    :emoji "🌆"
    :name {:cs "Synthwave" :en "Synthwave"}
    :dark? true
    :bg ["radial-gradient(circle at 50% 108%, rgba(255,46,151,0.75) 0%, rgba(255,46,151,0) 45%)"
         "radial-gradient(circle at 85% 10%, rgba(0,229,255,0.30) 0%, rgba(0,229,255,0) 35%)"
         "radial-gradient(circle at 10% 30%, rgba(155,93,229,0.45) 0%, rgba(155,93,229,0) 40%)"
         "linear-gradient(180deg, #140426 0%, #2a0845 60%, #4a0d4f 100%)"]
    :swatch ["#ff2e97" "#00e5ff" "#2a0845"]
    :panel "rgba(30,6,56,0.45)" :panel-border "rgba(255,46,151,0.35)"
    :panel-hi "rgba(255,180,230,0.14)" :shadow "rgba(255,46,151,0.18)"
    :popover "rgba(34,8,60,0.98)"
    :text "#fbe8ff" :muted "#c79bd8" :faint "#7d5596"
    :accent "#ff2e97" :accent2 "#00e5ff" :accent-text "#ffffff"
    :danger "#ff4d6d" :warning "#ffd166" :success "#72f1b8"
    :hover "rgba(255,46,151,0.10)" :selected "rgba(255,46,151,0.24)"
    :input "rgba(20,2,38,0.6)"
    :editor "rgba(22,4,40,0.78)" :gutter "#1e0636" :gutter-fg "#7d5596"
    :line "#2c0b4a" :selection "#5a1a73" :cursor "#00e5ff"
    :syntax {:keyword "#ff7edb" :type "#36f9f6" :string "#ffd166" :number "#f97e72"
             :comment "#8a6aa5" :preproc "#fede5d" :include "#ffd166"
             :constant "#f97e72" :escape "#f97e72" :function "#72f1b8" :error "#ff4d6d"}
    :console {:out "#fbe8ff" :err "#ff6b88" :input "#36f9f6" :sys "#c79bd8" :ok "#72f1b8"}
    :run "#ff2e97" :run-text "#ffffff"
    :extra "
.brand { text-shadow: 0 0 12px rgba(255,46,151,0.9), 0 0 2px #ffffff; }
.run-btn { box-shadow: 0 0 18px rgba(255,46,151,0.65), inset 0 1px rgba(255,255,255,0.35); }
"}

   {:id :cyberpunk
    :emoji "⚡"
    :name {:cs "Cyberpunk" :en "Cyberpunk"}
    :dark? true
    :bg ["radial-gradient(circle at 92% 8%, rgba(252,238,10,0.20) 0%, rgba(252,238,10,0) 35%)"
         "radial-gradient(circle at 5% 95%, rgba(0,240,255,0.22) 0%, rgba(0,240,255,0) 40%)"
         "repeating-linear-gradient(135deg, rgba(252,238,10,0.025) 0px, rgba(252,238,10,0.025) 2px, rgba(0,0,0,0) 2px, rgba(0,0,0,0) 14px)"
         "linear-gradient(160deg, #0a0a0f 0%, #12121c 100%)"]
    :swatch ["#fcee0a" "#00f0ff" "#0a0a0f"]
    :radius 3
    :panel "rgba(18,18,30,0.72)" :panel-border "rgba(252,238,10,0.38)"
    :panel-hi "rgba(0,240,255,0.10)" :shadow "rgba(0,0,0,0.55)"
    :popover "rgba(14,14,22,0.98)"
    :text "#f2f2f2" :muted "#a7a7b8" :faint "#5c5c70"
    :accent "#fcee0a" :accent2 "#00f0ff" :accent-text "#0a0a0f"
    :danger "#ff003c" :warning "#ff9f1c" :success "#39ff14"
    :hover "rgba(0,240,255,0.08)" :selected "rgba(252,238,10,0.16)"
    :input "rgba(0,0,0,0.5)"
    :editor "rgba(8,8,14,0.85)" :gutter "#0c0c14" :gutter-fg "#5c5c70"
    :line "#16161f" :selection "#34340f" :cursor "#fcee0a"
    :syntax {:keyword "#fcee0a" :type "#00f0ff" :string "#39ff14" :number "#ff9f1c"
             :comment "#5c5c70" :preproc "#ff003c" :include "#39ff14"
             :constant "#ff9f1c" :escape "#ff9f1c" :function "#ff6ad5" :error "#ff003c"}
    :console {:out "#f2f2f2" :err "#ff3366" :input "#00f0ff" :sys "#a7a7b8" :ok "#39ff14"}
    :run "#fcee0a" :run-text "#0a0a0f"
    :extra "
.brand { letter-spacing: 2px; }
.run-btn { border-radius: 0px 10px 0px 10px; }
.panel { border-left: 3px solid #fcee0a; }
"}

   {:id :sunrise
    :emoji "🌅"
    :name {:cs "Svítání" :en "Sunrise"}
    :dark? false
    :bg ["radial-gradient(circle at 85% 12%, rgba(251,146,60,0.55) 0%, rgba(251,146,60,0) 42%)"
         "radial-gradient(circle at 10% 85%, rgba(244,63,94,0.30) 0%, rgba(244,63,94,0) 42%)"
         "radial-gradient(circle at 40% 10%, rgba(250,204,21,0.40) 0%, rgba(250,204,21,0) 40%)"
         "linear-gradient(160deg, #fff7ed 0%, #ffe4e6 100%)"]
    :swatch ["#fb923c" "#f43f5e" "#fff7ed"]
    :panel "rgba(255,255,255,0.58)" :panel-border "rgba(255,255,255,0.9)"
    :panel-hi "rgba(255,255,255,0.95)" :shadow "rgba(160,70,40,0.16)"
    :popover "rgba(255,250,245,0.98)"
    :text "#3b2117" :muted "#8a5a45" :faint "#c39b88"
    :accent "#f97316" :accent2 "#e11d48" :accent-text "#ffffff"
    :danger "#dc2626" :warning "#b45309" :success "#15803d"
    :hover "rgba(249,115,22,0.09)" :selected "rgba(249,115,22,0.18)"
    :input "rgba(255,255,255,0.75)"
    :editor "rgba(255,253,250,0.82)" :gutter "#fff3ea" :gutter-fg "#d1a58f"
    :line "#fff0e3" :selection "#ffd8bd" :cursor "#f97316"
    :syntax {:keyword "#c2185b" :type "#00838f" :string "#558b2f" :number "#d84315"
             :comment "#b39283" :preproc "#6a1b9a" :include "#558b2f"
             :constant "#d84315" :escape "#e65100" :function "#1565c0" :error "#dc2626"}
    :console {:out "#3b2117" :err "#c81e1e" :input "#c2410c" :sys "#8a5a45" :ok "#15803d"}
    :run "#f97316"}])

(def by-id (into {} (map (juxt :id identity)) themes))

(defn theme [id] (by-id id (by-id :midnight)))

(defn scheme-id [id] (str "bbcecko-" (name id)))

;; ---------------------------------------------------------------------------
;; GtkSourceView style schemes
;; ---------------------------------------------------------------------------

(defn scheme-xml [{:keys [id name syntax text gutter gutter-fg line selection cursor accent danger]}]
  (let [s (fn [style & {:as attrs}]
            (str "  <style name=\"" style "\""
                 (apply str (for [[k v] attrs] (str " " (clojure.core/name k) "=\"" v "\"")))
                 "/>\n"))
        {:keys [keyword type string number comment preproc include constant escape function error]} syntax]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<style-scheme id=\"" (scheme-id id) "\" name=\"bbcecko " (:en name) "\" version=\"1.0\">\n"
         "  <description>generated by bbcecko</description>\n"
         (s "text" :foreground text)
         (s "selection" :background selection)
         (s "selection-unfocused" :background selection)
         (s "cursor" :foreground cursor)
         (s "current-line" :background line)
         (s "line-numbers" :foreground gutter-fg :background gutter)
         (s "current-line-number" :foreground accent :background line :bold "true")
         (s "bracket-match" :foreground accent :background selection :bold "true")
         (s "bracket-mismatch" :foreground danger :underline "error")
         (s "def:comment" :foreground comment :italic "true")
         (s "def:shebang" :foreground comment :bold "true")
         (s "def:doc-comment-element" :foreground comment :bold "true")
         (s "def:string" :foreground string)
         (s "def:character" :foreground string)
         (s "def:special-char" :foreground escape :bold "true")
         (s "def:keyword" :foreground keyword :bold "true")
         (s "def:statement" :foreground keyword :bold "true")
         (s "def:builtin" :foreground type)
         (s "def:type" :foreground type)
         (s "def:preprocessor" :foreground preproc)
         (s "c:preprocessor" :foreground preproc)
         (s "c:included-file" :foreground include)
         (s "def:number" :foreground number)
         (s "def:decimal" :foreground number)
         (s "def:floating-point" :foreground number)
         (s "def:base-n-integer" :foreground number)
         (s "def:boolean" :foreground constant :bold "true")
         (s "def:constant" :foreground constant)
         (s "def:special-constant" :foreground constant)
         (s "def:function" :foreground function)
         (s "def:identifier" :foreground text)
         (s "def:error" :foreground error :underline "error")
         (s "def:note" :foreground accent :bold "true")
         (s "def:net-address" :foreground function :underline "single")
         "</style-scheme>\n")))

(defn write-schemes!
  "Writes every theme's scheme into `dir`, which GtkSourceView then searches."
  [dir]
  (fs/create-dirs dir)
  (doseq [t themes]
    (spit (str (fs/path dir (str (scheme-id (:id t)) ".xml"))) (scheme-xml t)))
  (str dir))

;; ---------------------------------------------------------------------------
;; CSS
;; ---------------------------------------------------------------------------

(defn- swatch-rules
  "Every theme's swatch, in every stylesheet: the theme menu shows them all."
  []
  (apply str
         (for [{:keys [id swatch]} themes
               :let [[a b c] swatch]]
           (str ".swatch-" (name id) " { background-image: "
                "radial-gradient(circle at 25% 25%, " a " 0%, rgba(0,0,0,0) 60%), "
                "radial-gradient(circle at 80% 75%, " b " 0%, rgba(0,0,0,0) 60%), "
                "linear-gradient(135deg, " c ", " c "); }\n"))))

(defn css
  "The whole stylesheet for theme `id` with the code at `font-size` px."
  [id font-size]
  (let [{:keys [bg panel panel-border panel-hi shadow popover text muted faint accent accent2
                accent-text danger warning success hover selected input editor gutter
                radius extra mono-ui? dark?]}
        (theme id)
        r      (or radius 14)
        run    (or (:run (theme id)) success)
        run-text (or (:run-text (theme id)) (if dark? "#04140a" "#ffffff"))
        r-sm   (max 2 (- r 5))
        ui     (if mono-ui? code-font ui-font)
        fs     (long font-size)]
    (str "
window.bbc, window.bbc.background {
  background-color: " (if dark? "#05060a" "#f4f4fa") ";
  background-image: " (str/join ", " bg) ";
  color: " text ";
  font-family: " ui ";
  font-size: 14px;
}
window.bbc * { outline-color: " (str "alpha(" accent ", 0.6)") "; }
label { color: inherit; }
image { color: inherit; -gtk-icon-shadow: none; }
.muted { color: " muted "; }
.faint { color: " faint "; }
.accent { color: " accent "; }
.mono { font-family: " code-font "; }
.small { font-size: 12px; }
.tiny { font-size: 11px; }
.bold { font-weight: 700; }

/* --- glass panels -------------------------------------------------------- */
.panel {
  background-color: " panel ";
  border: 1px solid " panel-border ";
  border-radius: " r "px;
  box-shadow: 0 10px 30px " shadow ", inset 0 1px " panel-hi ";
}
.panel-head { padding: 8px 10px 6px 14px; min-height: 30px; }
.panel-title { font-size: 11px; font-weight: 800; letter-spacing: 1.5px; color: " muted "; }

/* --- buttons ------------------------------------------------------------- */
button, menubutton > button {
  background: none; background-image: none;
  border: none; box-shadow: none; text-shadow: none;
  color: inherit; border-radius: " r-sm "px;
  padding: 5px 9px; min-height: 0; min-width: 0;
  transition: background-color 150ms ease, box-shadow 150ms ease;
}
button:hover, menubutton > button:hover { background-color: " hover "; }
button:active, menubutton > button:active, menubutton > button:checked { background-color: " selected "; }
button:disabled { color: " faint "; }
button.icon-btn { padding: 5px; }
button.pill {
  background-color: " hover "; border: 1px solid " panel-border ";
  border-radius: 99px; padding: 5px 14px;
}
button.pill:hover { background-color: " selected "; }
button.primary {
  background-image: linear-gradient(135deg, " accent ", " (str "mix(" accent ", " accent2 ", 0.35)") ");
  color: " accent-text "; font-weight: 700;
  border-radius: " r-sm "px; padding: 7px 16px;
  box-shadow: 0 6px 18px " (str "alpha(" accent ", 0.35)") ", inset 0 1px rgba(255,255,255,0.35);
}
button.primary:hover { box-shadow: 0 8px 24px " (str "alpha(" accent ", 0.55)") ", inset 0 1px rgba(255,255,255,0.45); }

.run-btn {
  background-image: linear-gradient(135deg, " run ", " (str "shade(" run ", 0.78)") ");
  color: " run-text "; font-weight: 800; font-size: 14px;
  padding: 6px 18px; border-radius: " (max 2 (- r 4)) "px;
  box-shadow: 0 6px 20px " (str "alpha(" run ", 0.40)") ", inset 0 1px rgba(255,255,255,0.4);
}
.run-btn:hover { box-shadow: 0 8px 26px " (str "alpha(" run ", 0.60)") ", inset 0 1px rgba(255,255,255,0.5); }
.run-btn:disabled { background-image: none; background-color: " hover "; color: " faint "; box-shadow: none; }
.stop-btn {
  background-image: linear-gradient(135deg, " danger ", " (str "mix(" danger ", #ff9a3c, 0.35)") ");
  color: #ffffff; font-weight: 800; font-size: 14px;
  padding: 6px 18px; border-radius: " (max 2 (- r 4)) "px;
  animation: pulse 1.6s ease-in-out infinite;
}
@keyframes pulse {
  0%   { box-shadow: 0 0 0px " (str "alpha(" danger ", 0.0)") "; }
  50%  { box-shadow: 0 0 22px " (str "alpha(" danger ", 0.65)") "; }
  100% { box-shadow: 0 0 0px " (str "alpha(" danger ", 0.0)") "; }
}

/* --- top bar ------------------------------------------------------------- */
.topbar { padding: 8px 10px 4px 14px; }
.brand { font-size: 17px; font-weight: 900; color: " text "; }
.brand-mark {
  background-image: linear-gradient(135deg, " accent ", " accent2 ");
  color: " accent-text "; font-weight: 900; font-family: " code-font "; font-size: 13px;
  border-radius: " (max 2 (- r 6)) "px; padding: 3px 7px;
}
.root-chip { background-color: " hover "; border: 1px solid " panel-border "; border-radius: 99px; padding: 4px 12px; }
.root-chip:hover { background-color: " selected "; }
windowcontrols button { padding: 4px; margin-left: 4px; border-radius: 99px; background-color: " hover "; }
windowcontrols button:hover { background-color: " selected "; }
windowcontrols button image { color: " text "; }

/* --- explorer ------------------------------------------------------------ */
.tree-row { padding: 3px 8px; border-radius: " r-sm "px; margin: 0 6px; }
.tree-row.selected { background-color: " selected "; }
.tree-row.open-file label.name { font-weight: 700; }
.tree-row image { color: " muted "; }
.tree-row.dir image.folder { color: " accent "; }
.badge {
  font-family: " code-font "; font-size: 9px; font-weight: 800;
  border-radius: 4px; padding: 0px 3px; min-width: 22px;
  color: " accent-text "; background-color: " muted ";
}
.badge.cpp { background-color: " accent "; }
.badge.h   { background-color: " accent2 "; }
.badge.c   { background-color: " (str "mix(" accent ", " accent2 ", 0.5)") "; }
.badge.txt { background-color: " faint "; }
.new-entry { margin: 2px 8px 6px 8px; }

/* --- tabs ---------------------------------------------------------------- */
.tabs { padding: 6px 6px 0 6px; }
.tab { padding: 5px 6px 5px 12px; border-radius: " r-sm "px " r-sm "px 0 0; color: " muted "; }
.tab.active { background-color: " editor "; color: " text "; box-shadow: inset 0 2px " accent "; }
.tab .dirty { color: " accent "; font-size: 10px; }
.tab button { padding: 1px; border-radius: 99px; }

/* --- editor ---------------------------------------------------------------- */
.editor-wrap { background-color: " editor "; border-radius: 0 0 " r "px " r "px; }
textview.code, textview.code > text {
  background-color: rgba(0,0,0,0);
  color: " text ";
  font-family: " code-font "; font-size: " fs "px;
}
textview.code > border, textview.code gutter { background-color: " gutter "; }
textview.code > text > selection { background-color: " (str "alpha(" accent ", 0.30)") "; }
textview.code { caret-color: " accent "; }
scrolledwindow > undershoot, scrolledwindow > overshoot { background: none; }

/* --- bottom panel / console --------------------------------------------- */
.seg button { padding: 4px 12px; border-radius: 99px; color: " muted "; font-weight: 600; }
.seg button.active { background-color: " selected "; color: " text "; }
.count { border-radius: 99px; padding: 0 6px; font-size: 11px; font-weight: 800;
         background-color: " danger "; color: #ffffff; }
.count.warn { background-color: " warning "; color: #1a1200; }
textview.console-text, textview.console-text > text {
  background-color: rgba(0,0,0,0);
  font-family: " code-font "; font-size: " (max 11 (- fs 1)) "px;
}
.console-wrap { background-color: " editor "; border-radius: " r-sm "px; margin: 0 8px; }
.stdin-row { padding: 6px 8px 8px 8px; }
.prompt { font-family: " code-font "; color: " accent2 "; font-weight: 800; }
.status-pill { border-radius: 99px; padding: 2px 10px; font-size: 12px; font-weight: 700; }
.status-pill.running   { background-color: " (str "alpha(" success ", 0.18)") "; color: " success "; }
.status-pill.compiling { background-color: " (str "alpha(" warning ", 0.18)") "; color: " warning "; }
.status-pill.failed    { background-color: " (str "alpha(" danger ", 0.18)") "; color: " danger "; }
.status-pill.done      { background-color: " hover "; color: " muted "; }

/* --- problems ------------------------------------------------------------ */
.diag-card {
  padding: 10px 12px; margin: 0 8px 8px 8px;
  border-radius: " r-sm "px; background-color: " hover ";
  border: 1px solid " panel-border "; border-left: 4px solid " danger ";
}
.diag-card:hover { background-color: " selected "; }
.diag-card.warning { border-left-color: " warning "; }
.diag-card .where { font-family: " code-font "; font-size: 12px; color: " muted "; }
.diag-card .msg { font-size: 14px; font-weight: 600; }
.diag-card .snippet {
  font-family: " code-font "; font-size: 12px; color: " text ";
  background-color: " editor "; border-radius: 6px; padding: 6px 8px;
}
.diag-card .tip { font-size: 12px; color: " accent2 "; }
.diag-card .note { font-size: 12px; color: " muted "; }
.sev { font-size: 10px; font-weight: 900; letter-spacing: 1px; border-radius: 4px; padding: 1px 6px;
       background-color: " danger "; color: #ffffff; }
.sev.warning { background-color: " warning "; color: #1a1200; }

/* --- inputs -------------------------------------------------------------- */
entry, entry.flat {
  background-color: " input "; background-image: none;
  color: " text "; caret-color: " accent ";
  border: 1px solid " panel-border "; border-radius: " r-sm "px;
  box-shadow: none; min-height: 30px; padding: 0 10px;
}
entry:focus-within { border-color: " accent "; box-shadow: 0 0 0 3px " (str "alpha(" accent ", 0.22)") "; }
entry > text > placeholder { color: " faint "; }
entry.mono { font-family: " code-font "; }

/* --- popovers, tooltips, scrollbars, separators --------------------------- */
popover.bbc-popover > contents, popover > contents {
  background-color: " popover "; color: " text ";
  border: 1px solid " panel-border "; border-radius: " r "px;
  box-shadow: 0 16px 40px rgba(0,0,0,0.35);
  padding: 8px;
}
popover > arrow { background: none; border: none; }
.menu-title { font-size: 11px; font-weight: 800; letter-spacing: 1.5px; color: " muted "; margin: 4px 8px; }
.theme-item { padding: 6px 8px; }
.theme-item.active { background-color: " selected "; }
.swatch { min-width: 34px; min-height: 22px; border-radius: 7px; border: 1px solid " panel-border "; }
" (swatch-rules) "
tooltip, tooltip.background {
  background-color: " popover "; color: " text ";
  border: 1px solid " panel-border "; border-radius: 8px;
}
tooltip label { color: " text "; }
scrollbar { background: none; border: none; }
scrollbar slider {
  background-color: " (str "alpha(" text ", 0.22)") ";
  border-radius: 99px; min-width: 6px; min-height: 6px; border: none;
}
scrollbar slider:hover { background-color: " (str "alpha(" text ", 0.40)") "; }
paned > separator { background: none; background-image: none; min-width: 8px; min-height: 8px; border: none; box-shadow: none; }
separator { background-color: " panel-border "; }

/* --- welcome, banners, status bar ---------------------------------------- */
.hero { font-size: 34px; font-weight: 900; }
.hero-sub { font-size: 16px; color: " muted "; }
.welcome-card { padding: 34px 40px; }
.empty-title { font-size: 20px; font-weight: 800; }
.big-icon { color: " accent "; }
.banner {
  padding: 12px 16px; margin: 0 8px 8px 8px; border-radius: " r-sm "px;
  background-color: " (str "alpha(" warning ", 0.14)") ";
  border: 1px solid " (str "alpha(" warning ", 0.45)") ";
}
.banner.ok { background-color: " (str "alpha(" success ", 0.12)") "; border-color: " (str "alpha(" success ", 0.40)") "; }
.banner .title { font-weight: 800; }
.cmd {
  font-family: " code-font "; font-size: 13px;
  background-color: " editor "; color: " text ";
  border-radius: 6px; padding: 6px 10px;
}
.statusbar { padding: 2px 16px 6px 16px; font-size: 12px; color: " muted "; }
.statusbar .dot-ok { color: " success "; }
.statusbar .dot-bad { color: " danger "; }
.kbd {
  font-family: " code-font "; font-size: 11px; color: " muted ";
  border: 1px solid " panel-border "; border-radius: 4px; padding: 0 4px;
}
.toast {
  background-color: " popover "; color: " text ";
  border: 1px solid " panel-border "; border-radius: 99px;
  padding: 8px 18px; margin-bottom: 40px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.35);
}
.toast.error { border-color: " danger "; }
" extra)))
