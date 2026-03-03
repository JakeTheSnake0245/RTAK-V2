package com.rtak.bridge;

/**
 * Callback interface implemented on the Java side, passed to the Python
 * rtak_bridge module. Chaquopy marshals calls from Python threads to the
 * Java object that implements this interface.
 *
 * All methods may be called from background threads — implementations must
 * post to the main thread if UI updates are needed.
 */
public interface RTAKCallback {

    /**
     * A CoT XML event was received from a Reticulum peer.
     *
     * @param cotXml     The complete CoT XML string.
     * @param senderHash Hex string of the sender's link hash (may be empty).
     */
    void onCotReceived(String cotXml, String senderHash);

    /**
     * A Reticulum peer has connected (link established).
     *
     * @param peerHash Hex hash identifying the peer link.
     */
    void onPeerConnected(String peerHash);

    /**
     * A Reticulum peer has disconnected (link closed).
     *
     * @param peerHash Hex hash identifying the peer link.
     */
    void onPeerDisconnected(String peerHash);

    /**
     * An RTAK node was discovered via announce.
     *
     * @param destHash Hex destination hash of the announced node.
     * @param appData  App data string from the announce.
     */
    void onPeerAnnounced(String destHash, String appData);

    /**
     * Bridge status changed (STARTING, RUNNING, ERROR, STOPPED).
     *
     * @param status Status string.
     */
    void onStatusChanged(String status);

    /**
     * A dynamically-managed RNS interface was added, removed, or changed state.
     *
     * @param name  Interface name.
     * @param event One of: "ADDED", "REMOVED", "ONLINE", "OFFLINE".
     */
    void onInterfaceStateChanged(String name, String event);
}
