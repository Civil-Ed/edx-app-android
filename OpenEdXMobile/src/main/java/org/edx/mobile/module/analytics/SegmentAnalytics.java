package org.edx.mobile.module.analytics;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.segment.analytics.Analytics.Builder;
import com.segment.analytics.Options;
import com.segment.analytics.Properties;
import com.segment.analytics.Traits;
import com.segment.analytics.android.integrations.appboy.AppboyIntegration;
import com.segment.analytics.android.integrations.firebase.FirebaseIntegration;
import com.segment.analytics.integrations.BasePayload;
import com.segment.analytics.integrations.Integration;

import org.edx.mobile.R;
import org.edx.mobile.logger.Logger;
import org.edx.mobile.model.video.VideoQuality;
import org.edx.mobile.util.AnalyticsUtils;
import org.edx.mobile.util.Config;
import org.edx.mobile.util.JavaUtil;
import org.edx.mobile.util.images.ShareUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * A concrete implementation of {@link Analytics} to report all the screens and events to Segment.
 */
@Singleton
public class SegmentAnalytics implements Analytics {

    protected final Logger logger = new Logger(getClass().getName());
    private com.segment.analytics.Analytics tracker;

    @Inject
    public SegmentAnalytics(@ApplicationContext @NonNull Context context, @NonNull Config config) {
        if (!config.getSegmentConfig().isEnabled()) return;

        final String writeKey = config.getSegmentConfig().getSegmentWriteKey();
        final boolean debugging = context.getResources().getBoolean(R.bool.analytics_debug);
        final int queueSize = context.getResources().getInteger(R.integer.analytics_queue_size);
        final int flushInterval = context.getResources().getInteger(R.integer.analytics_flush_interval);

        // Must be called before any calls to Analytics.with(context)
        Builder builder = new Builder(context, writeKey)
                .flushQueueSize(queueSize)
                .flushInterval(flushInterval, TimeUnit.SECONDS)
                .logLevel(debugging ? com.segment.analytics.Analytics.LogLevel.VERBOSE : com.segment.analytics.Analytics.LogLevel.NONE);
        if (config.getFirebaseConfig().isAnalyticsSourceSegment()) {
            // If Segment & Firebase Analytics are enabled, we'll use Segment's Firebase integration
            Integration.Factory firebaseFactory = FirebaseIntegration.FACTORY;
            builder = builder.use(firebaseFactory)
                    // Add middleware only for Firebase Integration.
                    // Ref: https://segment.com/docs/connections/sources/catalog/libraries/mobile/android/middleware/
                    .useDestinationMiddleware(firebaseFactory.key(), chain -> {
                        BasePayload newPayload = chain.payload().toBuilder().build();
                        // remove special char from `Name and Event Name` that are not support by Firebase
                        if (newPayload.get(Keys.NAME) instanceof String) {
                            String name = String.valueOf(newPayload.get(Keys.NAME));
                            newPayload.putValue(Keys.NAME, AnalyticsUtils.Companion.removeUnSupportedCharacters(name));
                        }
                        if (newPayload.get(Keys.EVENT) instanceof String) {
                            String name = String.valueOf(newPayload.get(Keys.EVENT));
                            newPayload.putValue(Keys.EVENT, AnalyticsUtils.Companion.removeUnSupportedCharacters(name));
                        }
                        if (newPayload.get(Keys.PROPERTIES) instanceof Properties) {
                            newPayload.put(Keys.PROPERTIES, AnalyticsUtils.Companion.formatFirebaseAnalyticsData(newPayload.get(Keys.PROPERTIES)));
                        }
                        chain.proceed(newPayload);
                    });
        }
        if (config.getBrazeConfig().isEnabled() && config.getFirebaseConfig().isEnabled()) {
            builder = builder.use(AppboyIntegration.FACTORY);
        }
        tracker = builder.build();
    }

    /**
     * This function is used to send the event to Segment and log the output.
     *
     * @param eventName       The name of the event.
     * @param eventProperties The Properties of the event.
     */
    private void trackSegmentEvent(@NonNull String eventName, @NonNull Properties eventProperties) {
        String csv = "Track," + eventName;
        for (String parameterName : eventProperties.keySet()) {
            csv += "," + parameterName + "=" + eventProperties.get(parameterName);
        }
        logger.debug(csv);
        tracker.track(eventName, eventProperties);
    }

    /**
     * This function is used to send the event to Segment and log the output.
     */
    private void trackSegmentScreenEvent(@NonNull String screenCategory,
                                         @NonNull String screenName,
                                         @NonNull Properties screenProperties) {
        String csv = "Screen," + screenName;
        for (String pName : screenProperties.keySet()) {
            csv += "," + pName + "=" + screenProperties.get(pName);
        }
        Log.d(SegmentAnalytics.class.getName(), csv);
        tracker.screen(screenCategory, screenName, screenProperties);
    }


    /**
     * This function is used to send the screen tracking event, with an extra event for
     * sending course id.
     *
     * @param screenName The screen name to track
     * @param courseId   course id of the course we are viewing
     * @param action     any custom action we need to send with event
     * @param values     any custom key-value pairs we need to send with event
     */
    @Override
    public void trackScreenView(@NonNull String screenName, @Nullable String courseId,
                                @Nullable String action,
                                @Nullable Map<String, String> values) {
        // Sending screen view
        SegmentEvent aEvent = new SegmentEvent();
        if (!TextUtils.isEmpty(action)) {
            aEvent.properties.put(Keys.ACTION, action);
        }
        if (!TextUtils.isEmpty(courseId)) {
            aEvent.properties.put(Keys.COURSE_ID, courseId);
        }
        if (values != null) {
            aEvent.data.putAll(values);
        }
        trackSegmentScreenEvent("", screenName, aEvent.properties);

        // Sending screen event
        addCategoryToBiEvents(aEvent.properties, Values.SCREEN, screenName);
        trackSegmentEvent(screenName, aEvent.properties);
    }

    /**
     * This function is used to track Video Loading
     *
     * @param videoId
     * @param courseId
     * @param unitUrl
     */
    @Override
    public void trackVideoLoading(String videoId, String courseId, String unitUrl) {
        SegmentEvent aEvent = getCommonProperties(videoId, Values.VIDEO_LOADED);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);
        trackSegmentEvent(Events.LOADED_VIDEO, aEvent.properties);
    }

    /**
     * This function is used to track Video Playing
     *
     * @param videoId     -  Video Id that is being Played
     * @param currentTime -  Video Playing started at
     * @param courseId    -  CourseId under which the video is present
     * @param unitUrl     -  Page Url for that Video
     * @param playMedium  -  Player Medium {@link Analytics.Values.GOOGLE_CAST}
     */
    @Override
    public void trackVideoPlaying(String videoId, Double currentTime,
                                  String courseId, String unitUrl, String playMedium) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.VIDEO_PLAYED);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);
        if (!TextUtils.isEmpty(playMedium)) {
            aEvent.data.put(Keys.PLAY_MEDIUM, playMedium);
        }
        trackSegmentEvent(Events.PLAYED_VIDEO, aEvent.properties);
    }

    /**
     * This function is used to track Video Pause
     *
     * @param videoId     -  Video Id that is being Played
     * @param currentTime -  Video Playing started at
     * @param courseId    -  CourseId under which the video is present
     * @param unitUrl     -  Page Url for that Video
     * @param playMedium  - Player Medium {@link Analytics.Values.GOOGLE_CAST}
     */
    @Override
    public void trackVideoPause(String videoId,
                                Double currentTime, String courseId, String unitUrl, String playMedium) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.VIDEO_PAUSED);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);
        if (!TextUtils.isEmpty(playMedium)) {
            aEvent.data.put(Keys.PLAY_MEDIUM, playMedium);
        }
        trackSegmentEvent(Events.PAUSED_VIDEO, aEvent.properties);
    }

    /**
     * This function is used to track Video Stop
     *
     * @param videoId
     * @param currentTime
     * @param courseId
     * @param unitUrl
     */
    @Override
    public void trackVideoStop(String videoId, Double currentTime, String courseId,
                               String unitUrl) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.VIDEO_STOPPED);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);

        trackSegmentEvent(Events.STOPPED_VIDEO, aEvent.properties);
    }

    /**
     * This function is used to track 30 second rewind on Video
     *
     * @param videoId
     * @param oldTime
     * @param newTime
     * @param courseId
     * @param unitUrl
     * @param skipSeek
     */
    @Override
    public void trackVideoSeek(String videoId,
                               Double oldTime, Double newTime, String courseId, String unitUrl, Boolean skipSeek) {
        SegmentEvent aEvent = getCommonProperties(videoId, Values.VIDEO_SEEKED);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);
        //Call the format Double value so that we can have upto 3 decimal places after
        oldTime = JavaUtil.formatDoubleValue(oldTime, 3);
        newTime = JavaUtil.formatDoubleValue(newTime, 3);
        Double skipInterval = newTime - oldTime;
        skipInterval = JavaUtil.formatDoubleValue(skipInterval, 3);
        aEvent.data.putValue(Keys.OLD_TIME, oldTime);
        aEvent.data.putValue(Keys.NEW_TIME, newTime);
        if (skipSeek) {
            aEvent.data.putValue(Keys.SEEK_TYPE, Values.SKIP);
        } else {
            aEvent.data.putValue(Keys.SEEK_TYPE, Values.SLIDE);
        }
        aEvent.data.putValue(Keys.REQUESTED_SKIP_INTERVAL, skipInterval);

        trackSegmentEvent(Events.SEEK_VIDEO, aEvent.properties);
    }

    /**
     * This function is used to Show Transcript
     *
     * @param videoId
     * @param currentTime
     * @param courseId
     * @param unitUrl
     */
    @Override
    public void trackShowTranscript(String videoId, Double currentTime, String courseId,
                                    String unitUrl) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.TRANSCRIPT_SHOWN);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);

        trackSegmentEvent(Events.SHOW_TRANSCRIPT, aEvent.properties);
    }

    /**
     * This function is used to track the video playback speed changes
     *
     * @param videoId
     * @param currentTime
     * @param courseId
     * @param unitUrl
     * @param oldSpeed
     * @param newSpeed
     */
    @Override
    public void trackVideoSpeed(String videoId, Double currentTime, String courseId,
                                String unitUrl, float oldSpeed, float newSpeed) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.VIDEO_PLAYBACK_SPEED_CHANGED);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);
        aEvent.data.putValue(Keys.NEW_SPEED, newSpeed);
        aEvent.data.putValue(Keys.OLD_SPEED, oldSpeed);
        trackSegmentEvent(Events.SPEED_CHANGE_VIDEO, aEvent.properties);
    }

    /**
     * This function is used to Hide Transcript
     *
     * @param videoId
     * @param currentTime
     * @param courseId
     * @param unitUrl
     * @return A {@link Properties} object populated with analytics-event info
     */
    @Override
    public void trackHideTranscript(String videoId, Double currentTime, String courseId,
                                    String unitUrl) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.TRANSCRIPT_HIDDEN);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);

        trackSegmentEvent(Events.HIDE_TRANSCRIPT, aEvent.properties);
    }

    /**
     * This resets the Identify user once the user has logged out
     */
    @Override
    public void resetIdentifyUser() {
        tracker.flush();
    }


    /**
     * This function is used to track Video Download completed
     *
     * @param videoId  -  Video id for which download has started
     * @param courseId
     * @param unitUrl
     */
    @Override
    public void trackDownloadComplete(String videoId, String courseId,
                                      String unitUrl) {
        SegmentEvent aEvent = getCommonProperties(videoId, Values.VIDEO_DOWNLOADED);
        aEvent.setCourseContext(courseId, unitUrl, Values.DOWNLOAD_MODULE);

        trackSegmentEvent(Events.VIDEO_DOWNLOADED, aEvent.properties);
    }

    @Override
    public void trackCourseUpgradeSuccess(String blockId, String courseId, String minifiedBlockId) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.USER_COURSE_UPGRADE_SUCCESS);
        aEvent.data.putValue(Keys.BLOCK_ID, blockId);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties, Values.CONVERSION, courseId);
        trackSegmentEvent(Events.COURSE_UPGRADE_SUCCESS, aEvent.properties);
    }

    /**
     * This function is used to track Bulk Download from Subsection
     *
     * @param section      -   Section in which the subsection is present
     * @param subSection   -  Subsection from which the download started
     * @param enrollmentId -  Course under which the subsection is present
     * @param videoCount   -  no of videos started downloading
     */
    @Override
    public void trackSubSectionBulkVideoDownload(String section,
                                                 String subSection, String enrollmentId, long videoCount) {
        SegmentEvent aEvent = new SegmentEvent();
        if (section != null && subSection != null) {
            aEvent.data.putValue(Keys.COURSE_SECTION, section);
            aEvent.data.putValue(Keys.COURSE_SUBSECTION, subSection);
        }
        aEvent.data.putValue(Keys.NO_OF_VIDEOS, videoCount);
        aEvent.properties.putValue(Keys.NAME, Values.BULK_DOWNLOAD_SUBSECTION);
        aEvent.setCourseContext(enrollmentId,
                null, Values.DOWNLOAD_MODULE);

        trackSegmentEvent(Events.BULK_DOWNLOAD_SUBSECTION, aEvent.properties);
    }

    /**
     * This function is used to track Video Download started from Video List
     *
     * @param videoId  -  Video id for which download has started
     * @param courseId
     * @param unitUrl
     * @return A {@link Properties} object populated with analytics-event info
     */
    @Override
    public void trackSingleVideoDownload(String videoId, String courseId,
                                         String unitUrl) {
        SegmentEvent aEvent = getCommonProperties(videoId,
                Values.SINGLE_VIDEO_DOWNLOAD);
        aEvent.setCourseContext(courseId,
                unitUrl, Values.DOWNLOAD_MODULE);
        trackSegmentEvent(Events.SINGLE_VIDEO_DOWNLOAD, aEvent.properties);
    }

    /**
     * This function is used to track Video Orientation
     *
     * @param videoId
     * @param currentTime
     * @param isLandscape -  true / false based on orientation
     * @param courseId
     * @param unitUrl
     * @param playMedium  - Player Medium {@link Analytics.Values.GOOGLE_CAST}
     * @return A {@link Properties} object populated with analytics-event info
     */
    @Override
    public void trackVideoOrientation(String videoId, Double currentTime,
                                      boolean isLandscape, String courseId, String unitUrl, String playMedium) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.FULLSREEN_TOGGLED);
        aEvent.data.putValue(Keys.FULLSCREEN, isLandscape);
        aEvent.setCourseContext(courseId, unitUrl, Values.VIDEOPLAYER);
        if (!TextUtils.isEmpty(playMedium)) {
            aEvent.data.put(Keys.PLAY_MEDIUM, playMedium);
        }
        trackSegmentEvent(Events.SCREEN_TOGGLED, aEvent.properties);
    }

    @Override
    public void trackCoursesSearch(String searchQuery, boolean isLoggedIn, String versionName) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.DISCOVERY_COURSES_SEARCH);
        aEvent.properties.putValue(Keys.LABEL, searchQuery);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        aEvent.data.putValue(Keys.ACTION, isLoggedIn ? Values.DISCOVERY_COURSES_SEARCH_TAB : Values.DISCOVERY_COURSES_SEARCH_LANDING);
        trackSegmentEvent(Events.DISCOVERY_COURSES_SEARCH, aEvent.properties);
    }

    /**
     * This function is used to track User Login activity
     */
    @Override
    public void trackUserLogin(String method) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.USERLOGIN);
        //More information regarding a track event should be under 'data'
        if (method != null) {
            aEvent.data.putValue(Keys.METHOD, method);
        }

        trackSegmentEvent(Events.USER_LOGIN, aEvent.properties);
    }

    /**
     * This function is used to track user logout
     */
    @Override
    public void trackUserLogout() {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.USERLOGOUT);
        trackSegmentEvent(Events.USER_LOGOUT, aEvent.properties);
    }

    /**
     * This function is used to track launching the browser
     *
     * @param url
     */
    @Override
    public void trackBrowserLaunched(String url) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.BROWSER_LAUNCHED);
        if (url != null) {
            aEvent.data.putValue(Keys.TARGET_URL, url);
        }

        trackSegmentEvent(Events.BROWSER_LAUNCHED, aEvent.properties);
    }

    /**
     * This function is used to track Language changed for Transcripts
     */
    @Override
    public void trackTranscriptLanguage(String videoId,
                                        Double currentTime, String lang, String courseId, String unitUrl) {
        SegmentEvent aEvent = getCommonPropertiesWithCurrentTime(currentTime,
                videoId, Values.TRANSCRIPT_LANGUAGE);
        aEvent.properties.putValue(Keys.LANGUAGE, lang);
        aEvent.setCourseContext(courseId, unitUrl,
                Values.VIDEOPLAYER);

        trackSegmentEvent(Events.LANGUAGE_CLICKED, aEvent.properties);
    }

    /**
     * This function is used to track if user clicks on Sign up on landing page
     */
    @Override
    public void trackUserSignUpForAccount() {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.USER_NO_ACCOUNT);

        trackSegmentEvent(Events.SIGN_UP, aEvent.properties);

    }

    /**
     * This function is used to track if user clicks on Find Courses
     */
    @Override
    public void trackUserFindsCourses() {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.USER_FIND_COURSES);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.USER_ENGAGEMENT, Values.COURSE_DISCOVERY);
        trackSegmentEvent(Events.FIND_COURSES, aEvent.properties);
    }

    /**
     * This function is used to track if user clicks on Create Account on registration screen
     */
    @Override
    public void trackCreateAccountClicked(@NonNull String appVersion, @Nullable String source) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.CREATE_ACCOUNT_CLICKED);
        if (!TextUtils.isEmpty(source))
            aEvent.properties.putValue(Keys.PROVIDER, source);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.CONVERSION, appVersion);
        trackSegmentEvent(Events.CREATE_ACCOUNT_CLICKED, aEvent.properties);
    }

    @Override
    public void trackRegistrationSuccess(@NonNull String appVersion, @Nullable String source) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.USER_REGISTRATION_SUCCESS);
        if (!TextUtils.isEmpty(source)) {
            aEvent.properties.putValue(Keys.PROVIDER, source);
        }

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties, Values.CONVERSION, appVersion);
        trackSegmentEvent(Events.REGISTRATION_SUCCESS, aEvent.properties);
    }

    /**
     * This function is used to track if user clicks on Enroll in the FindCourses Activity
     *
     * @param courseId     - Course Id for which user selected enroll
     * @param email_opt_in - Flag to show user wants to opt in for email notification
     */
    @Override
    public void trackEnrollClicked(@NonNull String courseId, boolean email_opt_in) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.EMAIL_OPT_IN, email_opt_in);
        aEvent.properties.putValue(Keys.NAME, Values.USER_COURSE_ENROLL_CLICKED);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties, Values.CONVERSION, courseId);
        trackSegmentEvent(Events.COURSE_ENROLL_CLICKED, aEvent.properties);
    }

    @Override
    public void trackEnrolmentSuccess(@NonNull String courseId, boolean emailOptIn) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.EMAIL_OPT_IN, emailOptIn);
        aEvent.properties.putValue(Keys.NAME, Values.USER_COURSE_ENROLL_SUCCESS);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties, Values.CONVERSION, courseId);
        trackSegmentEvent(Events.COURSE_ENROLL_SUCCESS, aEvent.properties);
    }

    //Tracking methods introduced by BNOTIONS

    @Override
    public void trackUserConnectionSpeed(String connectionType, float connectionSpeed) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.CONNECTION_SPEED);
        aEvent.data.putValue(Keys.CONNECTION_TYPE, connectionType);
        aEvent.data.putValue(Keys.CONNECTION_SPEED, connectionSpeed);

        trackSegmentEvent(Events.SPEED, aEvent.properties);
    }

    @Override
    public void trackNotificationReceived(@Nullable String courseId) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.NOTIFICATION_RECEIVED);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties, Values.PUSH_NOTIFICATION, courseId);
        trackSegmentEvent(Events.PUSH_NOTIFICATION_RECEIVED, aEvent.properties);
    }

    @Override
    public void trackNotificationTapped(@Nullable String courseId) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.NOTIFICATION_TAPPED);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties, Values.PUSH_NOTIFICATION, courseId);
        trackSegmentEvent(Events.PUSH_NOTIFICATION_TAPPED, aEvent.properties);
    }

    @Override
    public void courseDetailShared(String courseId, String aboutUrl, ShareUtils.ShareType method) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.SOCIAL_COURSE_DETAIL_SHARED);

        aEvent.data.putValue(Keys.NAME, courseId);
        aEvent.data.putValue(Keys.CATEGORY, Values.SOCIAL_SHARING);
        aEvent.data.putValue(Keys.URL, aboutUrl);
        aEvent.data.putValue(Keys.TYPE, AnalyticsUtils.Companion.getShareTypeValue(method));
        trackSegmentEvent(Events.SOCIAL_COURSE_DETAIL_SHARED, aEvent.properties);
    }

    @Override
    public void certificateShared(String courseId, String certificateUrl, ShareUtils.ShareType method) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.SOCIAL_CERTIFICATE_SHARED);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.CATEGORY, Values.SOCIAL_SHARING);
        aEvent.data.putValue(Keys.URL, certificateUrl);
        aEvent.data.putValue(Keys.TYPE, AnalyticsUtils.Companion.getShareTypeValue(method));
        trackSegmentEvent(Events.SOCIAL_CERTIFICATE_SHARED, aEvent.properties);
    }

    @Override
    public void trackCourseComponentViewed(String blockId, String courseId, String minifiedBlockId) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.COMPONENT_VIEWED);
        aEvent.data.putValue(Keys.BLOCK_ID, blockId);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);

        //Add category for Google Analytics
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.NAVIGATION, Keys.COMPONENT_VIEWED);
        trackSegmentEvent(Events.COMPONENT_VIEWED, aEvent.properties);
    }

    @Override
    public void trackOpenInBrowser(String blockId, String courseId, boolean isSupported,
                                   String minifiedBlockId) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.OPEN_IN_BROWSER);
        aEvent.data.putValue(Keys.BLOCK_ID, blockId);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.SUPPORTED, isSupported);

        //Add category for Google Analytics
        String label = (isSupported ? Values.OPEN_IN_WEB_SUPPORTED : Values.OPEN_IN_WEB_NOT_SUPPORTED);
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.NAVIGATION, label);
        trackSegmentEvent(Events.OPEN_IN_BROWSER, aEvent.properties);
    }

    @Override
    public void trackProfileViewed(@NonNull String username) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.PROFILE_VIEWED);
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.PROFILE, username);
        trackSegmentEvent(Events.PROFILE_VIEWED, aEvent.properties);
    }

    @Override
    public void trackProfilePhotoSet(boolean fromCamera) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.PROFILE_PHOTO_SET);
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.PROFILE, fromCamera ? Values.CAMERA : Values.LIBRARY);
        trackSegmentEvent(Events.PROFILE_PHOTO_SET, aEvent.properties);
    }

    /**
     * This function is set to identify the user for subsequent calls
     *
     * @param userID   - User Id from the server
     * @param email    -  email of the user
     * @param username -  Username/email that the user uses for signing in
     */
    @Override
    public void identifyUser(String userID, String email, String username) {
        Traits traits = new Traits();
        traits.putEmail(email);
        traits.putUsername(username);
        tracker.identify(userID, traits, new Options());
    }

    /**
     * This method sets category and labels to BI events
     *
     * @param props
     * @param category
     * @param label
     * @return An updated {@link Properties} object with CATEGORY and LABEL
     */
    private Properties addCategoryToBiEvents(Properties props, String category, String label) {
        props.put(Keys.CATEGORY, category);
        props.put(Keys.LABEL, label);
        return props;
    }

    /**
     * This function is used for getting common properties object having Module and Code
     *
     * @param videoId
     * @param eventName
     * @return The {@link SegmentEvent} updated with provided with arguments
     */
    private SegmentEvent getCommonProperties(String videoId, String eventName) {
        SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, eventName);
        if (videoId != null) {
            aEvent.data.putValue(Keys.MODULE_ID, videoId);
        }
        aEvent.data.putValue(Keys.CODE, Values.MOBILE);
        return aEvent;
    }

    /**
     * This function is used for getting common properties object having Module and Code and Current Time
     *
     * @param currentTime
     * @param videoId
     * @return The {@linkSegmentEvent} updated with provided with arguments
     */
    private SegmentEvent getCommonPropertiesWithCurrentTime(Double currentTime,
                                                            String videoId, String eventName) {
        SegmentEvent aEvent = getCommonProperties(videoId, eventName);
        if (currentTime != null) {
            currentTime = JavaUtil.formatDoubleValue(currentTime, 3);
            aEvent.data.putValue(Keys.CURRENT_TIME, currentTime);
        }
        return aEvent;
    }

    @Override
    public void trackAppRatingDialogViewed(String versionName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.APP_REVIEWS_VIEW_RATING);
        aEvent.data.putValue(Keys.CATEGORY, Values.APP_REVIEWS_CATEGORY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        trackSegmentEvent(Events.APP_REVIEWS_VIEW_RATING, aEvent.properties);
    }

    @Override
    public void trackAppRatingDialogCancelled(String versionName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.APP_REVIEWS_DISMISS_RATING);
        aEvent.data.putValue(Keys.CATEGORY, Values.APP_REVIEWS_CATEGORY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        trackSegmentEvent(Events.APP_REVIEWS_DISMISS_RATING, aEvent.properties);
    }

    @Override
    public void trackUserSubmitRating(String versionName, int rating) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.APP_REVIEWS_SUBMIT_RATING);
        aEvent.data.putValue(Keys.CATEGORY, Values.APP_REVIEWS_CATEGORY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        aEvent.data.putValue(Keys.RATING, rating);
        trackSegmentEvent(Events.APP_REVIEWS_SUBMIT_RATING, aEvent.properties);
    }

    @Override
    public void trackUserSendFeedback(String versionName, int rating) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.APP_REVIEWS_SEND_FEEDBACK);
        aEvent.data.putValue(Keys.CATEGORY, Values.APP_REVIEWS_CATEGORY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        aEvent.data.putValue(Keys.RATING, rating);
        trackSegmentEvent(Events.APP_REVIEWS_SEND_FEEDBACK, aEvent.properties);
    }

    @Override
    public void trackUserMayReviewLater(String versionName, int rating) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.APP_REVIEWS_MAYBE_LATER);
        aEvent.data.putValue(Keys.CATEGORY, Values.APP_REVIEWS_CATEGORY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        aEvent.data.putValue(Keys.RATING, rating);
        trackSegmentEvent(Events.APP_REVIEWS_MAYBE_LATER, aEvent.properties);
    }

    @Override
    public void trackRateTheAppClicked(String versionName, int rating) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.APP_REVIEWS_RATE_THE_APP);
        aEvent.data.putValue(Keys.CATEGORY, Values.APP_REVIEWS_CATEGORY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        aEvent.data.putValue(Keys.RATING, rating);
        trackSegmentEvent(Events.APP_REVIEWS_RATE_THE_APP, aEvent.properties);
    }

    @Override
    public void trackWhatsNewClosed(@NonNull String versionName, int totalViewed, int currentlyViewed, int totalScreens) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.WHATS_NEW_CLOSE);
        aEvent.data.putValue(Keys.CATEGORY, Values.WHATS_NEW_CATEGORY);
        aEvent.data.putValue(Keys.TOTAL_VIEWED, totalViewed);
        aEvent.data.putValue(Keys.CURRENTLY_VIEWED, currentlyViewed);
        aEvent.data.putValue(Keys.TOTAL_SCREENS, totalScreens);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        trackSegmentEvent(Events.WHATS_NEW_CLOSE, aEvent.properties);
    }

    @Override
    public void trackWhatsNewSeen(@NonNull String versionName, int totalScreens) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.WHATS_NEW_DONE);
        aEvent.data.putValue(Keys.CATEGORY, Values.WHATS_NEW_CATEGORY);
        aEvent.data.putValue(Keys.TOTAL_SCREENS, totalScreens);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        trackSegmentEvent(Events.WHATS_NEW_DONE, aEvent.properties);
    }

    @Override
    public void trackSubsectionVideosDelete(@NonNull String courseId, @NonNull String subsectionId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.VIDEOS_SUBSECTION_DELETE);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.SUBSECTION_ID, subsectionId);
        trackSegmentEvent(Events.VIDEOS_SUBSECTION_DELETE, aEvent.properties);
    }

    @Override
    public void trackUndoingSubsectionVideosDelete(@NonNull String courseId, @NonNull String subsectionId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.VIDEOS_UNDO_SUBSECTION_DELETE);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.SUBSECTION_ID, subsectionId);
        trackSegmentEvent(Events.VIDEOS_UNDO_SUBSECTION_DELETE, aEvent.properties);
    }

    @Override
    public void trackUnitVideoDelete(@NonNull String courseId, @NonNull String unitId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.VIDEOS_UNIT_DELETE);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.UNIT_ID, unitId);
        trackSegmentEvent(Events.VIDEOS_UNIT_DELETE, aEvent.properties);
    }

    @Override
    public void trackUndoingUnitVideoDelete(@NonNull String courseId, @NonNull String unitId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.VIDEOS_UNDO_UNIT_DELETE);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.UNIT_ID, unitId);
        trackSegmentEvent(Events.VIDEOS_UNDO_UNIT_DELETE, aEvent.properties);
    }

    @Override
    public void trackBulkDownloadSwitchOn(@NonNull String courseId, int totalDownloadableVideos, int remainingDownloadableVideos) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.BULK_DOWNLOAD_SWITCH_ON);
        aEvent.properties.putValue(Keys.COMPONENT, Values.DOWNLOAD_MODULE);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.TOTAL_DOWNLOADABLE_VIDEOS, totalDownloadableVideos);
        aEvent.data.putValue(Keys.REMAINING_DOWNLOADABLE_VIDEOS, remainingDownloadableVideos);
        trackSegmentEvent(Events.BULK_DOWNLOAD_TOGGLE_ON, aEvent.properties);
    }

    @Override
    public void trackBulkDownloadSwitchOff(@NonNull String courseId, int totalDownloadableVideos) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.BULK_DOWNLOAD_SWITCH_OFF);
        aEvent.properties.putValue(Keys.COMPONENT, Values.DOWNLOAD_MODULE);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.TOTAL_DOWNLOADABLE_VIDEOS, totalDownloadableVideos);
        trackSegmentEvent(Events.BULK_DOWNLOAD_TOGGLE_OFF, aEvent.properties);

    }

    @Override
    public void trackExperimentParams(String experimentName, Map<String, String> values) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putAll(values);
        trackSegmentEvent(experimentName, aEvent.properties);
    }

    @Override
    public void trackCastDeviceConnectionChanged(@NonNull String eventName, @NonNull String connectionState, @NonNull String playMedium) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, connectionState);
        if (!TextUtils.isEmpty(playMedium)) {
            aEvent.data.put(Keys.PLAY_MEDIUM, playMedium);
        }
        trackSegmentEvent(eventName, aEvent.properties);
    }

    @Override
    public void trackPLSCourseDatesBanner(@NonNull String biValue, @NonNull String courseId,
                                          @NonNull String enrollmentMode, @NonNull String screenName,
                                          @NonNull String bannerType) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, biValue);
        aEvent.properties.putValue(Keys.CATEGORY, Values.COURSE_DATES);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.MODE, enrollmentMode);
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        aEvent.data.putValue(Keys.BANNER_TYPE, bannerType);
        trackSegmentEvent(Events.PLS_BANNER_VIEWED, aEvent.properties);
    }

    @Override
    public void trackPLSShiftButtonTapped(@NonNull String courseId, @NonNull String enrollmentMode,
                                          @NonNull String screenName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.CATEGORY, Values.COURSE_DATES);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.MODE, enrollmentMode);
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        trackSegmentEvent(Events.PLS_SHIFT_DATES_BUTTON_TAPPED, aEvent.properties);
    }

    @Override
    public void trackPLSCourseDatesShift(@NonNull String courseId, @NonNull String enrollmentMode,
                                         @NonNull String screenName, boolean isSuccess) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.CATEGORY, Values.COURSE_DATES);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.MODE, enrollmentMode);
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        aEvent.data.putValue(Keys.SUCCESS, isSuccess);
        trackSegmentEvent(Events.PLS_SHIFT_DATES, aEvent.properties);
    }

    @Override
    public void trackValuePropModalView(@NonNull String courseId, @NonNull String screenName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        trackSegmentEvent(Events.VALUE_PROP_MODAL_VIEW, aEvent.properties);
    }

    @Override
    public void trackValuePropLearnMoreTapped(@NonNull String courseId, @NonNull String screenName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.VALUE_PROP_LEARN_MORE_CLICKED);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        trackSegmentEvent(Events.VALUE_PROP_LEARN_MORE_CLICKED, aEvent.properties);
    }

    @Override
    public void trackLockedContentTapped(@NonNull String courseId, @NonNull String assignmentId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.ASSIGNMENT_ID, assignmentId);
        aEvent.data.putValue(Keys.SCREEN_NAME, Screens.COURSE_UNIT);
        trackSegmentEvent(Events.COURSE_UNIT_LOCKED_CONTENT, aEvent.properties);
    }

    @Override
    public void trackValuePropShowMoreLessClicked(@NonNull String courseId, @Nullable String componentId,
                                                  @NonNull String price, boolean isSelfPaced, boolean showMore) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, showMore ? Values.VALUE_PROP_SHOW_MORE_CLICKED : Values.VALUE_PROP_SHOW_LESS_CLICKED);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.PRICE, price);
        if (!TextUtils.isEmpty(componentId)) {
            aEvent.data.putValue(Keys.COMPONENT_ID, componentId);
        }
        aEvent.data.putValue(Keys.PACING, isSelfPaced ? Keys.SELF : Keys.INSTRUCTOR);
        trackSegmentEvent(showMore ? Events.VALUE_PROP_SHOW_MORE_CLICKED : Events.VALUE_PROP_SHOW_LESS_CLICKED, aEvent.properties);
    }

    @Override
    public void trackExploreAllCoursesTapped(String versionName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.EXPLORE_ALL_COURSES);
        aEvent.properties = addCategoryToBiEvents(aEvent.properties,
                Values.USER_ENGAGEMENT, Values.DISCOVERY);
        aEvent.data.putValue(Keys.APP_VERSION, versionName);
        aEvent.data.putValue(Keys.ACTION, Values.DISCOVERY_COURSES_SEARCH_LANDING);
        trackSegmentEvent(Events.EXPLORE_ALL_COURSES, aEvent.properties);
    }

    @Override
    public void trackDatesCourseComponentTapped(@NonNull String courseId, @NonNull String blockId, @NonNull String blockType, @NonNull String link) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.COURSE_DATES_COMPONENT_TAPPED);
        aEvent.properties.putValue(Keys.CATEGORY, Values.COURSE_DATES);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.BLOCK_ID, blockId);
        aEvent.data.putValue(Keys.BLOCK_TYPE, blockType);
        aEvent.data.putValue(Keys.LINK, link);
        trackSegmentEvent(Events.DATES_COURSE_COMPONENT_TAPPED, aEvent.properties);
    }

    @Override
    public void trackUnsupportedComponentTapped(@NonNull String courseId, @NonNull String blockId, @NonNull String link) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.COURSE_DATES_UNSUPPORTED_COMPONENT_TAPPED);
        aEvent.properties.putValue(Keys.CATEGORY, Values.COURSE_DATES);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.BLOCK_ID, blockId);
        aEvent.data.putValue(Keys.LINK, link);
        trackSegmentEvent(Events.DATES_UNSUPPORTED_COMPONENT_TAPPED, aEvent.properties);
    }

    @Override
    public void trackCourseSectionCelebration(@NonNull String courseId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.properties.putValue(Keys.NAME, Values.COURSE_SECTION_COMPLETION_CELEBRATION);
        trackSegmentEvent(Events.COURSE_SECTION_COMPLETION_CELEBRATION, aEvent.properties);
    }

    @Override
    public void trackCourseCelebrationShareClicked(@NonNull String courseId, @Nullable String socialService) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        if (!TextUtils.isEmpty(socialService)) {
            aEvent.data.putValue(Keys.SERVICE, socialService);
        }
        aEvent.properties.putValue(Keys.NAME, Values.COURSE_SECTION_CELEBRATION_SHARE_CLICKED);
        trackSegmentEvent(Events.CELEBRATION_SOCIAL_SHARE_CLICKED, aEvent.properties);
    }

    @Override
    public void trackResumeCourseBannerTapped(@NonNull String courseId, @NonNull String blockId) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.RESUME_COURSE_BANNER_TAPPED);
        aEvent.properties.putValue(Keys.CATEGORY, Values.NAVIGATION);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.BLOCK_ID, blockId);
        trackSegmentEvent(Events.RESUME_COURSE_TAPPED, aEvent.properties);
    }

    @Override
    public void trackSubsectionViewOnWebTapped(@NonNull String courseId, @NonNull String subsectionId, boolean isSpecialExamInfo) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.SUBSECTION_VIEW_ON_WEB_TAPPED);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.SUBSECTION_ID, subsectionId);
        aEvent.data.putValue(Keys.SPECIAL_EXAM_INFO, isSpecialExamInfo);
        trackSegmentEvent(Events.SUBSECTION_VIEW_ON_WEB_TAPPED, aEvent.properties);
    }

    @Override
    public void trackCalendarEvent(@NonNull String eventName, @NonNull String biValue,
                                   @NonNull String courseId, @NonNull String userType,
                                   @NonNull boolean isSelfPaced, @Nullable long elapsedTime) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, biValue);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.USER_TYPE, userType);
        aEvent.data.putValue(Keys.PACING, isSelfPaced ? Keys.SELF : Keys.INSTRUCTOR);
        if (elapsedTime > 0L) {
            aEvent.data.putValue(Keys.ELAPSED_TIME, elapsedTime);
        }
        trackSegmentEvent(eventName, aEvent.properties);
    }

    @Override
    public void trackOpenInBrowserBannerEvent(@NonNull String eventName, @NonNull String biValue,
                                              @NonNull String userType, @NonNull String courseId,
                                              @NonNull String componentId, @NonNull String componentType,
                                              @NonNull @NotNull String openedUrl) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, biValue);
        aEvent.data.putValue(Keys.USER_TYPE, userType);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.COMPONENT_ID, componentId);
        aEvent.data.putValue(Keys.COMPONENT_TYPE, componentType);
        aEvent.data.putValue(Keys.OPENED_URL, openedUrl);
        trackSegmentEvent(eventName, aEvent.properties);
    }

    @Override
    public void trackScreenViewEvent(@NonNull String eventName, @NonNull String screenName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        aEvent.properties.putValue(Keys.NAME, Values.SCREEN_NAVIGATION);
        trackSegmentEvent(Events.PROFILE_PAGE_VIEWED, aEvent.properties);
    }

    @Override
    public void trackVideoDownloadQualityChanged(@NonNull VideoQuality selectedVideoQuality, @NonNull VideoQuality oldVideoQuality) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Analytics.Values.VIDEO_DOWNLOAD_QUALITY_CHANGED);
        aEvent.data.putValue(Keys.VALUE, selectedVideoQuality.getValue());
        aEvent.data.putValue(Keys.OLD_VALUE, oldVideoQuality.getValue());
        trackSegmentEvent(Analytics.Events.VIDEO_DOWNLOAD_QUALITY_CHANGED, aEvent.properties);
    }

    @Override
    public void trackEvent(@NonNull String eventName, @NonNull String biValue) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, biValue);
        trackSegmentEvent(eventName, aEvent.properties);
    }

    @Override
    public void trackOpenInBrowserAlertTriggerEvent(@NonNull String url) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.DISCOVERY_OPEN_IN_BROWSER_ALERT_TRIGGERED);
        aEvent.properties.putValue(Keys.CATEGORY, Values.DISCOVERY);
        aEvent.data.putValue(Keys.URL, url);
        trackSegmentEvent(Events.DISCOVERY_OPEN_IN_BROWSER_ALERT_TRIGGERED, aEvent.properties);
    }

    @Override
    public void trackOpenInBrowserAlertActionTaken(@NonNull String url, @NonNull String actionTaken) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, Values.DISCOVERY_OPEN_IN_BROWSER_ALERT_ACTION_TAKEN);
        aEvent.properties.putValue(Keys.CATEGORY, Values.DISCOVERY);
        aEvent.data.putValue(Keys.URL, url);
        aEvent.data.putValue(Keys.ALERT_ACTION, actionTaken);
        trackSegmentEvent(Events.DISCOVERY_OPEN_IN_BROWSER_ALERT_ACTION_TAKEN, aEvent.properties);
    }

    @Override
    public void trackInAppPurchasesEvent(@NonNull String eventName, @NonNull String biValue,
                                         @NonNull String courseId, boolean isSelfPaced,
                                         @Nullable String price, @Nullable String componentId,
                                         long elapsedTime, @Nullable String error, @Nullable String errorAction,
                                         @NonNull String screenName) {
        final SegmentEvent aEvent = new SegmentEvent();
        aEvent.properties.putValue(Keys.NAME, biValue);
        aEvent.properties.putValue(Keys.CATEGORY, Values.IN_APP_PURCHASES);
        aEvent.data.putValue(Keys.COURSE_ID, courseId);
        aEvent.data.putValue(Keys.PACING, isSelfPaced ? Keys.SELF : Keys.INSTRUCTOR);
        if (!TextUtils.isEmpty(price)) {
            aEvent.data.putValue(Keys.PRICE, price);
        }
        if (!TextUtils.isEmpty(componentId)) {
            aEvent.data.putValue(Keys.COMPONENT_ID, componentId);
        }
        if (elapsedTime > 0L) {
            aEvent.data.putValue(Keys.ELAPSED_TIME, elapsedTime);
        }
        if (!TextUtils.isEmpty(error)) {
            aEvent.data.putValue(Keys.ERROR, error);
        }
        if (!TextUtils.isEmpty(errorAction)) {
            aEvent.data.putValue(Keys.ERROR_ACTION, errorAction);
        }
        aEvent.data.putValue(Keys.SCREEN_NAME, screenName);
        trackSegmentEvent(eventName, aEvent.properties);
    }
}
