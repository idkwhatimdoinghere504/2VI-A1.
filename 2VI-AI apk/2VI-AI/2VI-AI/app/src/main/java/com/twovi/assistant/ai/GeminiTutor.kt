package com.twovi.assistant.ai

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class StudyImage(
    val base64: String,
    val mimeType: String,
)

class GeminiTutor {
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun solve(
        apiKey: String,
        subject: String,
        question: String,
        image: StudyImage?,
    ): String = withContext(Dispatchers.IO) {
        val parts = JSONArray()
        val questionText = question.trim().ifEmpty {
            "Read the attached study question and explain how to solve it."
        }
        parts.put(JSONObject().put("text", questionText))
        if (image != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", image.mimeType)
                        .put("data", image.base64),
                ),
            )
        }

        val requestBody = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(
                JSONObject().put(
                    "text",
                    "You are 2VI, a patient $subject study tutor. Help the learner understand, " +
                        "not just copy an answer. Give a concise final answer followed by clear, " +
                        "numbered reasoning steps. Define unfamiliar terms and check arithmetic. " +
                        "If the image or question is unclear, say exactly what needs clarification.",
                ),
            )))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.35)
                    .put("maxOutputTokens", 2048),
            )

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-goog-api-key", apiKey.trim())
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                } catch (_: JSONException) {
                    responseText.take(MAX_ERROR_LENGTH)
                }
                val message = detail.ifBlank { "Gemini returned HTTP ${response.code}." }
                throw IOException(message.take(MAX_ERROR_LENGTH))
            }

            val responseJson = try {
                JSONObject(responseText)
            } catch (exception: JSONException) {
                throw IOException("Gemini returned an unreadable response.", exception)
            }
            val candidates = responseJson.optJSONArray("candidates")
                ?: throw IOException("Gemini returned no answer. Try rephrasing the question.")
            val candidate = candidates.optJSONObject(0)
                ?: throw IOException("Gemini returned no answer. Try again.")
            val answerParts = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Gemini returned no explanation. Try again.")
            val answer = buildString {
                for (index in 0 until answerParts.length()) {
                    val text = answerParts.optJSONObject(index)?.optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text)
                    }
                }
            }.trim()
            if (answer.isBlank()) {
                throw IOException("Gemini returned an empty explanation. Please try again.")
            }
            answer
        }
    }

    companion object {
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_LENGTH = 600

        suspend fun readImage(context: Context, uri: Uri): StudyImage = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bitmap = decodeScaledBitmap(resolver, uri)
            try {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) {
                    throw IOException("The selected photo could not be prepared.")
                }
                StudyImage(
                    base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                    mimeType = "image/jpeg",
                )
            } finally {
                bitmap.recycle()
            }
        }

        private fun decodeScaledBitmap(resolver: ContentResolver, uri: Uri): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: throw IOException("Could not read the selected photo.")
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("The selected file is not a readable image.")
            }

            val options = BitmapFactory.Options()
            while (
                bounds.outWidth / options.inSampleSize > MAX_IMAGE_DIMENSION ||
                bounds.outHeight / options.inSampleSize > MAX_IMAGE_DIMENSION
            ) {
                options.inSampleSize *= 2
            }
            return resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: throw IOException("Could not decode the selected photo.")
        }

        private const val MAX_IMAGE_DIMENSION = 1600
    }
}
