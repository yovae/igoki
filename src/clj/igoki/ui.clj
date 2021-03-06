(ns igoki.ui
  (:require [quil.core :as q]
            [igoki.util :as util])
  (:import (org.opencv.highgui VideoCapture Highgui)
           (org.opencv.core Mat Core)
           (javax.swing SwingUtilities JFrame JFileChooser)
           (org.opencv.video Video)
           (org.opencv.imgproc Imgproc)
           (java.util LinkedList)
           (kuusisto.tinysound TinySound)
           (java.io File)))

(defn setup [ctx]
  (q/smooth)
  (q/frame-rate (or (-> @ctx :sketchconfig :framerate) 5))
  (q/background 200))

(defn shadow-text
  ([^String s x y]
   (shadow-text s x y :left :bottom))
  ([^String s x y align-horiz]
    (shadow-text s x y align-horiz :bottom))
  ([^String s x y align-horiz align-vert]
   (q/text-align (or align-horiz :left) (or align-vert :bottom))
   (q/fill 0 196)
   (q/text-size 20)
   (q/text s (inc x) (inc y))

   (q/fill 255)
   (q/text-size 20)
   (q/text s x y)))

(defn state [ctx] (:state @ctx))

(defmulti construct state)
(defmethod construct :default [ctx])

(defmulti destruct state)
(defmethod destruct :default [ctx])

(defmulti draw state)
(defmethod draw :default [ctx]
  (q/fill 255 64 78)
  (q/rect 0 0 (q/width) (q/height))
  (shadow-text (str "State not implemented: " (:state @ctx)) 10 25))

(defmulti mouse-dragged state)
(defmethod mouse-dragged :default [ctx])

(defmulti mouse-pressed state)
(defmethod mouse-pressed :default [ctx])

(defmulti mouse-released state)
(defmethod mouse-released :default [ctx])

(defmulti mouse-moved state)
(defmethod mouse-moved :default [ctx])

(defmulti key-pressed state)
(defmethod key-pressed :default [ctx])

(defn transition [ctx new-state]
  (destruct ctx)
  (swap! ctx assoc :state new-state)
  (construct ctx)
  ctx)

(defn start [ctx]
  (let [sketch
        (q/sketch
          :renderer :java2d
          :title "Goban panel"
          :setup (partial setup ctx)
          :draw (partial #'draw ctx)
          :size (or (-> @ctx :sketchconfig :size) [1280 720])
          :features [:resizable]
          :mouse-dragged (partial #'mouse-dragged ctx)
          :mouse-pressed (partial #'mouse-pressed ctx)
          :mouse-released (partial #'mouse-released ctx)
          :mouse-moved (partial #'mouse-moved ctx)
          :key-pressed (partial #'key-pressed ctx))]
    (swap! ctx assoc :sketch sketch)))

;; Following code doesn't belong in here, but can move it out in due time.

(defonce ctx (atom {}))
(defn read-single [ctx camidx]
  (let [video (VideoCapture. (int camidx))
        frame (Mat.)]
    (Thread/sleep 500)
    (.read video frame)
    (swap!
      ctx
      update :camera
      #(assoc %
        :raw frame
        :pimg (util/mat-to-pimage frame (get-in % [:pimg :bufimg]) (get-in % [:pimg :pimg]))))
    (.release video)))

(defn read-file [ctx fname]
  (let [frame (Highgui/imread (str "resources/" fname))]
    (swap!
      ctx update :camera
      #(assoc %
        :raw frame
        :pimg (util/mat-to-pimage frame (get-in % [:pimg :bufimg]) (get-in % [:pimg :pimg]))))))

(defn stop-read-loop [ctx]
  (if-let [video ^VideoCapture (-> @ctx :camera :video)]
    (.release video))
  (swap! ctx update :camera assoc :stopped true :video nil))

(defn illuminate-correct [m]
  (util/with-release [lab-image (Mat.) equalized (Mat.)]
    (let [planes (LinkedList.)]
      (Imgproc/cvtColor m lab-image Imgproc/COLOR_BGR2Lab)
      (Core/split lab-image planes)
      (Imgproc/equalizeHist (first planes) equalized)
      (.copyTo equalized (first planes))
      (Core/merge planes lab-image)
      (Imgproc/cvtColor lab-image m Imgproc/COLOR_Lab2BGR)
      m)))

(defn camera-read [ctx video]
  (when-not (.isOpened video)
    (println "Error: Camera not opened"))
  (when-not (-> @ctx :camera :stopped)
    (try
      (let [frame (or (-> @ctx :camera :frame) (Mat.))]
        (.read video frame)

        (swap!
          ctx update :camera
          #(assoc %
            :raw frame
            ;; TODO: this chows memory - better to have a hook on update for each specific
            ;; view - this will only be needed on the first screen.
            :pimg (util/mat-to-pimage frame (get-in % [:pimg :bufimg]) (get-in % [:pimg :pimg])))))
      (Thread/sleep (or (-> @ctx :camera :read-delay) 1000))
      (catch Exception e
        (println "exception thrown")
        (.printStackTrace e)
        #_(stop-read-loop ctx)
        #_(throw e)))))

(defn read-loop [ctx camidx]
  (let [^VideoCapture video (VideoCapture. camidx)]
    (swap! ctx update :camera assoc
           :video video
           :stopped false)
    (doto
      (Thread.
        ^Runnable
        #(when-not (-> @ctx :camera :stopped)
          (camera-read ctx video)
          (recur)))
      (.setDaemon true)
      (.start))))

(defn switch-read-loop [ctx camidx]
  (stop-read-loop ctx)
  (Thread/sleep (* 2 (or (-> @ctx :camera :read-delay) 1000)))
  (read-loop ctx camidx))

(defn save-dialog [success-fn]
  (SwingUtilities/invokeLater
    #(let [frame (JFrame. "Save")
           chooser (JFileChooser.)]
      (try
        (.setAlwaysOnTop frame true)
        (when
          (= JFileChooser/APPROVE_OPTION (.showSaveDialog chooser frame))
          (success-fn (.getSelectedFile chooser)))
        (finally (.dispose frame))))))

(defn load-dialog [success-fn & [start-dir]]
  (SwingUtilities/invokeLater
    #(let [frame (JFrame. "Load")
           chooser (if start-dir (JFileChooser. start-dir) (JFileChooser.))]
      (try
        (.setAlwaysOnTop frame true)
        (when
          (= JFileChooser/APPROVE_OPTION (.showOpenDialog chooser frame))
          (success-fn (.getSelectedFile chooser)))
        (finally (.dispose frame))))))


#_(start (transition ctx :goban))