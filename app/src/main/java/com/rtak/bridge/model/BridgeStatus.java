package com.rtak.bridge.model;

/**
 * Holds the observable state of the RTAK Bridge for UI display.
 */
public class BridgeStatus {

    /** Reticulum bridge state: STARTING, RUNNING, ERROR, STOPPED */
    public String bridgeState = "STOPPED";

    /** This node's Reticulum destination hash */
    public String destinationHash = "";

    /** Whether the TAK TCP server is accepting connections */
    public boolean takServerRunning = false;

    /** Port the TAK TCP server is listening on */
    public int takServerPort = 8087;

    /** Number of connected TAK (ATAK/WinTAK) clients */
    public int takClients = 0;

    /** Number of connected Reticulum peers */
    public int rnsPeers = 0;

    /** CoT messages received from TAK clients */
    public int cotFromTak = 0;

    /** CoT messages received from Reticulum */
    public int cotFromRns = 0;

    /** CoT messages sent to TAK clients */
    public int cotToTak = 0;

    /** CoT messages sent to Reticulum */
    public int cotToRns = 0;

    public boolean isRunning() {
        return "RUNNING".equals(bridgeState);
    }
}
