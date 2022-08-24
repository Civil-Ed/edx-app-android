package org.edx.mobile.module.notification

import org.edx.mobile.model.api.EnrollmentResponse

/**
 * Abstracted for different implementation.
 * Tt may not make much sense for now. because different notification middleware may invoke
 * different workflow.
 */
interface NotificationDelegate {
    fun unsubscribeAll()

    /**
     * Tf learner logins, he should subscribe channels based on local pref setting
     */
    fun resubscribeAll()

    /**
     * Checks if local subscribed is not in the notification server then try to subscribe it
     */
    fun syncWithServerForFailure()

    /**
     * Sync with current course enrollment.
     * Based on current course enrollment, we subscribe/unsubscribe to the notification
     * and update the local preference.
     *
     * @param responses
     */
    fun checkCourseEnrollment(responses: EnrollmentResponse)

    /**
     *
     * @param courseId  also the channel id
     * @param subscribe subscribe or unsubscribe to courseId channel
     */
    fun changeNotificationSetting(courseId: String, channelId: String, subscribe: Boolean)

    /**
     *
     * @param channel
     * @return
     */
    fun isSubscribedByCourseId(channel: String): Boolean

    /**
     * App upgrade or new installation, it may need to re-sync with notification server
     */
    fun checkAppUpgrade()
}
