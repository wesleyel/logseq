(ns ^:no-doc frontend.extensions.copilot-ui
  "Ghost text overlay component for Copilot inline suggestions."
  (:require [frontend.context.i18n :refer [t]]
            [frontend.state :as state]
            [frontend.util.cursor :as cursor]
            [rum.core :as rum]))

(rum/defc ghost-text < rum/reactive
  [input-id]
  (let [suggestion (rum/react (rum/cursor state/state :copilot/suggestion))]
    (when (and suggestion (= (:input-id suggestion) input-id))
      (when-let [^js input (js/document.getElementById input-id)]
        (when-let [{:keys [left top]} (cursor/get-caret-pos input)]
          [:span.copilot-ghost-text
           {:style {:position "fixed"
                    :left (str left "px")
                    :top (str top "px")
                    :color "inherit"}}
           (:text suggestion)])))))

(rum/defc copilot-hint < rum/reactive
  []
  (let [suggestion (rum/react (rum/cursor state/state :copilot/suggestion))]
    (when suggestion
      [:div.copilot-tab-hint
       (t :copilot/hint-tab-accept)])))
