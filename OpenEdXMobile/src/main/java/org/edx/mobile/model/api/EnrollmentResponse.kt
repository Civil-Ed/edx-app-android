package org.edx.mobile.model.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.Serializable
import java.lang.reflect.Type

data class EnrollmentResponse(
    @SerializedName("config")
    val remoteConfig: RemoteConfig,

    @SerializedName("enrollments")
    val enrollments: List<EnrolledCoursesResponse>
) : Serializable {

    class EnrollmentDeserializer : JsonDeserializer<EnrollmentResponse> {

        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): EnrollmentResponse {
            json?.let {
                if (it.isJsonArray) {
                    val listType = object : TypeToken<List<EnrolledCoursesResponse>>() {}.type

                    return EnrollmentResponse(
                        RemoteConfig(),
                        Gson().fromJson((it as JsonArray), listType)
                    )
                } else if (it.isJsonObject) {
                    val remoteConfigJson = (it as JsonObject).get("config")
                    val remoteConfigType = object : TypeToken<RemoteConfig>() {}.type
                    val remoteConfig =
                        Gson().fromJson<RemoteConfig>(remoteConfigJson, remoteConfigType)

                    val enrolledCourseJson = it.get("enrollments")
                    val enrolledCourseType =
                        object : TypeToken<List<EnrolledCoursesResponse>>() {}.type
                    val enrolledCourses = Gson().fromJson<List<EnrolledCoursesResponse>>(
                        enrolledCourseJson,
                        enrolledCourseType
                    )

                    return EnrollmentResponse(remoteConfig, enrolledCourses)
                }
            }
            return EnrollmentResponse(RemoteConfig(), emptyList())
        }
    }
}

data class RemoteConfig(
    @SerializedName("value_prop_enabled")
    val isValuePropEnabled: Boolean = false
) : Serializable
