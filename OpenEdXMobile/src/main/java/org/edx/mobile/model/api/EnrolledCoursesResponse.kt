package org.edx.mobile.model.api

import com.google.gson.annotations.SerializedName
import org.edx.mobile.interfaces.SectionItemInterface
import org.edx.mobile.model.course.EnrollmentMode

data class EnrolledCoursesResponse(

    @SerializedName("audit_access_expires")
    var auditAccessExpires: String,

    @SerializedName("created")
    var created: String,

    @SerializedName("mode")
    var mode: String,

    @SerializedName("is_active")
    var isActive: Boolean = false,

    @SerializedName("course")
    var course: CourseEntry,

    @SerializedName("certificate")
    private val certificate: CertificateModel?,

    @SerializedName("course_modes")
    private val courseModes: List<CourseMode>?,
) : SectionItemInterface {

    var isDiscussionBlackedOut: Boolean = false

    val courseId: String
        get() = course.id

    val certificateURL: String?
        get() = certificate?.certificateURL

    val isCertificateEarned: Boolean
        get() = certificate != null && certificate.certificateURL.isNullOrEmpty().not()

    val courseSku: String?
        get() {
            if (courseModes != null && courseModes.isNotEmpty()) {
                for ((slug, _, androidSku) in courseModes) {
                    if (EnrollmentMode.VERIFIED.name.equals(slug, ignoreCase = true)) {
                        return if (androidSku.isNullOrEmpty()) null else androidSku
                    }
                }
            }
            return null
        }

    override fun isChapter(): Boolean {
        return false
    }

    override fun isSection(): Boolean {
        return false
    }

    override fun toString(): String {
        return course.name
    }

    override fun isCourse(): Boolean {
        return true
    }

    override fun isVideo(): Boolean {
        return false
    }

    override fun isDownload(): Boolean {
        return false
    }
}
