(ns lupapalvelu.attachment-test
    (:use lupapalvelu.attachment
        clojure.test
        midje.sweet))

(def ascii-pattern #"[a-zA-Z0-9\-\.]+")

(facts "Test file name encoding"
  (fact "Nil-safe"
    (encode-filename nil)     => nil)
  (fact "Short US-ASCII string is returned as is"
    (encode-filename "foo.txt")     => "foo.txt")
  (fact "Over 255 chars are truncated (Windows file limit)"
    (encode-filename (clojure.string/join (repeat 256 "x"))) => (clojure.string/join (repeat 255 "x")))
  (fact "255 chars are not truncated (Windows file limit)"
    (encode-filename (clojure.string/join (repeat 255 "y"))) => (clojure.string/join (repeat 255 "y")))
  (fact "File extension is not truncated"
    (encode-filename (str (clojure.string/join (repeat 256 "y")) ".txt")) => (contains ".txt"))
  (fact "Non-ascii letters are removed"
     (encode-filename "\u00c4\u00e4kk\u00f6si\u00e4") => (just ascii-pattern))
  (fact "Unix path separators are removed"
     (encode-filename "/root/secret") => (just ascii-pattern))
  (fact "Windows path separators are removed"
     (encode-filename "\\Windows\\cmd.exe") => (just ascii-pattern))
  (fact "Tabs are removed"
     (encode-filename "12345\t678\t90") => (just ascii-pattern))
  (fact "Newlines are removed"
     (encode-filename "12345\n678\r\n90") => (just ascii-pattern)))

(def test-attachments [{:id "1", :latestVersion {:version {:major 9, :minor 7}}}])

(facts "Test attachment-latest-version"
  (fact (attachment-latest-version test-attachments "1") => {:major 9, :minor 7})
  (fact (attachment-latest-version test-attachments "none") => nil?))

(def next-attachment-version @#'lupapalvelu.attachment/next-attachment-version)

(facts "Facts about next-attachment-version"
  (fact (next-attachment-version {:major 1 :minor 1} {:role :authority})  => {:major 1 :minor 2})
  (fact (next-attachment-version {:major 1 :minor 1} {:role :dude})       => {:major 2 :minor 0}))

(def attachment-types-for #'lupapalvelu.attachment/attachment-types-for)

(facts "Facts about attachment-types-for"
  (fact (attachment-types-for :buildingPermit) => vector?)
  (fact (first (attachment-types-for :buildingPermit)) => associative?)
  (fact (first (attachment-types-for :buildingPermit)) => {:key :hakija
                                                           :types [{:key :valtakirja}
                                                                   {:key :ote_kauppa_ja_yhdistysrekisterista}
                                                                   {:key :ote_asunto_osakeyhtion_hallituksen_kokouksen_poytakirjasta}]}))
