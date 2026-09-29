package com.sjstudioz.parkingpin.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
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

        // The tap has been acted on: take the question down now, not after the write.
        NotificationManagerCompat.from(context).cancel(ParkingEndProposalChannel.NOTIFICATION_ID)

        val container = ParkingpinApplication.containerOf(context)
        if (container == null) {
            // The proposal stays pending and the home card still asks it.
            Log.w(TAG, "departure answer arrived with no container")
            return
        }

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val proposals = container.parkingEndProposalCoordinator
                if (action == ParkingEndProposalChannel.ACTION_END) proposals.accept(recordId) else proposals.keep(recordId)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PkDetection"
    }
}
