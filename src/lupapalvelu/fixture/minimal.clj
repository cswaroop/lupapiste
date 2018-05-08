(ns lupapalvelu.fixture.minimal
  (:require [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :refer :all]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.organization :as org]
            [lupapalvelu.i18n :as i18n]
            [sade.core :refer :all]
            [sade.env :as env]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]))

(def now-year (t/year (tc/from-long (now))))
(def- local-krysp "http://localhost:8000/dev/krysp")
(def- local-3d-map "http://localhost:8000/dev/3dmap")
(def- local-krysp-receiver (str (env/server-address) "/dev/krysp/receiver"))

(def users
  [;; Solita admin:  admin / admin
   {:id "777777777777777777000099"
    :email "admin@solita.fi"
    :enabled true
    :role "admin"
    :language "fi"
    :firstName "Admin"
    :lastName "Admin"
    :phone "03030303"
    :username "admin"
    :private {:password "$2a$10$WHPur/hjvaOTlm41VFjtjuPI5hBoIMm8Y1p2vL4KqRi7QUvHMS1Ie"
              :apikey "5087ba34c2e667024fbd5992"}}

   ;; ETL export user: solita-etl / solita-etl
   {:id "solita-etl"
    :username "solita-etl"
    :email "etl@lupapiste.fi"
    :firstName "Solita"
    :lastName "DW ETL-lataus"
    :language "fi"
    :enabled true
    :role "trusted-etl"
    :private {:password "$2a$10$uog/cI4n4vxFBNgku4xTpu6lcrF56cttBDW5zkTfDaSClgEw54/Nm"}}

   ;; Salesforce export user: salesforce-etl / salesforce-etl
   {:id "salesforce-etl"
    :username "salesforce-etl"
    :email "sf-etl@lupapiste.fi"
    :firstName "Solita"
    :lastName "SF ETL-lataus"
    :language "fi"
    :enabled true
    :role "trusted-salesforce"
    :private {:password "$2a$10$9PjOuMzuY/5oIpKR4PVACOWn2AFrwhTT2xtDe5sFXlJwRmk.6T6ji"}}

   ;; Tampere

   ;; Tampere YA paakayttaja:  tampere-ya / tampere
   {:id "837-YA"
    :enabled true
    :lastName "Tampere"
    :firstName "Paakayttaja-YA"
    :city "Tampere"
    :language "fi"
    :username "tampere-ya"
    :street "Paapankuja 12"
    :phone "0102030405"
    :email "tampere-ya@example.com"
    :role "authorityAdmin"
    :zip "10203"
    :orgAuthz {:837-YA #{:authorityAdmin}}
    :private {:password "$2a$10$hkJ5ZQhqL66iM2.3m4712eDIH1K1Ez6wp7FeV9DTkPCNEZz8IfrAe" :apikey "tampereYAapikey"}}

   {:id "837-R"                                             ; tampere / tampere
    :enabled true
    :lastName "Tampere"
    :firstName "Paakayttaja"
    :city "Tampere"
    :language "fi"
    :username "tampere"
    :street "Paapankuja 12"
    :phone "0102030405"
    :email "tampere@example.com"
    :role "authorityAdmin"
    :zip "10203"
    :orgAuthz {:837-R #{:authorityAdmin}}
    :private {:password "$2a$10$hkJ5ZQhqL66iM2.3m4712eDIH1K1Ez6wp7FeV9DTkPCNEZz8IfrAe" :apikey "tampereapikey"}}

   ;; Veikko Viranomainen - tamperelainen Lupa-arkkitehti:  veikko / veikko
   {:id "777777777777777777000016"
    :email "veikko.viranomainen@tampere.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:837-R #{:authority :approver}}
    :firstName "Veikko"
    :lastName "Viranomainen"
    :phone "03121991"
    :username "veikko"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuLF5AQqkSO5S1DJOgziMep.xJLYm3.xG"
              :apikey "5051ba0caa2480f374dcfeff"}}

   ;; Jussi Viranomainen - tamperelainen YA-lupa-arkkitehti:  jussi / jussi
   {:id "777777777777777777000017"
    :email "jussi.viranomainen@tampere.fi"
    :enabled true
    :role "authority"
    :language "fi"
    :username "jussi"
    :orgAuthz {:837-YA #{:authority}}
    :firstName "Jussi"
    :lastName "Viranomainen"
    :phone "1231234567"
    :street "Katuosoite 1 a 1"
    :zip "33456"
    :city "Tampere"
    :private {:password "$2a$10$Wl49diVWkO6UpBABzjYR4e8zTwIJBDKiEyvw1O2EMOtV9fqHaXPZq"
              :apikey "5051ba0caa2480f374dcfefg"}}

   ;; Kuopio

   ;; Sakari Viranomainen - Kuopion YA-lupa-arkkitehti:  sakari / sakari
   {:id "77777777777777777700669"
    :email "sakari.viranomainen@kuopio.fi"
    :enabled true
    :role "authority"
    :language "fi"
    :username "sakari"
    :orgAuthz {:297-YA #{:authority}}
    :firstName "Sakari"
    :lastName "Viranomainen"
    :phone "1231234567"
    :street "Katuosoite 1 a 1"
    :zip "33456"
    :city "Kuopio"
    :private {:password "$2a$10$VnwROer5dhRJCQxoZusOney/hyN7Vk4ILQMSVqT8iZMO4XiQz.8Cm"}}

   ;; Kuopio YA-paakayttaja:  kuopio-ya / kuopio
   {:id "297-YA"
    :enabled true
    :lastName "Kuopio"
    :firstName "Paakayttaja-YA"
    :city "Kuopio"
    :language "fi"
    :username "kuopio-ya"
    :street "Paapankuja 12"
    :phone "0102030405"
    :email "kuopio-ya@example.com"
    :role "authorityAdmin"
    :zip "10203"
    :orgAuthz {:297-YA #{:authorityAdmin}}
    :private {:password "$2a$10$YceveAiQXbeUs65B4FZ6lez/itf0UEXooHcZlygI2WnQGhF0dJ1jO"}}

   ;; Velho Viranomainen - Kuopio R viranomaien:  velho / velho
   {:id "77777777777777777700645"
    :email "velho.viranomainen@kuopio.fi"
    :enabled true
    :role "authority"
    :username "velho"
    :language "fi"
    :orgAuthz {:297-R #{:authority :approver}
               :297-YA #{:authority :approver}}
    :firstName "Velho"
    :lastName "Viranomainen"
    :phone "1231234567"
    :street "Katuosoite 2 a 4"
    :zip "33456"
    :personId "180495-754N"
    :personIdSource "identification-service"
    :city "Kuopio"
    :private {:password "$2a$10$me2UOXOUfEbseJeLUBde8u2rlqOwHuqxbFT00q70QEvTpskHKol2m"
              :apikey   "e1vshYravGWKA1QXL3NeWMmyzzBJmcgq6IUqKZmh"}}

   ;; Kuopio R-paakayttaja:  kuopio-r / kuopio

   {:id "297-R"
    :enabled true
    :lastName "Kuopio"
    :firstName "Paakayttaja-R"
    :city "Kuopio"
    :language "fi"
    :username "kuopio-r"
    :street "Paapankuja 12"
    :phone "0102030405"
    :email "kuopio-r@kuopio.fi"
    :role "authorityAdmin"
    :zip "10203"
    :orgAuthz {:297-R #{:authorityAdmin}}
    :private {:password "$2a$10$YceveAiQXbeUs65B4FZ6lez/itf0UEXooHcZlygI2WnQGhF0dJ1jO"
              :apikey   "lhIqT1YwOMH8HuiCGcjBtGggfeRaxZL5OUNd3r4u"}}

   ;; Sipoo

   ;; Simo Suurvisiiri - Sipoon R paakayttaja:  sipoo / sipoo
   {:id "50ac77ecc2e6c2ea6e73f83e"
    :email "admin@sipoo.fi"
    :enabled true
    :role "authorityAdmin"
    :orgAuthz {:753-R #{:authorityAdmin}}
    :firstName "Simo"
    :language "fi"
    :lastName "Suurvisiiri"
    :username "sipoo"
    :private {:password "$2a$10$VFcksPILCd9ykyl.1FIhwO/tEYby9SsqZL7GsIAdpJ1XGvAG2KskG"
              :apikey "50ac788ec2e6c2ea6e73f83f"}}

   ;; Simo YA-Suurvisiiri - Sipoon YA paakayttaja:  sipoo-ya / sipoo
   {:id "50ac77eaf2e6c2ea6e73f81e"
    :email "admin-ya@sipoo.fi"
    :enabled true
    :role "authorityAdmin"
    :orgAuthz {:753-YA #{:authorityAdmin}}
    :firstName "Simo"
    :language "fi"
    :lastName "YA-Suurvisiiri"
    :username "sipoo-ya"
    :private {:password "$2a$10$VFcksPILCd9ykyl.1FIhwO/tEYby9SsqZL7GsIAdpJ1XGvAG2KskG"
              :apikey "55cdafd8abc1d91e7ccd60b2"}}

   ;; Sonja Sibbo - Sipoon lupa-arkkitehti:  sonja / sonja
   {:id "777777777777777777000023"
    :username "sonja"
    :role "authority"
    :enabled true
    :email "sonja.sibbo@sipoo.fi"
    :orgAuthz {:753-R #{:authority :approver}
               :753-YA #{:authority :approver}
               :998-R-TESTI-2 #{:authority :approver}}
    :firstName "Sonja"
    :lastName "Sibbo"
    :language "fi"
    :phone "03121991"
    :street "Katuosoite 1 a 1"
    :zip "33456"
    :city "Sipoo"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuVKiwbKvNs90f80zc57FDiPnGjuMbuf2"
              :apikey "5056e6d3aa24a1c901e6b9d1"}
    :applicationFilters [{:id "foobar"
                          :title "Foobar"
                          :sort {:asc false
                                 :field "modified"}
                          :filter {:handlers []
                                   :tags []
                                   :operations []
                                   :organizations []
                                   :areas []}}
                         {:id "barfoo"
                          :title "Barfoo"
                          :sort {:asc false
                                 :field "modified"}
                          :filter {:handlers []
                                   :tags []
                                   :operations []
                                   :organizations []
                                   :areas []}}]}

   ;; Ronja Sibbo - Sipoon lupa-arkkitehti:  ronja / sonja
   {:id "777777777777777777000024"
    :username "ronja"
    :role "authority"
    :enabled true
    :language "fi"
    :email "ronja.sibbo@sipoo.fi"
    :orgAuthz {:753-R #{:authority}}
    :firstName "Ronja"
    :lastName "Sibbo"
    :phone "03121991"
    :street "Katuosoite 1 a 1"
    :zip "33456"
    :city "Sipoo"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuVKiwbKvNs90f80zc57FDiPnGjuMbuf2"
              :apikey "5056e6d3aa24a1c901e6b9dd"}}

   ;; Luukas Lukija - Sipoon katselija:  luukas / luukas
   {:id "777777777777777777000025"
    :username "luukas"
    :role "authority"
    :enabled true
    :language "fi"
    :email "luukas.lukija@sipoo.fi"
    :orgAuthz {:753-R #{:reader}}
    :firstName "Luukas"
    :lastName "Lukija"
    :phone "03121992"
    :street "Katuosoite 1 a 2"
    :zip "04130"
    :city "Sipoo"
    :private {:password "$2a$10$YM2XkcJVjM5JiqqR2qg7U.iUuY10LPYexYTfV/21RHOayn1xIf2sS"
              :apikey "5056e6d3aa24a1c901e6b9de"}}

   ;; Kosti Kommentoija - Sipoon kommentoija: kosti / kosti
   {:id "777777777777777777000026"
    :username "kosti"
    :role "authority"
    :enabled true
    :language "fi"
    :email "kosti.kommentoija@sipoo.fi"
    :orgAuthz {:753-R #{:commenter}}
    :firstName "Kosti"
    :lastName "Kommentoija"
    :phone "03121992"
    :street "Katuosoite 1 a 3"
    :zip "04130"
    :city "Sipoo"
    :private {:password "$2a$10$d2Ut/qSvKylOGhYm/7jXB..1ZC7/x39q5e/PFdtjHLqV1XW9wr3oO"
              :apikey "XDnPTeDDpPqU5yoYQEERgZ0p4H6dff1RIdYgyDCk"}}


   {:id "sipoo-r-backend"
    :username "sipoo-r-backend"
    :email "sipoo-r-backend@sipoo.fi"
    :firstName "Sipoo"
    :lastName "Taustaj\u00E4rjestelm\u00E4"
    :enabled true
    :language "fi"
    :role "rest-api"
    :private {:password "$2a$10$VFcksPILCd9ykyl.1FIhwO/tEYby9SsqZL7GsIAdpJ1XGvAG2KskG"} ;sipoo
    :orgAuthz {:753-R #{:authority}}}

   ;; Porvoo

   ;; Pekka Borga - Porvoon lupa-arkkitehti:  pekka / pekka
   {:id "777777777777777777000033"
    :email "pekka.borga@porvoo.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:638-R #{:authority :approver}}
    :firstName "Pekka"
    :lastName "Borga"
    :phone "121212"
    :username "pekka"
    :private {:password "$2a$10$C65v2OgWcCzo4SVDtofawuP8xXDnZn5.URbODSpeOWmRABxUU01k6"
              :apikey "4761896258863737181711425832653651926670"}}

   ;; Oulu

   ;; Oulu Ymp Admin - Oulun YMP paakayttaja:  ymp-admin@oulu.fi / oulu
   ;; Viranomaisena myos Naantalissa
   {:id "777777777777734777000034"
    :email "ymp-admin@oulu.fi"
    :enabled true
    :language "fi"
    :role "authorityAdmin"
    :orgAuthz {:564-YMP #{:authorityAdmin}}
    :firstName "Oulu Ymp"
    :lastName "Admin"
    :phone "121212"
    :username "ymp-admin@oulu.fi"
    :private {:password "$2a$10$JA1Ec/bEUBrKLzeZX3aKNeyXcfCtjDdWyUQPTlL0rldhFhjq5Drje"
              :apikey "YEU26a6TXHlapM18QGYST7WBYEU26a6TXHlapM18"}}

   ;; Olli Ule\u00E5borg - Oulun lupa-arkkitehti:  olli / olli
   ;; Viranomaisena myos Naantalissa
   {:id "777777777777777777000034"
    :email "olli.uleaborg@ouka.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:564-R #{:authority :approver}
               :529-R #{:authority :approver}
               :564-YMP #{:authority :approver}}
    :firstName "Olli"
    :lastName "Ule\u00E5borg"
    :phone "121212"
    :username "olli"
    :private {:password "$2a$10$JXFA55BPpNDpI/jDuPv76uW9TTgGHcDI2l5daelFcJbWvefB6THmi"
              :apikey "7634919923210010829057754770828315568705"}}

   ;; YA viranomainen
   ;; Olli Ule\u00E5borg - Oulun lupa-arkkitehti:  olli-ya / olli
   ;; Viranomaisena myos Naantalissa
   {:id "777777777777777777000035"
    :email "olliya.uleaborg@ouka.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:564-YA #{:authority :approver :archivist :tos-editor :tos-publisher}
               :564-YMP #{:authority :approver}}
    :firstName "Olli-ya"
    :lastName "Ule\u00E5borg"
    :phone "121212"
    :username "olli-ya"
    :private {:password "$2a$10$JXFA55BPpNDpI/jDuPv76uW9TTgGHcDI2l5daelFcJbWvefB6THmi"
              :apikey "7634919923210010829057754770828315568706"}}

   ;; Naantali

   ;; Naantali R paakayttaja: admin@naantali.fi / naantali
   {:id "50ac77ecd2e6c2ea6e73f83f"
    :email "admin@naantali.fi"
    :enabled true
    :role "authorityAdmin"
    :orgAuthz {:529-R #{:authorityAdmin}}
    :firstName "Admin"
    :language "fi"
    :lastName "Naantali"
    :username "admin@naantali.fi"
    :private {:password "$2a$10$4pvNDXk2g5XgxT.whx1Ua.RKkAoyjOb8C91r7aBMrgf7zNPMjhizq"
              :apikey "a0ac77ecd2e6c2ea6e73f83f"}}
   ;; rakennustarkastaja@naantali.fi / naantali
   ;; Viranomainen myos Jarvenpaassa
   {:id "50ac77ecd2e6c2ea6e73f840"
    :email "rakennustarkastaja@naantali.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:529-R #{:authority}
               :186-R #{:authority}}
    :firstName "Rakennustarkastaja"
    :lastName "Naantali"
    :username "rakennustarkastaja@naantali.fi"
    :private {:password "$2a$10$4pvNDXk2g5XgxT.whx1Ua.RKkAoyjOb8C91r7aBMrgf7zNPMjhizq"
              :apikey "a0ac77ecd2e6c2ea6e73f840"}}
   ;; lupasihteeri@naantali.fi / naantali
   {:id "50ac77ecd2e6c2ea6e73f841"
    :email "lupasihteeri@naantali.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:529-R #{:authority}}
    :firstName "Lupasihteeri"
    :lastName "Naantali"
    :username "lupasihteeri@naantali.fi"
    :private {:password "$2a$10$4pvNDXk2g5XgxT.whx1Ua.RKkAoyjOb8C91r7aBMrgf7zNPMjhizq"
              :apikey "a0ac77ecd2e6c2ea6e73f841"}}

   ;; Jarvenpaa

   ;; Jarvenpaan R paakayttaja: admin@jarvenpaa.fi / jarvenpaa
   {:id "50ac77ecd2e6c2ea6e73f850"
    :email "admin@jarvenpaa.fi"
    :enabled true
    :language "fi"
    :role "authorityAdmin"
    :orgAuthz {:186-R #{:authorityAdmin}}
    :firstName "Admin"
    :lastName "J\u00E4rvenp\u00E4\u00E4"
    :username "admin@jarvenpaa.fi"
    :private {:password "$2a$10$eYl/SxvzYzOfIDIqjQIZ8.uhi57zPKg0m8J1BHwnAIx/sBcxYojvS"
              :apikey "a0ac77ecd2e6c2ea6e73f850"}}
   ;; rakennustarkastaja@jarvenpaa.fi / jarvenpaa
   {:id "50ac77ecd2e6c2ea6e73f851"
    :email "rakennustarkastaja@jarvenpaa.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:186-R #{:authority :archivist :approver}}
    :firstName "Rakennustarkastaja"
    :lastName "J\u00E4rvenp\u00E4\u00E4"
    :username "rakennustarkastaja@jarvenpaa.fi"
    :private {:password "$2a$10$eYl/SxvzYzOfIDIqjQIZ8.uhi57zPKg0m8J1BHwnAIx/sBcxYojvS"
              :apikey "a0ac77ecd2e6c2ea6e73f851"}}
   ;; lupasihteeri@jarvenpaa.fi / jarvenpaa
   {:id "50ac77ecd2e6c2ea6e73f852"
    :email "lupasihteeri@jarvenpaa.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:186-R #{:authority}}
    :firstName "Lupasihteeri"
    :lastName "J\u00E4rvenp\u00E4\u00E4"
    :username "lupasihteeri@jarvenpaa.fi"
    :private {:password "$2a$10$eYl/SxvzYzOfIDIqjQIZ8.uhi57zPKg0m8J1BHwnAIx/sBcxYojvS"
              :apikey "a0ac77ecd2e6c2ea6e73f852"}}
   ;; digitoija@jarvenpaa.fi / jarvenpaa
   {:id "50ac77ecd2e6c2ea6e73f853"
    :email "digitoija@jarvenpaa.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:186-R #{:digitizer}}
    :firstName "Dingo"
    :lastName "Digitoija"
    :username "digitoija@jarvenpaa.fi"
    :private {:password "$2a$10$eYl/SxvzYzOfIDIqjQIZ8.uhi57zPKg0m8J1BHwnAIx/sBcxYojvS"
              :apikey "a0ac77ecd2e6c2ea6e73f853"}}
   ;; Torsti Tossavainen - Jarvenpaan TOS-vastaava: torsti / torsti
   {:id "777777777777777777000027"
    :username "torsti"
    :role "authority"
    :enabled true
    :language "fi"
    :email "torsti.tossavainen@jarvenpaa.fi"
    :orgAuthz {:186-R #{:tos-editor :tos-publisher}} ; Note that :authority is not present
    :firstName "Torsti"
    :lastName "Tossavainen"
    :private {:password "$2a$10$eo2H257MMoJHMUCxAmEXVeRTDjaEL9qPpbQQiC/kdI38lPwXutklS"
              :apikey "a0ac77ecd2e6c2ea6e73f854"}}

   {:id "jarvenpaa-backend"
    :username "jarvenpaa-backend"
    :email "jarvenpaa@example.com"
    :firstName "J\u00E4rvenp\u00E4\u00E4"
    :lastName "Taustaj\u00E4rjestelm\u00E4"
    :enabled true
    :language "fi"
    :role "rest-api"
    :private {:password "$2a$10$eYl/SxvzYzOfIDIqjQIZ8.uhi57zPKg0m8J1BHwnAIx/sBcxYojvS"} ;jarvenpaa
    :orgAuthz {:186-R #{:authority}}}

   {:id "porvoo-backend"
    :username "porvoo-backend"
    :email "porvoo@example.com"
    :firstName "Porvoo"
    :lastName "Taustaj\u00E4rjestelm\u00E4"
    :enabled true
    :language "fi"
    :role "rest-api"
    :private {}                                             ; testing autologin
    :orgAuthz {:638-R #{:authority}}}

   ;; Loppi

   ;; Arto Viranomainen - Lopen R lupa-arkkitehti:  arto / arto
   {:id "77775577777777777700769"
    :email "arto.viranomainen@loppi.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :username "arto"
    :orgAuthz {:433-R #{:authority}}
    :firstName "Arto"
    :lastName "Viranomainen"
    :phone "1231234567"
    :street "Katuosoite 1 a 1"
    :zip "33456"
    :city "Loppi"
    :private {:password "$2a$10$MX9RWSqjBocwxg1ikSp/POV8lXd6lKA4yDymRIg7.GsXdRigZxmjK"}}

   ;; Helsinki - rakennustarkastaja@hel.fi / helsinki
   {:id "5770f6ffd358719a09397a45"
    :email "rakennustarkastaja@hel.fi"
    :enabled true
    :language "fi"
    :role "authority"
    :orgAuthz {:091-R #{:authority :approver :archivist :tos-editor :tos-publisher}}
    :firstName "Hannu"
    :lastName "Helsinki"
    :username "rakennustarkastaja@hel.fi"
    :private {:password "$2a$10$/bi569g4ijAitS82ES9MO.TDqGZrNlBrBPC1rE6N8v7uqTJbiHTNW"
              :apikey "a0ac77ecd2e6c2ea6e73f860"}}

   ;; Helsinki Admin - Helsinki R paakayttaja:  helsinki / helsinki
   {:id "50ac77ecc2e6c2ea6e73f665"
    :email "admin@hel.fi"
    :enabled true
    :role "authorityAdmin"
    :orgAuthz {:091-R #{:authorityAdmin}}
    :firstName "Heikki"
    :language "fi"
    :lastName "Helsinki"
    :username "helsinki"
    :private {:password "$2a$10$/bi569g4ijAitS82ES9MO.TDqGZrNlBrBPC1rE6N8v7uqTJbiHTNW"
              :apikey "50ac788ec2e6c2ea6e73f665"}}

   ;; Hakijat

   ;; Hakija: Mikko's neighbour - teppo@example.com / teppo69
   {:lastName "Nieminen"
    :firstName "Teppo"
    :enabled true
    :language "fi"
    :username "teppo@example.com"
    :private {:password "$2a$10$KKBZSYTFTEFlRrQPa.PYPe9wz4q1sRvjgEUCG7gt8YBXoYwCihIgG"
              :apikey "502cb9e58426c613c8b85abb"}
    :phone "0505503171"
    :email "teppo@example.com"
    :personId "210281-0002"
    :personIdSource "identification-service"
    :role "applicant"
    :id "5073c0a1c2e6c470aef589a5"
    :allowDirectMarketing true
    :street "Mutakatu 7"
    :zip "33560"
    :city "Tampere"}

   ;; sven@example.com / sven
   ;; Svens's language is omitted in order to test the implicit
   ;; language setting.
   {:role "applicant",
    :email "sven@example.com",
    :personId "070842-559U",
    :personIdSource "identification-service"
    :private {:apikey "bfxLwCerNjNUpmJ2HqZbfxLwCerNjNUpmJ2HqZ",
              :password "$2a$10$i8O320oYo76R6QoV6bh5MunFXeNy.FcS/xqTOKwLnxQOLBg721Ouy"},
    :phone "0505504444",
    :city "Tampere",
    :username "sven@example.com",
    :firstName "Sven",
    :street "Ericsgatan 8",
    :allowDirectMarketing true,
    :zip "33310",
    :id "578731b78ca8231afeca99e8",
    :lastName "Svensson",
    :enabled true}

   ;; Hakija: Mikko Intonen - mikko@example.com / mikko123
   {:id "777777777777777777000010"
    :username "mikko@example.com"
    :enabled true
    :language "fi"
    :role "applicant"
    :personId "210281-9988" ; = Nordea demo
    :personIdSource "identification-service"
    :firstName "Mikko"
    :lastName "Intonen"
    :email "mikko@example.com"
    :street "Rambokuja 6"
    :zip "55550"
    :city "Sipoo"
    :phone "0505503171"
    :architect true
    :degree "kirvesmies"
    :graduatingYear "2000"
    :companyName "Yritys Oy"
    :companyId "1234567-1"
    :fise "f"
    :fiseKelpoisuus "tavanomainen p\u00e4\u00e4suunnittelu (uudisrakentaminen)"
    :allowDirectMarketing false
    :private {:password "$2a$10$sVFCAX/MB7wDKA2aNp1greq7QlHCU/r3WykMX/JKMWmg7d1cp7HSq"
              :apikey "502cb9e58426c613c8b85abc"}}
   ;; Hakija: pena / pena
   {:id "777777777777777777000020"
    :username "pena"
    :enabled true
    :language "fi"
    :role "applicant"
    :personId "010203-040A"
    :personIdSource "identification-service"
    :firstName "Pena"
    :lastName "Panaani"
    :email "pena@example.com"
    :street "Paapankuja 12"
    :zip "10203"
    :city "Piippola"
    :phone "0102030405"
    :allowDirectMarketing true
    :private {:password "$2a$10$hLCt8BvzrJScTOGQcXJ34ea5ovSfS5b/4X0OAmPbfcs/x3hAqEDxy"
              :apikey "502cb9e58426c613c8b85abd"}}

   ;; Dummy Hakijat

   ;; Dummy hakija 1: dummy / pena
   {:id  "51112424c26b7342d92acf3c"
    :enabled  false
    :language "fi"
    :username  "dummy"
    :firstName "Duff"
    :lastName "Dummy"
    :email  "dummy@example.com"
    :private {:apikey "602cb9e58426c613c8b85abe"} ; Dummy user has apikey, should not actually happen
    :role "dummy"}
   ;; Dummy hakija 2: dummy2 / pena
   {:id  "51112424c26b7342d92acf3d"
    :enabled  false
    :language "fi"
    :username  "dummy2"
    :firstName "Duff2"
    :lastName "Dummy2"
    :email  "dummy2@example.com"
    :private {}
    :role "dummy"}
   ;; Dummy hakija 3: dummy3 / pena
   {:id  "51112424c26b7342d92acf3e"
    :enabled  false
    :language "fi"
    :username  "dummy3"
    :firstName ""
    :lastName ""
    :email  "dummy3@example.com"
    :private {}
    :role "dummy"}

   ;; Solita company admin
   ;; kaino@solita.fi  / kaino123
   {:id "kainosolita"
    :enabled true
    :language "fi"
    :username "kaino@solita.fi" ;
    :firstName "Kaino"
    :lastName "Solita"
    :email "kaino@solita.fi"
    :street "Sensorintie 7"
    :zip "12345"
    :city "Forssa"
    :private {:password "$2a$10$QjKZTnJy77sxiWaBKR0jQezFf1LSpKfg/sljmsSq4YIq05HRZI.l."
              :apikey "502cb9e58426c613c8b85abe"}
    :role "applicant"
    :architect true
    :company {:id "solita" :role "admin" :submit true}}

   ;; Esimerkki company admin
   ;; erkki@example.com / esimerkki
   {:id "erkkiesimerkki"
    :enabled true
    :language "fi"
    :username "erkki@example.com" ;
    :firstName "Erkki"
    :lastName "Esimerkki"
    :personId "010203-041B"
    :personIdSource "identification-service"
    :phone "556677"
    :email "erkki@example.com"
    :street "Merkintie 88"
    :zip "12345"
    :city "Humppila"
    :private {:password "$2a$10$XzjXRA80jV.O3v35cOmtc.Teqqk.1.d8rBd3P52UCfa2C/oiVeeVG"
              :apikey "502cb9e58426c613c8b85eba"}
    :role "applicant"
    :architect true
    :company {:id "esimerkki" :role "admin" :submit true}}

   ;; Docstore
   {:id "docstore"
    :username "docstore"
    :email "docstore@lupapiste.fi"
    :firstName "Docstore"
    :lastName "API-user"
    :enabled true
    :language "fi"
    :role "docstore-api"
    :private {:password "$2a$10$LqhU/xPaLEsiPYkIJlT3UuBkzZ0wJyLr.0NBcOAlaP4/DW7AHbeGy"} ; basicauth
    :oauth {:client-id "docstore"
            :client-secret "docstore"
            :scopes ["read" "pay"]
            :display-name {:fi "Lupapiste kauppa"
                           :sv "Lupapiste butik"
                           :en "Lupapiste store"}
            :callback-url "http://localhost:8000"}}

   ;; Onkalo
   {:id "onkalo"
    :username "onkalo"
    :email "onkalo@lupapiste.fi"
    :firstName "Onkalo"
    :lastName "API-user"
    :enabled true
    :language "fi"
    :role "onkalo-api"
    :private {:password "$2a$10$LqhU/xPaLEsiPYkIJlT3UuBkzZ0wJyLr.0NBcOAlaP4/DW7AHbeGy"}} ; basicauth

   {:id "tiedonohjausjarjestelma"
    :username "tiedonohjausjarjestelma"
    :email "tiedonohjausjarjestelma@lupapiste.fi"
    :firstName "TOJ"
    :lastName "API-user"
    :enabled true
    :language "fi"
    :role "rest-api"
    :private {:apikey "tojTOJtojTOJtojTOJtoj"}}

   ;; Solita admin:  financial / admin
   {:id "financial"
    :email "financial@ara.fi"
    :enabled true
    :role "financialAuthority"
    :language "fi"
    :firstName "ARA-k\u00e4sittelij\u00e4"
    :lastName ""
    :phone ""
    :username "financial"
    :private {:password "$2a$10$WHPur/hjvaOTlm41VFjtjuPI5hBoIMm8Y1p2vL4KqRi7QUvHMS1Ie"
              :apikey "5087ba34c2e667024fbd5999"}}
   ])

(def- ya-default-attachments-for-operations {:ya-kayttolupa-tapahtumat                                          [[:muut :muu]]
                                            :ya-kayttolupa-harrastustoiminnan-jarjestaminen                    [[:muut :muu]]
                                            :ya-kayttolupa-metsastys                                           [[:muut :muu]]
                                            :ya-kayttolupa-vesistoluvat                                        [[:muut :muu]]
                                            :ya-kayttolupa-terassit                                            [[:muut :muu]]
                                            :ya-kayttolupa-kioskit                                             [[:muut :muu]]
                                            :ya-kayttolupa-muu-kayttolupa                                      [[:muut :muu]]
                                            :ya-kayttolupa-nostotyot                                           [[:muut :muu]]
                                            :ya-kayttolupa-vaihtolavat                                         [[:muut :muu]]
                                            :ya-kayttolupa-kattolumien-pudotustyot                             [[:muut :muu]]
                                            :ya-katulupa-muu-liikennealuetyo                                   [[:muut :muu]]
                                            :ya-kayttolupa-talon-julkisivutyot                                 [[:muut :muu]]
                                            :ya-kayttolupa-talon-rakennustyot                                  [[:muut :muu]]
                                            :ya-kayttolupa-muu-tyomaakaytto                                    [[:muut :muu]]
                                            :ya-kayttolupa-mainostus-ja-viitoitus                              [[:muut :muu]]
                                            :ya-katulupa-vesi-ja-viemarityot                                   [[:muut :muu]]
                                            :ya-katulupa-kaukolampotyot                                        [[:muut :muu]]
                                            :ya-katulupa-kaapelityot                                           [[:muut :muu]]
                                            :ya-katulupa-kiinteiston-johto-kaapeli-ja-putkiliitynnat           [[:muut :muu]]
                                            :ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen             [[:muut :muu]]
                                            :ya-sijoituslupa-maalampoputkien-sijoittaminen                     [[:muut :muu]]
                                            :ya-sijoituslupa-sahko-data-ja-muiden-kaapelien-sijoittaminen      [[:muut :muu]]
                                            :ya-sijoituslupa-rakennuksen-tai-sen-osan-sijoittaminen            [[:muut :muu]]
                                            :ya-sijoituslupa-ilmajohtojen-sijoittaminen                        [[:muut :muu]]
                                            :ya-sijoituslupa-muuntamoiden-sijoittaminen                        [[:muut :muu]]
                                            :ya-sijoituslupa-jatekatoksien-sijoittaminen                       [[:muut :muu]]
                                            :ya-sijoituslupa-leikkipaikan-tai-koiratarhan-sijoittaminen        [[:muut :muu]]
                                            :ya-sijoituslupa-rakennuksen-pelastuspaikan-sijoittaminen          [[:muut :muu]]
                                            :ya-sijoituslupa-muu-sijoituslupa                                  [[:muut :muu]]
                                            :ya-jatkoaika                                                      [[:muut :muu]]})

(def- default-keys-for-organizations {:app-required-fields-filling-obligatory false
                                      :validate-verdict-given-date true
                                      :kopiolaitos-email nil
                                      :kopiolaitos-orderer-address nil
                                      :kopiolaitos-orderer-email nil
                                      :kopiolaitos-orderer-phone nil
                                      :calendars-enabled false
                                      :use-attachment-links-integration false
                                      :inspection-summaries-enabled false
                                      :docstore-info org/default-docstore-info})

(defn- names [names-map]
  (i18n/with-default-localization names-map (:fi names-map)))

(defn- link [link-names link-url]
  {:name (names link-names)
   :url (i18n/with-default-localization {:fi link-url} link-url)})

(def organizations (map
                     (partial merge default-keys-for-organizations)
                     [;; Jarvenpaa R
                      {:id "186-R"
                       :name (names {:fi "J\u00E4rvenp\u00E4\u00E4n rakennusvalvonta"
                                    :sv "J\u00E4rvenp\u00E4\u00E4n rakennusvalvonta"})
                       :scope [{:municipality "186"
                                :permitType "R"
                                :inforequest-enabled true
                                :new-application-enabled true}]
                       :links [(link {:fi "J\u00E4rvenp\u00E4\u00E4" :sv "Tr\u00E4skenda"}
                                     "http://www.jarvenpaa.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://www.jarvenpaa.fi/sivu/index.tmpl?sivu_id=182" )]
                       :operations-attachments {:kerrostalo-rivitalo [[:paapiirustus :asemapiirros]
                                                                      [:paapiirustus :pohjapiirustus]
                                                                      [:hakija :valtakirja]
                                                                      [:pelastusviranomaiselle_esitettavat_suunnitelmat :vaestonsuojasuunnitelma]]}
                       :krysp {:R {:url local-krysp :version "2.1.3" :ftpUser "dev_jarvenpaa"}}
                       :handler-roles [{:id "abba11111111111111111186"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :assignments-enabled true
                       :inspection-summaries-enabled true
                       :permanent-archive-enabled true
                       :digitizer-tools-enabled true
                       :permanent-archive-in-use-since 1451613600000
                       :earliest-allowed-archiving-date 0
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Sipoo R
                      {:id "753-R"
                       :name (names {:fi "Sipoon rakennusvalvonta"
                                     :sv "Sipoon rakennusvalvonta"})
                       :scope [{:municipality "753"
                                :permitType "R"
                                :inforequest-enabled true
                                :new-application-enabled true
                                :bulletins {:enabled true
                                            :url "http://localhost:8000/dev/julkipano"
                                            :notification-email "sonja.sibbo@sipoo.fi"
                                            :descriptions-from-backend-system false}}
                               {:municipality "753" :permitType "P" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "YM" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "YI" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "YL" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "MAL" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "VVVL" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "KT" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "753" :permitType "MM" :inforequest-enabled true :new-application-enabled true}]
                       :links [(link {:fi "Sipoo", :sv "Sibbo"}
                                     "http://sipoo.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://sipoo.fi/fi/palvelut/asuminen_ja_rakentaminen/rakennusvalvonta")]
                       :operations-attachments {:kerrostalo-rivitalo [[:paapiirustus :asemapiirros]
                                                                      [:paapiirustus :pohjapiirustus]
                                                                      [:hakija :valtakirja]
                                                                      [:pelastusviranomaiselle_esitettavat_suunnitelmat :vaestonsuojasuunnitelma]]
                                                :vapaa-ajan-asuinrakennus [[:paapiirustus :pohjapiirustus]
                                                                           [:hakija :ote_kauppa_ja_yhdistysrekisterista]
                                                                           [:pelastusviranomaiselle_esitettavat_suunnitelmat :vaestonsuojasuunnitelma]
                                                                           [:suunnitelmat :valaistussuunnitelma]]
                                                :poikkeamis [[:paapiirustus :asemapiirros]]
                                                :meluilmoitus [[:kartat :kartta-melun-ja-tarinan-leviamisesta]
                                                               [:muut :muu]]
                                                :yl-uusi-toiminta [[:muut :muu]]
                                                :maa-aineslupa [[:muut :muu]]
                                                "vvvl-vesijohdosta" [[:muut :muu]]}
                       :krysp {:R {:url local-krysp, :ftpUser "dev_sipoo", :version "2.2.2"}
                               :P {:url local-krysp :ftpUser "dev_poik_sipoo" :version "2.1.2"}
                               :YI {:url local-krysp :ftpUser "dev_ymp_sipoo" :version "2.2.1"}
                               :YL {:url local-krysp, :ftpUser "dev_ymp_sipoo", :version "2.2.1"}
                               :MAL {:url local-krysp, :ftpUser "dev_ymp_sipoo", :version "2.2.1"}
                               :VVVL {:url local-krysp, :ftpUser "dev_ymp_sipoo", :version "2.2.1"}
                               :KT {:url local-krysp, :ftpUser "dev_ymp_sipoo", :version "1.0.2"}
                               :MM {:url local-krysp, :ftpUser "dev_ymp_sipoo", :version "1.0.1"}}
                       :statementGivers [{:id "516560d6c2e6f603beb85147"
                                          :text "Paloviranomainen",
                                          :email "sonja.sibbo@sipoo.fi",
                                          :name "Sonja Sibbo"}]
                       :handler-roles [{:id "abba1111111111111111acdc"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}
                                       {:id "abba1111111111111112acdc"
                                        :name {:fi "KVV-K\u00e4sittelij\u00e4"
                                               :sv "KVV-Handl\u00e4ggare"
                                               :en "KVV-Handler"}}]
                       :assignment-triggers [{:id "dead1111111111111111beef"
                                              :targets ["ennakkoluvat_ja_lausunnot.elyn_tai_kunnan_poikkeamapaatos"
                                                        "ennakkoluvat_ja_lausunnot.naapurin_suostumus"]
                                              :description "ELY ja naapuri"}
                                             {:id "dead1111111111111112beef"
                                              :targets ["paapiirustus.aitapiirustus"
                                                        "paapiirustus.asemapiirros"]
                                              :description "Aita ja asema"
                                              :handlerRole {:id "abba1111111111111111acdc"
                                                            :name {:fi "K\u00e4sittelij\u00e4"
                                                                   :sv "Handl\u00e4ggare"
                                                                   :en "Handler"}}}]
                       :kopiolaitos-email "sipoo@example.com"
                       :kopiolaitos-orderer-address "Testikatu 2, 12345 Sipoo"
                       :kopiolaitos-orderer-email "tilaaja@example.com"
                       :kopiolaitos-orderer-phone "0501231234"
                       :calendars-enabled true
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R" "P" "YI" "YL" "YM" "MAL" "VVVL" "KT" "MM"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :permanent-archive-in-use-since 1451613600000
                       :earliest-allowed-archiving-date 0
                       :tags [{:id "111111111111111111111111" :label "yl\u00E4maa"} {:id "222222222222222222222222" :label "ullakko"}]
                       :assignments-enabled true
                       :areas {:type "FeatureCollection"
                               :features [{:id "sipoo_keskusta",
                                           :properties {:nimi "Keskusta", :id 3},
                                           :geometry
                                           {:coordinates
                                            [[[[402644.2941 6693912.6002]
                                               [401799.0131 6696356.5649]
                                               [406135.6722 6695272.4001]
                                               [406245.9263 6693673.7164]
                                               [404059.221 6693545.0867]
                                               [404059.221 6693545.0867]
                                               [402644.2941 6693912.6002]]]],
                                            :type "MultiPolygon"},
                                           :type "Feature"}]}
                       :areas-wgs84 {:type "FeatureCollection"
                                     :features [{:id "sipoo_keskusta"
                                                 :properties { :id 3, :nimi "Keskusta"}
                                                 :geometry
                                                 {:coordinates
                                                        [[[[25.2346903951971, 60.3699135383472]
                                                           [25.218174131471, 60.3916425519997]
                                                           [25.2973279375514, 60.382941834376]
                                                           [25.3000746506076, 60.3686195326001]
                                                           [25.2605085377224, 60.3669529618652]
                                                           [25.2605085377224, 60.3669529618652]
                                                           [25.2346903951971, 60.3699135383472]]]]
                                                  :type "MultiPolygon"}
                                                 :type "Feature"}]}
                       ;; Admin admin enforces HTTPS requirement for
                       ;; 3D map server backend. Thus, the development
                       ;; backend must be set in the minimal
                       :3d-map {:enabled true
                                :server {:url local-3d-map
                                         :username "3dmap"
                                         :password "Xma8r8GMkPvibmg9PoclOA=="
                                         :crypto-iv "vOztjZQ8O2Szk8uI13844g=="}
                                }
                       :stamps [{:id "123456789012345678901234"
                                 :name "Oletusleima"
                                 :position {:x 10 :y 200}
                                 :background 0
                                 :page :first
                                 :qrCode true
                                 :rows [[{:type :custom-text :text "Hyv\u00e4ksytty"} {:type "current-date"}]
                                        [{:type :backend-id} {:type :section}]
                                        [{:type :organization}]]}
                                {:id "112233445566778899001122"
                                 :name "KV-leima"
                                 :position {:x 10 :y 10}
                                 :background 0
                                 :page :all
                                 :qrCode true
                                 :rows [[{:type :custom-text :text "Custom text"} {:type "current-date"}]
                                        [{:type :extra-text :text "Extra text"}]
                                        [{:type :application-id} {:type :backend-id}]
                                        [{:type :user}]
                                        [{:type :organization}]]}]
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true
                       ; NB! Setting pate-enabled to true WILL BREAK (robot) tests
                       :pate-enabled false
                       :docstore-info (assoc org/default-docstore-info
                                             :docStoreInUse true
                                             :docTerminalInUse true
                                             :documentPrice 314)

                       :local-bulletins-page-settings
                       {:texts
                        (names
                          {:fi {:heading1 "Sipoo",
                                :heading2 "Sipoo R julkipanot",
                                :caption ["Tervetuloa"
                                          "Viranhaltijan p\u00e4\u00e4t\u00f6ksi\u00e4 tehd\u00e4\u00e4n p\u00e4ivitt\u00e4in. Oikaisuvaatimusaika on 14 p\u00e4iv\u00e4\u00e4 p\u00e4\u00e4t\u00f6sten tiedoksiannosta."
                                          "Listat ovat n\u00e4ht\u00e4vill\u00e4 kunnan virastossa"]},
                           :sv {:heading1 "Sibbo",
                                :heading2 "Sibbo julkipano",
                                :caption ["Bygglovssektionens beslut om bygglov meddelas efter den offentliga delgivningen d\u00e5 de anses ha kommit till vederb\u00f6randes k\u00e4nnedom. Besv\u00e4rstiden \u00e4r 30 dagar."
                                          "Tj\u00e4nsteinnehavarbeslut fattas dagligen. Besv\u00e4rstiden \u00e4r 14 dagar fr\u00e5n det att besluten kungjorts."]}})}}

                      ;; Sipoo YA
                      ;; Keeping :inforequest-enabled true and :new-application-enabled true to allow krysp itests pass.
                      {:id "753-YA"
                       :name (names {:fi "Sipoon yleisten alueiden rakentaminen"
                                     :sv "Sipoon yleisten alueiden rakentaminen"})
                       :scope [{:municipality "753"
                                :permitType "YA"
                                :inforequest-enabled true
                                :new-application-enabled true}]
                       :links [(link {:fi "Sipoo", :sv "Sibbo"}
                                     "http://sipoo.fi")]
                       :krysp {:YA {:url local-krysp :ftpUser "dev_ya_sipoo" :version "2.2.1"}}
                       :statementGivers [{:id "516560d6c2e6f603beb85147"
                                           :text "Paloviranomainen",
                                           :email "sonja.sibbo@sipoo.fi",
                                           :name "Sonja Sibbo"}]
                       :handler-roles [{:id "abba1111111111111111b753"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations [:ya-katulupa-vesi-ja-viemarityot
                                             :ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen
                                             :ya-kayttolupa-mainostus-ja-viitoitus
                                             :ya-kayttolupa-terassit
                                             :ya-kayttolupa-vaihtolavat
                                             :ya-kayttolupa-nostotyot]
                       :operations-attachments ya-default-attachments-for-operations
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :tags [{:id "735001000000000000000000" :label "YA kadut"} {:id "735002000000000000000000" :label "YA ojat"}]
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Kuopio YA
                      {:id "297-YA"
                       :name (names {:fi "Kuopio yleisten alueiden kaytto"
                                     :sv "Kuopio yleisten alueiden kaytto"})
                       :scope [{:municipality "297"
                                :permitType "YA"
                                :inforequest-enabled true
                                :new-application-enabled true
                                :caseManagement {:ftpUser "dev_ah_kuopio" :enabled true :version "1.1"}}]
                       :links [(link {:fi "Kuopio", :sv "Kuopio"}
                                     "http://www.kuopio.fi")]
                       :krysp {:YA {:url local-krysp :version "2.1.2" :ftpUser "dev_ya_kuopio"}}
                       :statementGivers [{:id "516560d6c2e6f603beb85147"
                                          :text "Paloviranomainen",
                                          :email "sonja.sibbo@sipoo.fi",
                                          :name "Sonja Sibbo"}]
                       :handler-roles [{:id "abba1111111111111111b753"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :operations-attachments ya-default-attachments-for-operations
                       :selected-operations (map first (filter (fn [[_ v]] (#{"YA"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}


                      ;; Tampere R
                      {:id "837-R"
                       :name (names {:fi "Tampereen rakennusvalvonta"
                                     :sv "Tampereen rakennusvalvonta"})
                       :scope [{:municipality "837"
                                :permitType "R"
                                :inforequest-enabled true
                                :new-application-enabled true}]
                       :links [(link {:fi "Tampere" :sv "Tammerfors"}
                                     "http://tampere.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://www.tampere.fi/asuminenjarakentaminen/rakennusvalvonta.html")
                               (link {:fi "Lomakkeet" :sv "Lomakkeet"}
                                     "http://www.tampere.fi/asuminenjarakentaminen/rakennusvalvonta/lomakkeet.html")]
                       :operations-attachments {:kerrostalo-rivitalo [[:paapiirustus :asemapiirros]
                                                                      [:paapiirustus :pohjapiirustus]
                                                                      [:hakija :valtakirja]
                                                                      [:pelastusviranomaiselle_esitettavat_suunnitelmat :vaestonsuojasuunnitelma]]
                                                :vapaa-ajan-asuinrakennus [[:paapiirustus :pohjapiirustus]
                                                                           [:hakija :ote_kauppa_ja_yhdistysrekisterista]
                                                                           [:pelastusviranomaiselle_esitettavat_suunnitelmat :vaestonsuojasuunnitelma]
                                                                           [:suunnitelmat :valaistussuunnitelma]]}
                       :pate-enabled true
                       :krysp {:R {:url local-krysp :version "2.2.2"
                                   :http (merge
                                           {:auth-type "basic"
                                            :partner "matti"
                                            :path {:application "hakemus-path"
                                                   :review  "katselmus-path"
                                                   :verdict "verdict-path"}
                                            :url local-krysp-receiver
                                            :headers [{:key "x-vault" :value "vaultti"}]}
                                           (org/encode-credentials "kuntagml" "kryspi"))}}
                       :handler-roles [{:id "abba1111111111111111a837"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Tampere YA
                      {:id "837-YA",
                       :name (names {:fi "Tampere yleiset alueet"
                                     :sv "Tammerfors yleiset alueet"})
                       :scope [{:municipality "837"
                                :permitType "YA"
                                :inforequest-enabled true
                                :new-application-enabled true}]
                       :statementGivers [{:id "521f1e82e4b0d14f5a87f179"
                                          :text "Paloviranomainen"
                                          :email "jussi.viranomainen@tampere.fi"
                                          :name "Jussi Viranomainen"}]
                       :krysp {:YA {:url local-krysp :ftpUser "dev_ya_tampere" :version "2.1.2"}}
                       :handler-roles [{:id "abba1111111111111111b837"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :operations-attachments ya-default-attachments-for-operations
                       :selected-operations (map first (filter (fn [[_ v]] (#{"YA"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Porvoo R
                      {:id "638-R"
                       :name (names {:fi "Porvoon rakennusvalvonta"
                                     :sv "Porvoon rakennusvalvonta"})
                       :scope [{:municipality "638" :permitType "R" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "638" :permitType "YI" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "638" :permitType "YL" :inforequest-enabled true :new-application-enabled true}]
                       :links [(link {:fi "Porvoo", :sv "Borg\u00e5"}
                                     "http://www.porvoo.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://www.porvoo.fi/fi/haku/palveluhakemisto/?a=viewitem&itemid=1030")]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R" "YI" "YL"} (name (:permit-type v)))) operations/operations))
                       :allowedAutologinIPs ["0:0:0:0:0:0:0:1" "127.0.0.1" "172.17.144.220" "109.204.231.126"]
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :krysp {:R {:url local-krysp, :ftpUser "dev_porvoo", :version "2.1.6"}}
                       :handler-roles [{:id "abba11111111111111111638"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Oulu R
                      {:id "564-R"
                       :name (names {:fi "Oulun rakennusvalvonta"
                                     :sv "Oulun rakennusvalvonta"})
                       :scope [{:municipality "564" :permitType "R" :inforequest-enabled true :new-application-enabled true}]
                       :links [(link {:fi "Oulu", :sv "Ule\u00E5borg"}
                                     "http://www.ouka.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Fastigheter"}
                                     "http://oulu.ouka.fi/rakennusvalvonta/")]
                       :handler-roles [{:id "abba1111111111111111a564"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Oulu YA
                      {:id "564-YA"
                       :name (names {:fi "Oulun yleiset alueet"
                                     :sv "Oulun yleiset alueet"})
                       :scope [{:municipality "564" :permitType "YA" :inforequest-enabled true :new-application-enabled true}]
                       :statementGivers [{:id "521f1e82e4b0d14f5a87f179"
                                          :text "Paloviranomainen"
                                          :email "oulu.viranomainen@oulu.fi"
                                          :name "Oulu Viranomainen"}]
                       :handler-roles [{:id "abba1111111111111111b564"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :links [(link {:fi "Oulu", :sv "Ule\u00E5borg"}
                                     "http://www.ouka.fi")]
                       :operations-attachments ya-default-attachments-for-operations
                       :selected-operations (map first (filter (fn [[_ v]] (#{"YA"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled true
                       :digitizer-tools-enabled true
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Naantali R
                      {:id "529-R"
                       :name (names {:fi "Naantalin rakennusvalvonta"
                                     :sv "Naantalin rakennusvalvonta"})
                       :scope [{:municipality "529" :permitType "R" :inforequest-enabled true :new-application-enabled true}]
                       :handler-roles [{:id "abba11111111111111111529"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Peruspalvelukuntayhtyma Selanne R
                      {:id "069-R"
                       :name (names {:fi "Peruspalvelukuntayhtym\u00E4 Sel\u00E4nne"
                                     :sv "Peruspalvelukuntayhtym\u00E4 Sel\u00E4nne"})
                       :scope [{:municipality "069" :permitType "R" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "317" :permitType "R" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "626" :permitType "R" :inforequest-enabled true :new-application-enabled true}
                               {:municipality "691" :permitType "R" :inforequest-enabled true :new-application-enabled true}]
                       :handler-roles [{:id "abba11111111111111111069"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}


                      ;; Loppi R
                      ;; Organisation for municipality "Loppi" that uses the "neuvontapyynnon-avaus" system.
                      ;; Nice address for testing "Ojatie 1, Loppi"
                      {:id "433-R"
                       :name (names {:fi "Loppi rakennusvalvonta"
                                     :sv "Loppi rakennusvalvonta"})
                       :scope [{:municipality "433"
                                :permitType "R"
                                :new-application-enabled false
                                :inforequest-enabled true
                                :open-inforequest true
                                :open-inforequest-email "erajorma@example.com"}]
                       :links []
                       :handler-roles [{:id "abba11111111111111111433"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Turku R with a public WFS server
                      {:id "853-R"
                       :name (names {:fi "Turku rakennusvalvonta"
                                     :sv "\u00c5bo byggnadstillsyn"})
                       :scope [{:municipality "853"
                                :permitType "R"
                                :new-application-enabled false
                                :inforequest-enabled true
                                :open-inforequest true
                                :open-inforequest-email "turku@example.com"}]
                       :links []
                       :krysp {:osoitteet {:url "http://opaskartta.turku.fi/TeklaOGCWeb/WFS.ashx"}}
                       :handler-roles [{:id "abba11111111111111111853"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled false
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Kuopio R, has case management (asianhallinta) enabled
                      {:id "297-R"
                       :name (names {:fi "Kuopio rakennusvalvonta"
                                     :sv "Kuopio byggnadstillsyn"})
                       :scope [{:open-inforequest-email nil
                                :open-inforequest false
                                :new-application-enabled true
                                :inforequest-enabled true
                                :municipality "297"
                                :permitType "R"}
                               {:open-inforequest-email nil
                                :open-inforequest false
                                :new-application-enabled true
                                :inforequest-enabled true
                                :municipality "297"
                                :permitType "P"
                                :caseManagement {:ftpUser "dev_ah_kuopio" :enabled true :version "1.1"}}]
                       :links [(link {:fi "Rakentamisen s\u00E4hk\u00F6iset lupapalvelut Kuopiossa"
                                      :sv "Rakentamisen s\u00E4hk\u00F6iset lupapalvelut Kuopiossa"}
                                     "http://www.kuopio.fi/web/tontit-ja-rakentaminen/rakentamisen-sahkoiset-lupapalvelut")
                               (link {:fi "Kuopion alueellinen rakennusvalvonta"
                                      :sv "Kuopion alueellinen rakennusvalvonta"}
                                     "http://www.kuopio.fi/web/tontit-ja-rakentaminen/rakennusvalvonta")
                               (link {:fi "Kuopion kaupunki"
                                      :sv "Kuopion kaupunki"}
                                     "http://www.kuopio.fi")]
                       :krysp {:R
                               {:ftpUser "dev_kuopio"
                                :url "http://localhost:8000/dev/krysp"
                                :version "2.1.5"}
                               :P
                               {:ftpUser "dev_kuopio"
                                :url "http://localhost:8000/dev/krysp"
                                :version "2.1.5"}}
                       :handler-roles [{:id "abba1111111111111111b297"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :operations-attachments {:poikkeamis [[:paapiirustus :asemapiirros]]}
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R" "P"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported false}

                      ;; Helsinki R
                      {:id "091-R"
                       :name (names {:fi "Helsingin rakennusvalvontavirasto"
                                     :sv "Helsingfors byggnadstillsynsverket"})
                       :scope [{:municipality "091"
                                :permitType "R"
                                :new-application-enabled true
                                :inforequest-enabled true}]
                       :links [(link {:fi "Helsinki" :sv "Helsingfors"}
                                     "http://www.hel.fi")
                               (link {:fi "Rakennusvalvontavirasto", :sv "Byggnadstillsynsverket"}
                                     "http://www.hel.fi/www/rakvv/fi")]
                       :krysp {:R {:url local-krysp :version "2.2.0" :ftpUser "dev_helsinki"}}
                       :handler-roles [{:id "abba1111111111111111a091"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :operations-attachments {:kerrostalo-rivitalo [[:paapiirustus :asemapiirros]
                                                                      [:paapiirustus :pohjapiirustus]]}
                       :assignments-enabled true
                       :permanent-archive-enabled true
                       :digitizer-tools-enabled true
                       :permanent-archive-in-use-since 1451613600000
                       :earliest-allowed-archiving-date 0
                       :use-attachment-links-integration true
                       :operations-tos-functions {:masto-tms "10 03 00 01"}
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;;
                      ;; Ymparisto organisaatiot
                      ;;
                      {:id "564-YMP"
                       :name (names {:fi "Oulun ymparisto"
                                     :sv "Oulun ymparisto"})
                       :scope [{:municipality "564" :permitType "YM" :inforequest-enabled true :new-application-enabled true :caseManagement {:ftpUser "dev_ah_oulu" :enabled false :version "1.1"}}
                               {:municipality "564" :permitType "YI" :inforequest-enabled true :new-application-enabled true :caseManagement {:ftpUser "dev_ah_oulu" :enabled false :version "1.1"}}
                               {:municipality "564" :permitType "YL" :inforequest-enabled true :new-application-enabled true :caseManagement {:ftpUser "dev_ah_oulu" :enabled false :version "1.1"}}
                               {:municipality "564" :permitType "MAL" :inforequest-enabled true :new-application-enabled true :caseManagement {:ftpUser "dev_ah_oulu" :enabled false :version "1.1"}}
                               {:municipality "564" :permitType "VVVL" :inforequest-enabled true :new-application-enabled true :caseManagement {:ftpUser "dev_ah_oulu" :enabled false :version "1.1"}}]
                       :links [(link {:fi "Oulu", :sv "Ule\u00E5borg"}
                                     "http://www.ouka.fi")]
                       :statementGivers [{:id "516560d6c2e6f603beccc144"
                                          :text "Paloviranomainen",
                                          :email "olli.uleaborg@ouka.fi",
                                          :name "Olli Ule\u00E5borg"}]
                       :handler-roles [{:id "abba1111111111111111c564"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"YI" "YL" "YM" "MAL" "VVVL"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;;
                      ;; Testeissa kaytettavia organisaatioita
                      ;;

                      ;; Sipoo R - New applications disabled
                      {:id "997-R-TESTI-1"
                       :name (names {:fi "Sipoon rakennusvalvonta"
                                     :sv "Sipoon rakennusvalvonta"})
                       :scope [{:municipality "997" :permitType "R" :inforequest-enabled true :new-application-enabled false}]
                       :links [(link {:fi "Sipoo", :sv "Sibbo"}
                                     "http://sipoo.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://sipoo.fi/fi/palvelut/asuminen_ja_rakentaminen/rakennusvalvonta")]
                       :krysp {:R {:url local-krysp, :version "2.1.2"
                                   :ftpUser "dev_sipoo"}}
                       :statementGivers [{:id "516560d6c2e6f603beb85147"
                                           :text "Paloviranomainen",
                                           :email "sonja.sibbo@sipoo.fi",
                                           :name "Sonja Sibbo"}]
                       :handler-roles [{:id "abba11111111111111111997"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Sipoo R - Inforequests disabled
                      {:id "998-R-TESTI-2"
                       :name (names {:fi "Sipoon rakennusvalvonta"
                                     :sv "Sipoon rakennusvalvonta"})
                       :scope [{:municipality "998" :permitType "R" :inforequest-enabled false :new-application-enabled true}]
                       :links [(link {:fi "Sipoo", :sv "Sibbo"}
                                     "http://sipoo.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://sipoo.fi/fi/palvelut/asuminen_ja_rakentaminen/rakennusvalvonta")]
                       :krysp {:R {:url local-krysp, :version "2.1.2"}}
                       :statementGivers [{:id "516560d6c2e6f603beb85147"
                                           :text "Paloviranomainen",
                                           :email "sonja.sibbo@sipoo.fi",
                                           :name "Sonja Sibbo"}]
                       :handler-roles [{:id "abba11111111111111111998"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}

                      ;; Sipoo R - Both new applications and inforequests disabled
                      {:id "999-R-TESTI-3"
                       :name (names {:fi "Sipoon rakennusvalvonta"
                                     :sv "Sipoon rakennusvalvonta"})
                       :scope [{:municipality "999" :permitType "R" :inforequest-enabled false :new-application-enabled false}]
                       :links [(link {:fi "Sipoo", :sv "Sibbo"}
                                     "http://sipoo.fi")
                               (link {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                                     "http://sipoo.fi/fi/palvelut/asuminen_ja_rakentaminen/rakennusvalvonta")]
                       :krysp {:R {:url local-krysp :version "2.1.2"}}
                       :statementGivers [{:id "516560d6c2e6f603beb85147"
                                           :text "Paloviranomainen",
                                           :email "sonja.sibbo@sipoo.fi",
                                           :name "Sonja Sibbo"}]
                       :handler-roles [{:id "abba11111111111111111999"
                                        :name {:fi "K\u00e4sittelij\u00e4"
                                               :sv "Handl\u00e4ggare"
                                               :en "Handler"}
                                        :general true}]
                       :selected-operations (map first (filter (fn [[_ v]] (#{"R"} (name (:permit-type v)))) operations/operations))
                       :permanent-archive-enabled false
                       :digitizer-tools-enabled false
                       :automatic-review-fetch-enabled true
                       :automatic-ok-for-attachments-enabled true
                       :multiple-operations-supported true}]))

(def companies [{:id "solita"
                 :accountType "account5"
                 :billingType "monthly"
                 :customAccountLimit nil
                 :created 1412959886600
                 :name "Solita Oy"
                 :address1 "\u00c5kerlundinkatu 11"
                 :zip "33100"
                 :po "Tampere"
                 :country "FINLAND"
                 :y "1060155-5"
                 :netbill "solitabilling"
                 :ovt "003710601555"
                 :pop "BAWCFI22"
                 :reference "Lupis"
                 :process-id "CkaekKfpEymHUG0nn5z4MLxwNm34zIdpAXHqQ3FM"
                 :tags [{:id "7a67a67a67a67a67a67a67a6"
                         :label "Projekti1"}]}
                {:id "esimerkki"
                 :accountType "account5"
                 :billingType "monthly"
                 :customAccountLimit nil
                 :created 1493200035783
                 :name "Esimerkki Oy"
                 :address1 "Merkintie 88"
                 :zip "12345"
                 :po "Humppila"
                 :country "Suomi"
                 :y "7208863-8"
                 :netbill "samplebilling"
                 :ovt "003710601555"
                 :pop "BAWCFI22"
                 :reference "Esim"
                 :process-id "CkaekKfpEymHUG0nn5z4MLxwNm34zIdpAXHqQMF3"}])

(deffixture "minimal" {}
  (mongo/clear!)
  (mongo/insert-batch :ssoKeys [{:_id "12342424c26b7342d92a4321" :ip "127.0.0.1" :key "ozckCE8EESo+wMKWklGevQ==" :crypto-iv "V0HaDa6lpWKj+W0uMKyHBw=="}
                                {:_id "12342424c26b7342d92a4322" :ip "172.17.144.220" :key "ozckCE8EESo+wMKWklGevQ==" :crypto-iv "V0HaDa6lpWKj+W0uMKyHBw=="}
                                {:_id "12342424c26b7342d92a9876" :ip "109.204.231.126" :key "ozckCE8EESo+wMKWklGevQ==" :crypto-iv "V0HaDa6lpWKj+W0uMKyHBw=="}])
  (mongo/insert-batch :users users)
  (mongo/insert-batch :companies companies)
  (mongo/insert-batch :organizations organizations))
