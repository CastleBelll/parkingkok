package com.sjstudioz.parkingpin.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sjstudioz.parkingpin.ParkingpinApplication
import kotlinx.coroutines.launch

/**
 * `주차 종료` / `아직 주차 중` on the departure notification (docs/05 §11a), answered from the
 * shade without the app coming forward — the shape [ParkingCandidateReceiver] has.
 */
class ParkingEndProposalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ParkingEndProposalChannel.ACTION_END && action != ParkingEndProposalChannel.ACTION_KEEP) return
        val recordId = intent.getStringExtra(ParkingEndProposalChannel.EXTRA_RECORD_ID)
        if (recordId.isNullOrEmpty()) {
            Log.w(TAG, "departure answer ignored: no record id")
            return
        }

        val container = ParkingpinApplication.containerOf(context)
        if (container == null) {
            // The proposal stays pending, on the shade and on the home card.
            Log.w(TAG, "departure answer arrived with no container")
            return
        }

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val proposals = container.parkingEndProposalCoordinator
                // Never throws: a failed write is logged and the question re-asked, so the
                // user can tap again. The coordinator withdraws the notification of whatever
                // it answered, and of a tap about a question that is already gone.
                if (action == ParkingEndProposalChannel.ACTION_END) {
                    proposals.tryAccept(recordId)
                } else {
                    proposals.tryKeep(recordId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PkDetection"
    }
}
