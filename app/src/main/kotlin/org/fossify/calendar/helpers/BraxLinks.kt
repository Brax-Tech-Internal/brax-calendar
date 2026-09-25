package org.fossify.calendar.helpers

import android.content.Context
import android.net.Uri
import android.provider.CalendarContract.Events
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.queryCursorInlined
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALENDAR
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The `braxcal://` contract: how the Brax chat app (and the agent cards it verifies) open this calendar.
 * The chat app never learns package names or screen names; it only follows the links the tool registry
 * publishes in `res/raw/brax_tools.json`, and this file is the only place that interprets them.
 *
 *  braxcal://day/2026-09-28                              -> the day view on that date
 *  braxcal://event/<uid>                                 -> the event with that iCalendar UID (CalDAV) or import id
 *  braxcal://draft?title=..&start=..&end=..[&all_day=1]  -> a prefilled, unsaved event (start/end ISO 8601 UTC)
 *                                                           optional location=, description=
 *
 * Nothing here writes: a draft is only saved when the person taps save, and an event that cannot be found
 * is reported, never created.
 */
const val BRAX_SCHEME = "braxcal"
const val BRAX_HOST_DAY = "day"
const val BRAX_HOST_EVENT = "event"
const val BRAX_HOST_DRAFT = "draft"

sealed class BraxLink {
    data class Day(val dayCode: String) : BraxLink()
    data class Event(val uid: String) : BraxLink()
    data class Draft(
        val title: String,
        val startMillis: Long?,
        val endMillis: Long?,
        val allDay: Boolean,
        val location: String,
        val description: String,
    ) : BraxLink()

    data class Invalid(val reason: String) : BraxLink()
}

private val ISO_DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
private val UID_ALLOWED = Regex("""^[A-Za-z0-9._@\-]{1,255}$""")

fun parseBraxLink(uri: Uri): BraxLink? {
    if (uri.scheme != BRAX_SCHEME) {
        return null
    }
    val segments = uri.pathSegments
    return when (uri.host) {
        BRAX_HOST_DAY -> {
            val m = segments.singleOrNull()?.let { ISO_DAY.matchEntire(it) }
                ?: return BraxLink.Invalid("day needs one yyyy-mm-dd segment")
            val (y, mo, d) = m.destructured
            if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) {
                BraxLink.Invalid("day out of range")
            } else {
                BraxLink.Day("$y$mo$d")
            }
        }

        BRAX_HOST_EVENT -> {
            val uid = segments.singleOrNull() ?: return BraxLink.Invalid("event needs one uid segment")
            if (UID_ALLOWED.matches(uid)) BraxLink.Event(uid) else BraxLink.Invalid("uid has characters outside the allowed set")
        }

        BRAX_HOST_DRAFT -> {
            if (segments.isNotEmpty()) {
                return BraxLink.Invalid("draft takes query parameters only")
            }
            val title = uri.getQueryParameter("title").orEmpty().take(200)
            val start = uri.getQueryParameter("start")?.let(::parseIsoUtc)
            val end = uri.getQueryParameter("end")?.let(::parseIsoUtc)
            if (uri.getQueryParameter("start") != null && start == null) {
                return BraxLink.Invalid("start is not ISO 8601 UTC")
            }
            if (uri.getQueryParameter("end") != null && end == null) {
                return BraxLink.Invalid("end is not ISO 8601 UTC")
            }
            if (start != null && end != null && end < start) {
                return BraxLink.Invalid("end before start")
            }
            BraxLink.Draft(
                title = title,
                startMillis = start,
                endMillis = end,
                allDay = uri.getQueryParameter("all_day") in setOf("1", "true"),
                location = uri.getQueryParameter("location").orEmpty().take(500),
                description = uri.getQueryParameter("description").orEmpty().take(2000),
            )
        }

        else -> BraxLink.Invalid("unknown link kind ${uri.host}")
    }
}

/** `2026-09-28T10:00:00Z` (seconds optional) -> epoch milliseconds, or null when malformed. */
fun parseIsoUtc(value: String): Long? {
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX")
    for (pattern in patterns) {
        try {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            return fmt.parse(value)?.time
        } catch (_: Exception) {
        }
    }
    return null
}

/**
 * Resolve an iCalendar UID to this app's own event id.
 *
 * CalDAV events reach the app through the Android calendar provider (DAVx5 or another sync adapter), which
 * keeps the UID in `UID_2445` and the resource name (`<uid>.ics`) in `_SYNC_ID`; the app stores those rows under
 * the import id `Caldav-<calendarId>-<providerRowId>`. Events imported from an .ics file keep the UID itself
 * as their import id, so that is tried too. Returns null when no synced calendar knows the UID.
 */
fun Context.findEventIdByUid(uid: String): Long? {
    eventsDB.getEventIdWithImportId(uid)?.let { return it }

    if (hasPermission(PERMISSION_READ_CALENDAR)) {
        val projection = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DELETED)
        val selection = "(${Events.UID_2445} = ? OR ${Events._SYNC_ID} = ? OR ${Events._SYNC_ID} = ?)"
        val args = arrayOf(uid, uid, "$uid.ics")
        var found: Long? = null
        queryCursorInlined(Events.CONTENT_URI, projection, selection, args) { cursor ->
            if (cursor.getIntValue(Events.DELETED) == 1) {
                return@queryCursorInlined
            }
            val providerId = cursor.getLongValue(Events._ID)
            val calendarId = cursor.getIntValue(Events.CALENDAR_ID)
            val importId = "$CALDAV-$calendarId-$providerId"
            eventsDB.getEventIdWithImportId(importId)?.let { found = it }
        }
        found?.let { return it }
    }

    // Last resort for ids that only carry the UID at the end (older imports).
    return eventsDB.getEventIdWithLastImportId("%-$uid")
}
