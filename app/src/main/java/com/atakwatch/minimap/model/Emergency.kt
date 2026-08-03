package com.atakwatch.minimap.model

/**
 * ATAK emergency beacons. These are the `b-a-o-*` ("bits, alert") CoT types the
 * TAK ecosystem already understands, so an alert raised from the watch appears
 * as a real emergency on every ATAK client and TAK server on the network — not
 * as a custom marker only this app can read.
 *
 * The detail block carries an `<emergency>` element whose `type` is the human
 * label, and `cancel="true"` on the stand-down.
 */
enum class EmergencyType(
    val cotType: String,
    val label: String,
    /** Text ATAK shows for this alert. */
    val emergencyLabel: String,
) {
    NINE_ONE_ONE("b-a-o-tbl", "911 Alert", "911 Alert"),
    RING_THE_BELL("b-a-o-pan", "Ring the Bell", "Ring The Bell"),
    IN_CONTACT("b-a-o-opn", "Troops in Contact", "Troops In Contact"),
    ;

    companion object {
        const val CANCEL_TYPE = "b-a-o-can"

        /** True for any alert type, so inbound alerts can be highlighted. */
        fun isEmergency(cotType: String): Boolean = cotType.startsWith("b-a-o")

        fun isCancel(cotType: String): Boolean = cotType == CANCEL_TYPE
    }
}
