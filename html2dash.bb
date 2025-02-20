(ns html2dash
  (:require
   [clojure.string :as str]
   [babashka.fs :as fs]
   [babashka.pods :as pods]
   [selmer.parser :as selmer]))

(pods/load-pod 'retrogradeorbit/bootleg "0.1.9")
(require
  '[pod.retrogradeorbit.bootleg.utils :refer [convert-to]]
  '[pod.retrogradeorbit.bootleg.enlive :as enlive]
  '[pod.retrogradeorbit.hickory.select :as s]
  '[pod.retrogradeorbit.hickory.render :refer [hickory-to-html]])

(pods/load-pod 'org.babashka/go-sqlite3 "0.0.1")
(require '[pod.babashka.go-sqlite3 :as sqlite])
;; --------------------------------------------- HTML -> [cats [entries]]

(comment
  ;; AsciiDoc output structure:
  [:div.sect1 ;; Category
   [:h2 "category title"]
   [:div.sectionbody
    ;; Entry, if there is a h3 heading:
    [:div.sect2
     [:h2 "entry name"]
     [:div.paragraph "some para..."]
     [:div.literalblock "some .... ... ...."]
     [:div.ulist]
     :some-content-2]
    ;; Otherwise content for a nameless entry:
    [:div.exampleblock "content right under h2 w/o h3"]]])

(defn heading-elm->text [heading-elm]
  (first (:content heading-elm)))

(defn elm->child-elms [elm]
  (->> elm :content (remove string?)))

(defn map-container-children
  [f containers-selector elm]
  (->> elm
       (s/select containers-selector)
       (sequence (comp
                   (map elm->child-elms)
                   (map f)))))

(defn empty-entry-name? [name]
  (empty? (str/replace name #"\p{Z}" "")))

(defn entry-tuple->title+html [[heading-elm & body-elms]]
  (assert (= :h3 (:tag heading-elm)))
  (assert (seq body-elms))
  [(let [h (heading-elm->text heading-elm)]
     ;; Remove all separator characters such as &nbsp;, check:
     (if (empty-entry-name? h) "" h))
   (->> body-elms
        (map hickory-to-html)
        (str/join "\n"))])

(defn category-tuple->title+entries [[heading-elm body-elm]]
  (assert (= :h2 (:tag heading-elm)))
  (assert (-> body-elm :attrs :class #{"sectionbody"}))
  [(heading-elm->text heading-elm)
   (map-container-children
     entry-tuple->title+html
     (s/and (s/tag :div) (s/class :sect2))
     body-elm)])

(def hickory->categories
  (partial map-container-children
    category-tuple->title+entries
    (s/and (s/tag :div) (s/class :sect1))))

(def html (slurp "web-cheatsheet.html"))
(def hickory (convert-to html :hickory)) ; = (->> html hick/parse hick/as-hickory)
(comment (def cheatsheet-data (hickory->categories hickory)))

(defn cheatsheet-data->index-triples
  [cheatsheet-data]
  (->> cheatsheet-data
       (mapcat
         (fn [[category-id entries]]
           (conj
             (map (fn [[entry-name]]
                    [entry-name :Entry (format "index.html#//dash_ref_%s/Entry/%s/0"
                                         category-id entry-name)])
               entries)
             [category-id :Category (format "index.html#//dash_ref/Category/%s/1" category-id)])))))

(defn init-index! [index-file]
  (sqlite/execute! index-file
    ["create table searchIndex(id integer primary key, name TEXT, type TEXT, path TEXT)"])
  index-file)

(defn build-index! [index-file cheatsheet-data]
  (sqlite/execute! index-file
    ["insert into searchIndex(name, type, path) values (?,?,?)"
     "AsciiDoctor" "Category" "index.html"])
  (doseq [index-triple (cheatsheet-data->index-triples cheatsheet-data)]
    (sqlite/execute! index-file
      (into ["insert into searchIndex(name, type, path) values (?,?,?)"]
        (map name index-triple))))
  index-file)

;; --------------------- do it all
(let [docset-root      (fs/file "AsciiDoctor.docset")
      docset-contents  (fs/file docset-root "Contents")
      docset-resources (fs/file docset-contents "Resources")
      docset-docs      (fs/file docset-resources "Documents")
      html-file        (fs/file docset-docs "index.html")
      index-file       (fs/file docset-resources "docSet.dsidx")
      cheatsheet-data (hickory->categories hickory)]
  (selmer/set-resource-path! (System/getProperty "user.dir"))

  (when (fs/directory? docset-root)
    (fs/delete-tree docset-root))
  (fs/create-dirs docset-docs)
  (fs/copy-tree "resources/Contents" docset-contents)

  (spit html-file
    (selmer/render-file "./cheatsheet.template.html" {:categories cheatsheet-data}))

  (printf "DocSet file `%s` written\n" html-file)

  (-> (str (fs/path index-file))
      (init-index!)
      (build-index! cheatsheet-data))

  (printf "Index file `%s` written\n" index-file))

(comment
  
  (sqlite/query 
    index-file
    ;"AsciiDoctor.docset//Contents/Resources/docSet.dsidx" 
    ["select * from searchIndex"])
  
  (selmer/cache-off!)
  )