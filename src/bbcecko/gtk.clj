(ns bbcecko.gtk
  "Raw FFI bindings bbcecko needs beyond what gtkiccup.ffi has: GtkSourceView 5,
   the text buffer API (iterators, marks, markup), paned, popover, window
   handle and controls, the folder dialog, clipboard and trash.

   GtkTextIter and GdkRGBA are structs the caller owns. C code keeps them on the
   stack; here they are allocated in a confined arena for the length of one
   call chain (`with-iter`, `rgba`)."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [babashka.fs :as fs]
            [bbcecko.platform :as platform]
            [gtkiccup.ffi :as g]))

(defn- load-lib!
  "Like gtkiccup's loader, plus Homebrew's lib folders on a Mac, where the
   dylib may not be on the default search path."
  [short abi]
  (try
    (g/load-gtk-library short abi)
    (catch Throwable e
      (if-let [f (when platform/mac?
                   (first (for [prefix platform/brew-prefixes
                                name [(str "lib" short ".dylib") (str "lib" short "." abi ".dylib")]
                                :let [f (str prefix "/lib/" name)]
                                :when (fs/exists? f)]
                            f)))]
        (ffi/load-library f)
        (throw e)))))

(load-lib! "gtksourceview-5" 0)
(load-lib! "gio-2.0" 0)

;; -- memory ------------------------------------------------------------------

(def ^:private iter-size
  "sizeof(GtkTextIter) is 80 on 64-bit. A little slack costs nothing."
  128)

(defmacro with-iters
  "Binds each name to a fresh GtkTextIter, valid inside body only."
  [names & body]
  (let [arena (gensym "arena")]
    `(with-open [~arena (ffi/confined-arena)]
       (let [~@(mapcat (fn [n] [n `(ffi/alloc ~arena ~iter-size 8)]) names)]
         ~@body))))

(defcfn free "g_free" [:pointer] :void)
(defcfn rgba-parse "gdk_rgba_parse" [:pointer :string] :int)

(defn ptr->string-free
  "Reads a C string the callee allocated for us, then frees it."
  [p]
  (when-not (g/null? p)
    (let [s (ffi/ptr->string p)]
      (free p)
      s)))

;; -- GtkSourceView -----------------------------------------------------------

(defcfn source-init "gtk_source_init" [] :void)
(defcfn lang-manager-default "gtk_source_language_manager_get_default" [] :pointer)
(defcfn lang-manager-get-language "gtk_source_language_manager_get_language"
  [:pointer :string] :pointer)
(defcfn scheme-manager-default "gtk_source_style_scheme_manager_get_default" [] :pointer)
(defcfn scheme-manager-prepend-search-path "gtk_source_style_scheme_manager_prepend_search_path"
  [:pointer :string] :void)
(defcfn scheme-manager-force-rescan "gtk_source_style_scheme_manager_force_rescan" [:pointer] :void)
(defcfn scheme-manager-get-scheme "gtk_source_style_scheme_manager_get_scheme"
  [:pointer :string] :pointer)

(defcfn source-buffer-new-with-language "gtk_source_buffer_new_with_language" [:pointer] :pointer)
(defcfn source-buffer-set-language "gtk_source_buffer_set_language" [:pointer :pointer] :void)
(defcfn source-buffer-set-style-scheme "gtk_source_buffer_set_style_scheme" [:pointer :pointer] :void)
(defcfn source-buffer-set-highlight-matching-brackets
  "gtk_source_buffer_set_highlight_matching_brackets" [:pointer :int] :void)
(defcfn source-buffer-create-source-mark "gtk_source_buffer_create_source_mark"
  [:pointer :string :string :pointer] :pointer)
(defcfn source-buffer-remove-source-marks "gtk_source_buffer_remove_source_marks"
  [:pointer :pointer :pointer :string] :void)

(defcfn source-view-new "gtk_source_view_new" [] :pointer)
(defcfn source-view-set-show-line-numbers "gtk_source_view_set_show_line_numbers" [:pointer :int] :void)
(defcfn source-view-set-show-line-marks "gtk_source_view_set_show_line_marks" [:pointer :int] :void)
(defcfn source-view-set-highlight-current-line "gtk_source_view_set_highlight_current_line" [:pointer :int] :void)
(defcfn source-view-set-auto-indent "gtk_source_view_set_auto_indent" [:pointer :int] :void)
(defcfn source-view-set-indent-on-tab "gtk_source_view_set_indent_on_tab" [:pointer :int] :void)
(defcfn source-view-set-tab-width "gtk_source_view_set_tab_width" [:pointer :int] :void)
(defcfn source-view-set-indent-width "gtk_source_view_set_indent_width" [:pointer :int] :void)
(defcfn source-view-set-insert-spaces "gtk_source_view_set_insert_spaces_instead_of_tabs" [:pointer :int] :void)
(defcfn source-view-set-smart-backspace "gtk_source_view_set_smart_backspace" [:pointer :int] :void)
(defcfn source-view-set-smart-home-end "gtk_source_view_set_smart_home_end" [:pointer :int] :void)
(defcfn source-view-set-mark-attributes "gtk_source_view_set_mark_attributes"
  [:pointer :string :pointer :int] :void)

(defcfn mark-attributes-new "gtk_source_mark_attributes_new" [] :pointer)
(defcfn mark-attributes-set-background "gtk_source_mark_attributes_set_background" [:pointer :pointer] :void)
(defcfn mark-attributes-set-icon-name "gtk_source_mark_attributes_set_icon_name" [:pointer :string] :void)

;; -- GtkTextView / GtkTextBuffer --------------------------------------------

(defcfn text-view-set-buffer "gtk_text_view_set_buffer" [:pointer :pointer] :void)
(defcfn text-view-get-buffer "gtk_text_view_get_buffer" [:pointer] :pointer)
(defcfn text-view-scroll-to-mark "gtk_text_view_scroll_to_mark"
  [:pointer :pointer :double :int :double :double] :void)
(defcfn text-view-scroll-mark-onscreen "gtk_text_view_scroll_mark_onscreen" [:pointer :pointer] :void)
(defcfn text-view-set-monospace "gtk_text_view_set_monospace" [:pointer :int] :void)
(defcfn text-view-set-editable "gtk_text_view_set_editable" [:pointer :int] :void)
(defcfn text-view-set-cursor-visible "gtk_text_view_set_cursor_visible" [:pointer :int] :void)
(defcfn text-view-set-wrap-mode "gtk_text_view_set_wrap_mode" [:pointer :int] :void)
(defcfn text-view-set-left-margin "gtk_text_view_set_left_margin" [:pointer :int] :void)
(defcfn text-view-set-right-margin "gtk_text_view_set_right_margin" [:pointer :int] :void)
(defcfn text-view-set-top-margin "gtk_text_view_set_top_margin" [:pointer :int] :void)
(defcfn text-view-set-bottom-margin "gtk_text_view_set_bottom_margin" [:pointer :int] :void)
(defcfn text-view-set-pixels-below-lines "gtk_text_view_set_pixels_below_lines" [:pointer :int] :void)

(defcfn text-buffer-new "gtk_text_buffer_new" [:pointer] :pointer)
(defcfn text-buffer-set-text "gtk_text_buffer_set_text" [:pointer :string :int] :void)
(defcfn text-buffer-get-text "gtk_text_buffer_get_text" [:pointer :pointer :pointer :int] :pointer)
(defcfn text-buffer-get-start-iter "gtk_text_buffer_get_start_iter" [:pointer :pointer] :void)
(defcfn text-buffer-get-end-iter "gtk_text_buffer_get_end_iter" [:pointer :pointer] :void)
(defcfn text-buffer-get-iter-at-line "gtk_text_buffer_get_iter_at_line" [:pointer :pointer :int] :int)
(defcfn text-buffer-get-iter-at-line-offset "gtk_text_buffer_get_iter_at_line_offset"
  [:pointer :pointer :int :int] :int)
(defcfn text-buffer-get-iter-at-mark "gtk_text_buffer_get_iter_at_mark" [:pointer :pointer :pointer] :void)
(defcfn text-buffer-get-insert "gtk_text_buffer_get_insert" [:pointer] :pointer)
(defcfn text-buffer-place-cursor "gtk_text_buffer_place_cursor" [:pointer :pointer] :void)
(defcfn text-buffer-get-modified "gtk_text_buffer_get_modified" [:pointer] :int)
(defcfn text-buffer-set-modified "gtk_text_buffer_set_modified" [:pointer :int] :void)
(defcfn text-buffer-insert-markup "gtk_text_buffer_insert_markup" [:pointer :pointer :string :int] :void)
(defcfn text-buffer-create-mark "gtk_text_buffer_create_mark" [:pointer :string :pointer :int] :pointer)
(defcfn text-buffer-move-mark "gtk_text_buffer_move_mark" [:pointer :pointer :pointer] :void)
(defcfn text-buffer-begin-irreversible "gtk_text_buffer_begin_irreversible_action" [:pointer] :void)
(defcfn text-buffer-end-irreversible "gtk_text_buffer_end_irreversible_action" [:pointer] :void)
(defcfn text-buffer-get-char-count "gtk_text_buffer_get_char_count" [:pointer] :int)
(defcfn text-buffer-get-iter-at-offset "gtk_text_buffer_get_iter_at_offset" [:pointer :pointer :int] :void)
(defcfn text-buffer-delete "gtk_text_buffer_delete" [:pointer :pointer :pointer] :void)
(defcfn text-iter-get-line "gtk_text_iter_get_line" [:pointer] :int)
(defcfn text-iter-get-line-offset "gtk_text_iter_get_line_offset" [:pointer] :int)
(defcfn text-iter-forward-to-line-end "gtk_text_iter_forward_to_line_end" [:pointer] :int)

;; -- widgets -----------------------------------------------------------------

(defcfn widget-grab-focus "gtk_widget_grab_focus" [:pointer] :int)
(defcfn widget-get-clipboard "gtk_widget_get_clipboard" [:pointer] :pointer)
(defcfn clipboard-set-text "gdk_clipboard_set_text" [:pointer :string] :void)
(defcfn widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)
(defcfn widget-remove-css-class "gtk_widget_remove_css_class" [:pointer :string] :void)
(defcfn widget-set-visible "gtk_widget_set_visible" [:pointer :int] :void)
(defcfn widget-set-cursor-from-name "gtk_widget_set_cursor_from_name" [:pointer :string] :void)

(defcfn window-set-titlebar "gtk_window_set_titlebar" [:pointer :pointer] :void)
(defcfn window-set-icon-name "gtk_window_set_icon_name" [:pointer :string] :void)
(defcfn window-handle-new "gtk_window_handle_new" [] :pointer)
(defcfn window-handle-set-child "gtk_window_handle_set_child" [:pointer :pointer] :void)
(defcfn window-controls-new "gtk_window_controls_new" [:int] :pointer)

(defcfn paned-new "gtk_paned_new" [:int] :pointer)
(defcfn paned-set-start-child "gtk_paned_set_start_child" [:pointer :pointer] :void)
(defcfn paned-set-end-child "gtk_paned_set_end_child" [:pointer :pointer] :void)
(defcfn paned-set-position "gtk_paned_set_position" [:pointer :int] :void)
(defcfn paned-get-position "gtk_paned_get_position" [:pointer] :int)
(defcfn paned-set-resize-start-child "gtk_paned_set_resize_start_child" [:pointer :int] :void)
(defcfn paned-set-resize-end-child "gtk_paned_set_resize_end_child" [:pointer :int] :void)
(defcfn paned-set-shrink-start-child "gtk_paned_set_shrink_start_child" [:pointer :int] :void)
(defcfn paned-set-shrink-end-child "gtk_paned_set_shrink_end_child" [:pointer :int] :void)
(defcfn paned-set-wide-handle "gtk_paned_set_wide_handle" [:pointer :int] :void)

(defcfn popover-new "gtk_popover_new" [] :pointer)
(defcfn popover-set-child "gtk_popover_set_child" [:pointer :pointer] :void)
(defcfn popover-set-has-arrow "gtk_popover_set_has_arrow" [:pointer :int] :void)
(defcfn menu-button-new "gtk_menu_button_new" [] :pointer)
(defcfn menu-button-set-popover "gtk_menu_button_set_popover" [:pointer :pointer] :void)
(defcfn menu-button-set-child "gtk_menu_button_set_child" [:pointer :pointer] :void)
(defcfn menu-button-set-icon-name "gtk_menu_button_set_icon_name" [:pointer :string] :void)
(defcfn menu-button-set-label "gtk_menu_button_set_label" [:pointer :string] :void)
(defcfn menu-button-set-always-show-arrow "gtk_menu_button_set_always_show_arrow" [:pointer :int] :void)
(defcfn menu-button-set-has-frame "gtk_menu_button_set_has_frame" [:pointer :int] :void)
(defcfn menu-button-popdown "gtk_menu_button_popdown" [:pointer] :void)
(defcfn menu-button-get-popover "gtk_menu_button_get_popover" [:pointer] :pointer)

(defcfn box-new "gtk_box_new" [:int :int] :pointer)

;; -- GIO: files, folder dialog, trash ----------------------------------------

(defcfn file-new-for-path "g_file_new_for_path" [:string] :pointer)
(defcfn file-get-path "g_file_get_path" [:pointer] :pointer)
(defcfn file-trash "g_file_trash" [:pointer :pointer :pointer] :int)
(defcfn object-unref "g_object_unref" [:pointer] :void)

(defcfn file-dialog-new "gtk_file_dialog_new" [] :pointer)
(defcfn file-dialog-set-title "gtk_file_dialog_set_title" [:pointer :string] :void)
(defcfn file-dialog-set-initial-folder "gtk_file_dialog_set_initial_folder" [:pointer :pointer] :void)
(defcfn file-dialog-select-folder "gtk_file_dialog_select_folder"
  [:pointer :pointer :pointer :pointer :pointer] :void)
(defcfn file-dialog-select-folder-finish "gtk_file_dialog_select_folder_finish"
  [:pointer :pointer :pointer] :pointer)

(defn trash!
  "Moves a file or folder to the desktop trash. True when it worked."
  [path]
  (let [f (file-new-for-path path)]
    (try (g/<-gbool (file-trash f ffi/null ffi/null))
         (finally (object-unref f)))))

(defn choose-folder!
  "Opens the native folder chooser over `window`, starting in `initial`.
   Calls (on-pick path) on the GTK thread when the user picks a folder; a
   cancel calls nothing. The async callback lives in its own arena, closed
   once it has fired."
  [window title initial on-pick]
  (let [dialog (file-dialog-new)
        arena  (ffi/shared-arena)
        cb     (ffi/callback
                 arena
                 (fn [_src res _data]
                   (try
                     (let [f (file-dialog-select-folder-finish dialog res ffi/null)]
                       (when-not (g/null? f)
                         (let [p (ptr->string-free (file-get-path f))]
                           (object-unref f)
                           (when p (on-pick p)))))
                     (catch Throwable t
                       (binding [*out* *err*] (println "folder dialog failed:" (ex-message t))))
                     (finally
                       (object-unref dialog))))
                 [:pointer :pointer :pointer] :void)]
    (file-dialog-set-title dialog title)
    (when initial
      (let [f (file-new-for-path initial)]
        (file-dialog-set-initial-folder dialog f)
        (object-unref f)))
    (file-dialog-select-folder dialog window ffi/null cb ffi/null)
    nil))

(defn copy-to-clipboard!
  "Puts text on the clipboard of `widget`'s display."
  [widget text]
  (clipboard-set-text (widget-get-clipboard widget) text))

;; -- settings ------------------------------------------------------------------

(defcfn settings-get-default "gtk_settings_get_default" [] :pointer)
(defcfn value-init "g_value_init" [:pointer :long] :pointer)
(defcfn value-set-boolean "g_value_set_boolean" [:pointer :int] :void)
(defcfn value-unset "g_value_unset" [:pointer] :void)
(defcfn object-set-property "g_object_set_property" [:pointer :string :pointer] :void)

(def ^:private G-TYPE-BOOLEAN (bit-shift-left 5 2))

(defn set-bool-property!
  "g_object_set without varargs: one boolean property through a GValue."
  [obj prop v]
  (with-open [arena (ffi/confined-arena)]
    (let [gv (ffi/alloc arena 24 8)]
      (ffi/write gv :long 0)
      (ffi/write gv :long 0 8)
      (ffi/write gv :long 0 16)
      (value-init gv G-TYPE-BOOLEAN)
      (value-set-boolean gv (if v 1 0))
      (object-set-property obj prop gv)
      (value-unset gv))))

(defn prefer-dark!
  "Switches GTK's own widgets between their light and dark look, so anything
   our stylesheet does not cover still matches the theme."
  [dark?]
  (set-bool-property! (settings-get-default) "gtk-application-prefer-dark-theme" dark?))
