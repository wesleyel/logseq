(ns ^:no-doc frontend.extensions.copilot
  "GitHub Copilot integration for inline suggestions and chat."
  (:require [clojure.string :as string]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [promesa.core :as p]))

(def ^:private copilot-completions-url "https://api.githubcopilot.com/v1/completions")
(def ^:private copilot-chat-url "https://api.githubcopilot.com/v1/chat/completions")
(def ^:private github-device-code-url "https://github.com/login/device/code")
(def ^:private github-token-url "https://github.com/login/oauth/access_token")
(def ^:private copilot-session-url "https://api.github.com/copilot_internal/v2/token")
(def ^:private client-id
  (or (some-> js/globalThis (aget "process") (aget "env") (aget "COPILOT_CLIENT_ID"))
      "Iv1.b507a08c87ecfe98"))

(def ^:private debounce-ms 500)
(defonce *pending-timer (atom nil))
(defonce *last-req-id (atom 0))
(defonce *device-poll-timer (atom nil))

(defn get-github-token [] (storage/get "copilot-github-token"))
(defn set-github-token! [token] (storage/set "copilot-github-token" token))
(defn get-session-token [] (storage/get "copilot-session-token"))
(defn set-session-token! [token] (storage/set "copilot-session-token" token))
(defn get-session-expires [] (storage/get "copilot-session-expires"))
(defn set-session-expires! [expires] (storage/set "copilot-session-expires" expires))
(defn copilot-enabled? [] (boolean (get-github-token)))

(defn- stop-device-polling! []
  (when @*device-poll-timer
    (js/clearInterval @*device-poll-timer)
    (reset! *device-poll-timer nil)))

(defn- refresh-session-token! []
  (when-let [gh-token (get-github-token)]
    (-> (js/fetch copilot-session-url
                  #js {:method "GET"
                       :headers #js {"Authorization" (str "token " gh-token)
                                     "Accept" "application/json"}})
        (p/then (fn [response]
                  (if (.-ok response)
                    (.json response)
                    (throw (ex-info "Copilot session token refresh failed"
                                    {:status (.-status response)})))))
        (p/then (fn [res]
                  (let [token (aget res "token")
                        expires (aget res "expires_at")]
                    (when token
                      (set-session-token! token))
                    (when expires
                      (set-session-expires! expires))
                    token)))
        (p/catch (fn [error]
                   (js/console.error "Copilot session refresh failed" error)
                   nil)))))

(defn- get-valid-session-token! []
  (let [token (get-session-token)
        expires (get-session-expires)
        expires' (js/parseFloat (str expires))
        now (/ (.getTime (js/Date.)) 1000)]
    (if (and token (number? expires') (> (- expires' now) 60))
      (p/resolved token)
      (refresh-session-token!))))

(defn start-device-flow!
  []
  (-> (js/fetch github-device-code-url
                #js {:method "POST"
                     :headers #js {"Accept" "application/json"
                                   "Content-Type" "application/json"}
                     :body (js/JSON.stringify #js {:client_id client-id
                                                   :scope "copilot"})})
      (p/then (fn [response]
                (if (.-ok response)
                  (.json response)
                  (throw (ex-info "Copilot device flow failed to start"
                                  {:status (.-status response)})))))
      (p/then (fn [res]
                {:verification-uri (aget res "verification_uri")
                 :user-code (aget res "user_code")
                 :device-code (aget res "device_code")
                 :interval (or (aget res "interval") 5)}))
      (p/catch (fn [error]
                 (js/console.error "Device flow start failed" error)
                 nil))))

(defn poll-device-token!
  [device-code interval on-success on-error]
  (letfn [(poll []
            (-> (js/fetch github-token-url
                          #js {:method "POST"
                               :headers #js {"Accept" "application/json"
                                             "Content-Type" "application/json"}
                               :body (js/JSON.stringify
                                      #js {:client_id client-id
                                           :device_code device-code
                                           :grant_type "urn:ietf:params:oauth:grant-type:device_code"})})
                (p/then (fn [response]
                          (if (.-ok response)
                            (.json response)
                            (throw (ex-info "Device token poll failed"
                                            {:status (.-status response)})))))
                (p/then (fn [res]
                          (let [token (aget res "access_token")
                                err   (aget res "error")]
                            (cond
                              token
                              (do
                                (set-github-token! token)
                                (stop-device-polling!)
                                (p/do!
                                 (refresh-session-token!)
                                 (on-success token)))

                              (= err "authorization_pending")
                              nil

                              :else
                              (do
                                (stop-device-polling!)
                                (on-error err))))))
                (p/catch (fn [error]
                           (stop-device-polling!)
                           (on-error error)))))]
    (stop-device-polling!)
    (poll)
    (reset! *device-poll-timer (js/setInterval poll (* interval 1000)))))

(defn logout! []
  (stop-device-polling!)
  (storage/remove "copilot-github-token")
  (storage/remove "copilot-session-token")
  (storage/remove "copilot-session-expires")
  (state/set-state! :copilot/suggestion nil))

(defn get-suggestion [] (:copilot/suggestion @state/state))
(defn clear-suggestion! [] (state/set-state! :copilot/suggestion nil))

(defn- do-fetch-completion!
  [input-id prefix suffix]
  (-> (get-valid-session-token!)
      (p/then (fn [token]
                (when token
                  (let [req-id (swap! *last-req-id inc)]
                    (-> (js/fetch copilot-completions-url
                                  #js {:method "POST"
                                       :headers #js {"Authorization" (str "Bearer " token)
                                                     "Content-Type" "application/json"
                                                     "Editor-Version" "logseq/0.10.0"
                                                     "Copilot-Integration-Id" "logseq"
                                                     "OpenAI-Intent" "copilot-ghost-text"}
                                       :body (js/JSON.stringify
                                              (clj->js
                                               {:prompt prefix
                                                :suffix suffix
                                                :max_tokens 150
                                                :temperature 0
                                                :top_p 1
                                                :n 1
                                                :stream false
                                                :stop ["\n\n" "---"]}))})
                        (p/then (fn [response]
                                  (if (.-ok response)
                                    (.json response)
                                    (throw (ex-info "Copilot completion request failed"
                                                    {:status (.-status response)})))))
                        (p/then (fn [res]
                                  (when (= req-id @*last-req-id)
                                    (let [text (some-> (aget res "choices" 0 "text")
                                                       (as-> content
                                                           (when-not (string/blank? content)
                                                             content)))]
                                      (if text
                                        (state/set-state! :copilot/suggestion
                                                          {:text text
                                                           :pos (count prefix)
                                                           :input-id input-id})
                                        (clear-suggestion!))))))
                        (p/catch (fn []
                                   (clear-suggestion!))))))))
      (p/catch (fn []
                 (clear-suggestion!)))))

(defn trigger-completion!
  [^js input]
  (when @*pending-timer
    (js/clearTimeout @*pending-timer))
  (clear-suggestion!)
  (when input
    (reset! *pending-timer
            (js/setTimeout
             (fn []
               (let [value (.-value input)
                     pos (.-selectionStart input)
                     prefix (subs value 0 pos)
                     suffix (subs value pos)]
                 (when (> (count (string/trim prefix)) 3)
                   (do-fetch-completion! (.-id input) prefix suffix))))
             debounce-ms))))

(defn accept-suggestion!
  []
  (when-let [{:keys [text pos input-id]} (get-suggestion)]
    (when-let [^js input (js/document.getElementById input-id)]
      (let [value (.-value input)
            new-val (str (subs value 0 pos) text (subs value pos))
            new-pos (+ pos (count text))]
        (set! (.-value input) new-val)
        (.setSelectionRange input new-pos new-pos)
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        (clear-suggestion!)))))

(defn chat-completion!
  [messages]
  (-> (get-valid-session-token!)
      (p/then (fn [token]
                (if token
                  (-> (js/fetch copilot-chat-url
                                #js {:method "POST"
                                     :headers #js {"Authorization" (str "Bearer " token)
                                                   "Content-Type" "application/json"
                                                   "Editor-Version" "logseq/0.10.0"
                                                   "Copilot-Integration-Id" "logseq"}
                                     :body (js/JSON.stringify
                                            (clj->js {:model "gpt-4o"
                                                      :messages messages
                                                      :max_tokens 1024
                                                      :stream false}))})
                      (p/then (fn [response]
                                (if (.-ok response)
                                  (.json response)
                                  (throw (ex-info "Copilot chat request failed"
                                                  {:status (.-status response)})))))
                      (p/then (fn [res]
                                (aget res "choices" 0 "message" "content")))
                      (p/catch (fn [error]
                                 (js/console.error "Copilot chat error" error)
                                 nil)))
                  (p/resolved nil))))
      (p/catch (fn []
                 nil))))
