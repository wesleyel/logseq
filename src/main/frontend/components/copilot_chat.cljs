(ns ^:no-doc frontend.components.copilot-chat
  "Copilot Chat panel component for Logseq sidebar."
  (:require [clojure.string :as string]
            [frontend.context.i18n :refer [t]]
            [frontend.extensions.copilot :as copilot]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private system-prompt
  "You are a helpful AI assistant integrated into Logseq, a privacy-first knowledge management tool.
Help the user with notes, writing, research, and thinking.
Keep responses concise and in the same language as the user's message.
Use Logseq terminology like blocks, pages, properties, and queries.")

(rum/defc chat-message
  [{:keys [role content]}]
  [:div.copilot-chat-message
   {:class (if (= role "user") "copilot-chat-user" "copilot-chat-assistant")}
   [:div.copilot-chat-role
    (if (= role "user")
      (t :copilot/chat-role-you)
      (t :copilot/chat-role-copilot))]
   [:div.copilot-chat-content content]])

(rum/defcs copilot-chat-panel < rum/reactive
  (rum/local [] ::messages)
  (rum/local "" ::input)
  (rum/local false ::loading?)
  (rum/local nil ::flow-data)
  (rum/local :idle ::auth-status)
  (rum/local nil ::scroll-ref)
  [state]
  (let [messages (::messages state)
        input-val (::input state)
        loading? (::loading? state)
        flow-data (::flow-data state)
        auth-status (::auth-status state)
        scroll-ref (::scroll-ref state)
        authenticated? (copilot/copilot-enabled?)

        scroll-to-bottom! (fn []
                            (when-let [el @scroll-ref]
                              (set! (.-scrollTop el) (.-scrollHeight el))))

        connect! (fn []
                   (reset! auth-status :waiting)
                   (-> (copilot/start-device-flow!)
                       (p/then (fn [flow]
                                 (if flow
                                   (do
                                     (reset! flow-data flow)
                                     (copilot/poll-device-token!
                                      (:device-code flow)
                                      (:interval flow)
                                      (fn [_] (reset! auth-status :success))
                                      (fn [_] (reset! auth-status :error))))
                                   (reset! auth-status :error))))
                       (p/catch (fn []
                                  (reset! auth-status :error)))))

        send-message! (fn []
                        (let [query (string/trim @input-val)]
                          (when (and (not (string/blank? query))
                                     (not @loading?))
                            (let [user-msg {:role "user" :content query}
                                  history (conj @messages user-msg)
                                  api-messages (into [{:role "system" :content system-prompt}]
                                                     history)]
                              (reset! messages history)
                              (reset! input-val "")
                              (reset! loading? true)
                              (js/setTimeout scroll-to-bottom! 50)
                              (-> (copilot/chat-completion! api-messages)
                                  (p/then (fn [response]
                                            (swap! messages conj {:role "assistant"
                                                                  :content (or response (t :copilot/chat-error-generic))})
                                            (reset! loading? false)
                                            (js/setTimeout scroll-to-bottom! 50)))
                                  (p/catch (fn []
                                             (swap! messages conj {:role "assistant"
                                                                   :content (t :copilot/chat-error-generic)})
                                             (reset! loading? false))))))))]

    [:div.copilot-chat-panel
     [:div.copilot-chat-header
      [:span.ti.ti-brand-github {:style {:font-size "16px"}}]
      [:strong (t :copilot/chat-title)]
      (when authenticated?
        [:span {:style {:margin-left "auto" :font-size "11px" :opacity 0.6}}
         (t :copilot/chat-connected)])]

     (if-not authenticated?
       [:div.copilot-chat-auth
        [:p (t :copilot/chat-connect-description)]
        (case @auth-status
          :idle
          (shui/button {:on-click connect!}
                       (t :copilot/action-connect-github))

          :waiting
          (if-let [flow @flow-data]
            [:div.copilot-device-flow
             [:p
              (t :copilot/device-step-1-prefix)
              " "
              [:a {:href (:verification-uri flow) :target "_blank" :rel "noopener noreferrer"}
               (:verification-uri flow)]]
             [:p {:style {:margin-top "8px"}}
              (t :copilot/device-step-2-prefix)]
             [:div.copilot-device-code (:user-code flow)]
             [:p {:style {:opacity 0.6}} (t :copilot/device-waiting)]]
            [:p (t :copilot/device-waiting)])

          :success
          [:div
           [:p {:style {:color "var(--lx-green-11, #2f9e44)"}} (t :copilot/device-connected)]
           (shui/button {:on-click #(reset! auth-status :idle)
                         :size :sm}
                        (t :ui/close))]

          :error
          [:div
           [:p {:style {:color "var(--lx-red-11, #e03131)"}} (t :copilot/device-auth-failed)]
           (shui/button {:on-click #(reset! auth-status :idle)
                         :size :sm}
                        (t :copilot/action-retry))])]

       [:<>
        [:div.copilot-chat-messages
         {:ref #(when % (reset! scroll-ref %))}
         (if (empty? @messages)
           [:div.copilot-chat-empty (t :copilot/chat-empty)]
           (for [[idx msg] (map-indexed vector @messages)]
             (rum/with-key (chat-message msg) idx)))
         (when @loading?
           [:div.copilot-chat-message.copilot-chat-assistant
            [:div.copilot-chat-role (t :copilot/chat-role-copilot)]
            [:div.copilot-chat-content
             [:span.copilot-typing (t :copilot/chat-thinking)]]
            ])]

        [:div.copilot-chat-input-area
         [:textarea.copilot-chat-input
          {:value @input-val
           :placeholder (t :copilot/chat-placeholder)
           :rows 2
           :on-change #(reset! input-val (.. % -target -value))
           :on-key-down (fn [e]
                          (when (and (= 13 (.-keyCode e)) (not (.-shiftKey e)))
                            (.preventDefault e)
                            (send-message!)))}]
         (shui/button
          {:on-click send-message!
           :disabled (or @loading? (string/blank? @input-val))
           :size :sm
           :aria-label (t :copilot/action-send)}
          (shui/icon "send" {:size 14}))
         (shui/button
          {:on-click copilot/logout!
           :size :sm
           :variant :ghost}
          (t :copilot/action-disconnect))]])]))
