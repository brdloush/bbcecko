(ns bbcecko.desktop
  "Puts bbCéčko in the desktop's application menu and on the PATH.

     bb install     ~/.local/bin/bbcecko, a .desktop file and the icon
     bb uninstall   removes exactly that

   The icon cannot be set from a running GTK4 app on GNOME: the shell shows
   the icon of the .desktop file whose name matches the window's app id. So
   the file is named after the app id, cz.brdloush.Bbcecko."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def app-id "cz.brdloush.Bbcecko")

(defn- data-home []
  (or (System/getenv "XDG_DATA_HOME") (str (fs/home) "/.local/share")))

(defn- paths [project-dir]
  {:launcher   (str (fs/path project-dir "bbcecko"))
   :bin        (str (fs/path (fs/home) ".local" "bin" "bbcecko"))
   :desktop    (str (fs/path (data-home) "applications" (str app-id ".desktop")))
   :icon-src   (str (fs/path project-dir "icons" (str app-id ".svg")))
   :icon       (str (fs/path (data-home) "icons" "hicolor" "scalable" "apps" (str app-id ".svg")))})

(defn desktop-entry [launcher]
  (str/join "\n"
            ["[Desktop Entry]"
             "Type=Application"
             "Name=bbCéčko"
             "GenericName=C++ IDE"
             "Comment=A simple C++ editor for students"
             "Comment[cs]=Jednoduchý editor C++ pro studenty"
             (str "Exec=" launcher " %F")
             (str "Icon=" app-id)
             "Terminal=false"
             "Categories=Development;IDE;Education;"
             "Keywords=C++;cpp;programming;school;"
             "MimeType=text/x-c++src;text/x-csrc;inode/directory;"
             (str "StartupWMClass=" app-id)
             ""]))

(defn install! [project-dir]
  (let [{:keys [launcher bin desktop icon-src icon]} (paths project-dir)]
    (fs/create-dirs (fs/parent bin))
    (fs/delete-if-exists bin)
    (fs/create-sym-link bin launcher)
    (fs/create-dirs (fs/parent desktop))
    (spit desktop (desktop-entry launcher))
    (fs/create-dirs (fs/parent icon))
    (fs/copy icon-src icon {:replace-existing true})
    (try (p/shell {:continue true :out :string :err :string}
                  "update-desktop-database" (str (fs/parent desktop)))
         (catch Exception _ nil))
    (println "installed:")
    (doseq [f [bin desktop icon]] (println "  " f))
    (when-not (some #(= (str (fs/parent bin)) %)
                    (str/split (or (System/getenv "PATH") "") #":"))
      (println "note:" (str (fs/parent bin)) "is not on your PATH"))))

(defn uninstall! [project-dir]
  (let [{:keys [bin desktop icon]} (paths project-dir)]
    (doseq [f [bin desktop icon]]
      (when (fs/delete-if-exists f) (println "removed" f)))))
