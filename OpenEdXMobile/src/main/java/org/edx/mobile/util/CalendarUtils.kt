package org.edx.mobile.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.edx.mobile.R
import org.edx.mobile.logger.Logger
import org.edx.mobile.model.course.CourseDateBlock
import java.util.*


@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
object CalendarUtils {
    private val logger = Logger(DateUtil::class.java.name)
    private const val REMINDER_24_HOURS = 24 * 60
    private const val REMINDER_48_HOURS = 2 * 24 * 60

    val permissions = arrayOf(android.Manifest.permission.WRITE_CALENDAR, android.Manifest.permission.READ_CALENDAR)

    /**
     * Check if the app has the calendar READ/WRITE permissions or not
     */
    fun hasPermissions(context: Context): Boolean = permissions.all { permission ->
        PermissionsUtil.checkPermissions(permission, context)
    }

    /**
     * Check if the calendar is already existed in mobile calendar app or not
     */
    fun isCalendarExists(context: Context, accountName: String, calendarTitle: String): Boolean {
        if (hasPermissions(context)) {
            return getCalendarId(context, accountName, calendarTitle) != (-1).toLong()
        }
        return false
    }

    /**
     * Create or update the calendar if it is already existed in mobile calendar app
     */
    fun createOrUpdateCalendar(
        context: Context,
        accountName: String,
        accountType: String,
        calendarTitle: String
    ): Long {
        val calendarId: Long = getCalendarId(context = context, accountName = accountName, calendarTitle = calendarTitle)
        return calendarId
//        return if (calendarId == (-1).toLong()) {
//            createCalendar(
//                context = context, accountName = accountName,
//                accountType = accountType, calendarTitle = calendarTitle
//            )
//        } else {
//            deleteCalendar(context = context, calendarId = calendarId)
//            createCalendar(
//                context = context, accountName = accountName, accountType = accountType,
//                calendarTitle = calendarTitle
//            )
//        }
    }

    /**
     * Method to create a separate calendar based on course name in mobile calendar app
     */
    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun createCalendar(
        context: Context,
        accountName: String,
        accountType: String,
        calendarTitle: String
    ): Long {
        val contentValues = ContentValues()
        contentValues.put(CalendarContract.Calendars.NAME, accountName)
        contentValues.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, accountName)
        contentValues.put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
//        contentValues.put(CalendarContract.Calendars.CAL_SYNC1, accountName)
        contentValues.put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
        contentValues.put(
            CalendarContract.Calendars.CALENDAR_COLOR,
            ContextCompat.getColor(context, R.color.primaryBaseColor)
        )
        contentValues.put(
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CAL_ACCESS_OWNER
        )
        contentValues.put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
        contentValues.put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        contentValues.put(CalendarContract.Calendars.VISIBLE, 1)
        contentValues.put(CalendarContract.Calendars._SYNC_ID, accountName)
        val creationUri: Uri? = asSyncAdapter(
            Uri.parse(CalendarContract.Calendars.CONTENT_URI.toString()),
            accountName,
            accountType
        )
        creationUri?.let {
            val calendarData: Uri? = context.contentResolver.insert(creationUri, contentValues)
            calendarData?.let {
                val id = calendarData.lastPathSegment.toLong()
                logger.debug("Calendar ID $id")
                return id
            }
        }
        return -1
    }

    /**
     * Method to check if the calendar with the course name exist in the mobile calendar app or not
     */
    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun getCalendarId(context: Context, accountName: String, calendarTitle: String): Long {
        var calendarId = -1
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.NAME)
        val calendarContentResolver = context.contentResolver
        var cursor: Cursor = calendarContentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection,
            CalendarContract.Calendars.NAME + "=? or " +
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + "=?", arrayOf(
                accountName, accountName
            ), null
        )
        if (cursor.moveToFirst()) {
            if (cursor.getString(cursor.getColumnIndex(CalendarContract.Calendars.NAME))
                    .equals(accountName)
            )
                calendarId = cursor.getInt(cursor.getColumnIndex(CalendarContract.Calendars._ID))
        }
        cursor.close()
        // temp code
        cursor = calendarContentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(),
            null,
            null,
            null
        )
        if (cursor.moveToFirst()) {
            do {
                logger.debug(cursor.getString(cursor.getColumnIndex(CalendarContract.Calendars._ID)))

            } while (cursor.moveToNext())
        }
        cursor.close()
        return calendarId.toLong()
    }

    /**
     * Method to add important dates of course as calendar event into calendar of mobile app
     */
    fun addEventsIntoCalendar(context: Context, calendarId: Long, courseName: String, courseDateBlock: CourseDateBlock) {
        val date = courseDateBlock.getDateCalendar()
        // start time of the event added to the calendar
        val startMillis: Long = Calendar.getInstance().run {
            set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), date.get(Calendar.HOUR_OF_DAY) - 1, date.get(Calendar.MINUTE))
            timeInMillis
        }
        // end time of the event added to the calendar
        val endMillis: Long = Calendar.getInstance().run {
            set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE))
            timeInMillis
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, "${AppConstants.ASSIGNMENT_DUE} : $courseName")
            put(CalendarContract.Events.DESCRIPTION, courseDateBlock.title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        addReminderToEvent(context = context, uri = uri)
    }

    /**
     * Method to add a reminder to the given calendar events
     *
     * @param context
     * @param uri calender event Uri
     */
    private fun addReminderToEvent(context: Context, uri: Uri) {
        val eventId: Long = uri.lastPathSegment.toLong()
        logger.debug("Event ID $eventId")
        // Adding reminder on the start of event
        val eventValues = ContentValues().apply {
            put(CalendarContract.Reminders.MINUTES, 0)
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, eventValues)
        // Adding reminder 24 hours before the event get started
        eventValues.apply {
            put(CalendarContract.Reminders.MINUTES, REMINDER_24_HOURS)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, eventValues)
        // Adding reminder 48 hours before the event get started
        eventValues.apply {
            put(CalendarContract.Reminders.MINUTES, REMINDER_48_HOURS)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, eventValues)
    }

    /**
     * Method to query the events for the given calendar id
     *
     * @param context [Context]
     * @param calendarId calendarId to query the events
     *
     * @return [Cursor]
     *
     * */
    private fun getCalendarEvents(context: Context, calendarId: Long): Cursor? {
        val calendarContentResolver = context.contentResolver
        val projection = arrayOf(
                CalendarContract.Events._ID
        )
        val selection = CalendarContract.Events.CALENDAR_ID + "=?"
        return calendarContentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf(calendarId.toString()),
                null
        )
    }

    /**
     * Method to delete the events for the given calendar id
     *
     * @param context [Context]
     * @param calendarId calendarId to query the events
     *
     * */
    fun deleteAllCalendarEvents(context: Context, calendarId: Long) {
        val cursor = getCalendarEvents(context, calendarId)
        cursor?.run {
            if (moveToFirst()) {
                do {
                    val deleteUri = ContentUris.withAppendedId(
                            CalendarContract.Events.CONTENT_URI,
                            getLong(getColumnIndex(CalendarContract.Events._ID))
                    )
                    val rowDelete = context.contentResolver.delete(deleteUri, null, null)
                    logger.debug("Rows deleted: $rowDelete")
                } while (moveToNext())
            }
        }
    }

    /**
     * Method to delete the course calendar from the mobile calendar app
     */
    fun deleteCalendar(context: Context, calendarId: Long) {
//        context.contentResolver.delete(Uri.parse("content://com.android.calendar/calendars/$calendarId"), null, null)
    }

    /**
     * Helper method used to return a URI for use with a sync adapter (how an application and a
     * sync adapter access the Calendar Provider)
     *
     * @param uri URI to access the calendar
     * @param account Name of the calendar owner
     *
     * @return URI of the calendar
     *
     */
    private fun asSyncAdapter(uri: Uri, account: String, accountType: String): Uri? {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.SyncState.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.SyncState.ACCOUNT_TYPE, accountType).build()
    }

    fun openCalendarApp(fragment: Fragment) {
        val builder: Uri.Builder = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
        ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
        val intent = Intent(Intent.ACTION_VIEW)
                .setData(builder.build())
        fragment.startActivity(intent)
    }
}
