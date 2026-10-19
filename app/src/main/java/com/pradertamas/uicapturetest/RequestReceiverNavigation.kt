package com.pradertamas.uicapturetest

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent

/** No Activity/VM reference or request authority is retained in routing metadata. */
internal data class RequestReceiverTask(val bound: Boolean, val taskId: Int?)
internal data class RequestReceiverRoute(val taskId: Int?, val flags: Int)

internal fun requestReceiverRoute(owner: RequestReceiverTask): RequestReceiverRoute? {
    // A bound owner without a task cannot safely be replaced with a second ViewModel.
    if (owner.bound && owner.taskId == null) return null
    return RequestReceiverRoute(if (owner.bound) owner.taskId else null,
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
            (if (owner.bound) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Warm routing selects the exact bound owner's task, rather than guessing by task affinity.
 * CLEAR_TOP + SINGLE_TOP delivers to its existing standard-mode Activity via onNewIntent.
 * Missing warm task fails closed; it never creates a competing receiver owner.
 */
internal fun navigateRequestReceiver(activity: Activity, id: RequestMediaHandoffId): Boolean {
    val route = requestReceiverRoute(AppMediaRoles.receiverTask()) ?: return false
    val intent = Intent(activity, ReceiverActivity::class.java).addFlags(route.flags)
        .putExtra(ReceiverActivity.REQUEST_MEDIA_HANDOFF, id.value)
    if (route.taskId == null) activity.startActivity(intent)
    else {
        val task = activity.getSystemService(ActivityManager::class.java).appTasks
            .firstOrNull { it.taskInfo?.taskId == route.taskId } ?: return false
        task.startActivity(activity, intent, null)
    }
    return true
}
