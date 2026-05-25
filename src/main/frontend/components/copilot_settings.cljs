(ns ^:no-doc frontend.components.copilot-settings
  "Copilot settings and OAuth flow UI."
  (:require [frontend.context.i18n :refer [t]]
            [frontend.extensions.copilot :as copilot]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]))

(rum/defcs device-flow-panel < rum/reactive
  (rum/local nil ::flow-data)
  (rum/local :idle ::status)
  [state]
  (let [flow-data (::flow-data state)
        status (::status state)]
    [:div.copilot-settings
     [:h3 (t :copilot/title)]
     [:p {:style {:opacity 0.7 :margin-bottom "16px"}}
      (t :copilot/settings-description)]

     (case @status
       :idle
       (shui/button
        {:on-click
         (fn []
           (reset! status :waiting)
           (-> (copilot/start-device-flow!)
               (p/then (fn [flow]
                         (if flow
                           (do
                             (reset! flow-data flow)
                             (copilot/poll-device-token!
                              (:device-code flow)
                              (:interval flow)
                              (fn [_] (reset! status :success))
                              (fn [_] (reset! status :error))))
                           (reset! status :error))))
               (p/catch (fn []
                          (reset! status :error)))))}
        (t :copilot/action-connect-github))

       :waiting
       (when-let [flow @flow-data]
         [:div {:style {:border "1px solid var(--lx-gray-05)"
                        :border-radius "8px"
                        :padding "16px"
                        :margin-top "12px"}}
          [:p
           (t :copilot/device-step-1-prefix)
           " "
           [:a {:href (:verification-uri flow) :target "_blank" :rel "noopener noreferrer"}
            (:verification-uri flow)]]
          [:p {:style {:margin-top "8px"}} (t :copilot/device-step-2-prefix)]
          [:div.copilot-device-code (:user-code flow)]
          [:p {:style {:opacity 0.6 :font-size "13px"}} (t :copilot/device-waiting)]])

       :success
       [:div
        [:span {:style {:color "var(--lx-green-11, #2f9e44)"}} (t :copilot/device-connected)]
        (shui/button {:on-click #(reset! status :idle)
                      :size :sm
                      :style {:margin-left "12px"}}
                     (t :ui/close))]

       :error
       [:div
        [:span {:style {:color "var(--lx-red-11, #e03131)"}} (t :copilot/device-auth-failed)]
        (shui/button {:on-click #(reset! status :idle)
                      :size :sm
                      :style {:margin-left "12px"}}
                     (t :copilot/action-retry))])

     (when (copilot/copilot-enabled?)
       [:div {:style {:margin-top "16px"
                      :padding "12px"
                      :background "var(--lx-gray-02)"
                      :border-radius "6px"}}
        [:span {:style {:color "var(--lx-green-11, #2f9e44)"}} "● "]
        (t :copilot/connected-label)
        (shui/button
         {:on-click copilot/logout!
          :size :sm
          :variant :ghost
          :style {:margin-left "12px"}}
         (t :copilot/action-disconnect))])]))
