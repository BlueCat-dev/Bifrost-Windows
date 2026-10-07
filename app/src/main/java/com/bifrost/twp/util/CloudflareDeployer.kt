package com.bifrost.twp.util

import com.bifrost.twp.model.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object CloudflareDeployer {

    private const val WORKER_SOURCE_URL =
        "https://raw.githubusercontent.com/Qorvhex/TWP/refs/heads/main/worker.js"

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun deployWorker(
        apiToken: String,
        secretKey: String?,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<ProxyConfig> = withContext(Dispatchers.IO) {
        val cleanToken = apiToken.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("کلید API نمی‌تواند خالی باشد."))
        }

        try {
            // Step 1: 15% - Verify Token & Get Account ID
            onProgress(15, "در حال بررسی اعتبار کلید و دریافت اطلاعات حساب...")
            val accountId = getAccountId(cleanToken)

            // Step 2: 35% - Fetch workers.dev subdomain
            onProgress(35, "در حال دریافت ساب‌دامین اختصاصی workers.dev...")
            val subdomain = getAccountSubdomain(cleanToken, accountId)

            // Step 3: 50% - Download latest worker.js
            onProgress(50, "در حال دریافت آخرین نسخه سورس ورکر از گیت‌هاب...")
            val workerCode = fetchLatestWorkerCode()

            // Step 4: 70% - Upload and deploy Worker script
            val randomId = Random.nextInt(100000, 999999)
            val scriptName = "bifrost-$randomId"
            onProgress(70, "در حال آپلود و ایجاد اسکریپت در کلادفلر...")
            uploadWorkerScript(cleanToken, accountId, scriptName, workerCode, secretKey?.trim())

            // Step 5: 85% - Enable workers.dev subdomain route for script
            onProgress(85, "در حال فعال‌سازی دسترسی دامنه workers.dev برای ورکر...")
            enableScriptSubdomain(cleanToken, accountId, scriptName)

            // Step 6: 100% - Build ProxyConfig
            val workerHost = "$scriptName.$subdomain.workers.dev"
            val config = ProxyConfig(
                id = UUID.randomUUID().toString(),
                name = "CF-$randomId",
                workerHost = workerHost,
                secret = secretKey?.trim()?.takeIf { it.isNotBlank() },
                cleanIp = null,
                port = 443,
                isActive = true
            )

            onProgress(100, "پروکسی با موفقیت ساخته و آماده اتصال شد!")
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getAccountId(token: String): String {
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body)
                throw IllegalStateException("خطا در ارتباط با کلادفلر با کد ${response.code}: $errorMsg")
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                val errorMsg = extractErrorMessage(body)
                throw IllegalStateException("کلید وارد شده نامعتبر است: $errorMsg")
            }

            val result = json.optJSONArray("result")
            if (result == null || result.length() == 0) {
                throw IllegalStateException("هیچ حسابی مرتبط با این کلید یافت نشد. لطفاً از وجود حساب در کلادفلر مطمئن شوید.")
            }

            return result.getJSONObject(0).getString("id")
        }
    }

    private fun getAccountSubdomain(token: String, accountId: String): String {
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) {
                throw IllegalStateException("ساب‌دامین workers.dev هنوز برای اکانت شما تنظیم نشده است. لطفاً یک‌بار وارد داشبورد کلادفلر شده و در بخش Workers & Pages یک ساب‌دامین برای خود تعیین کنید.")
            }
            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body)
                throw IllegalStateException("خطا در دریافت ساب‌دامین: $errorMsg")
            }

            val json = JSONObject(body)
            val result = json.optJSONObject("result")
            val subdomain = result?.optString("subdomain")
            if (subdomain.isNullOrBlank()) {
                throw IllegalStateException("ساب‌دامین workers.dev یافت نشد. لطفاً در بخش Workers کلادفلر ساب‌دامین حساب خود را مشخص کنید.")
            }
            return subdomain
        }
    }

    private fun fetchLatestWorkerCode(): String {
        val request = Request.Builder()
            .url(WORKER_SOURCE_URL)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("خطا در دریافت کد ورکر از گیت‌هاب با کد ${response.code}")
            }
            val code = response.body?.string().orEmpty()
            if (code.isBlank()) {
                throw IllegalStateException("سورس دریافتی از گیت‌هاب خالی است.")
            }
            return code
        }
    }

    private fun uploadWorkerScript(
        token: String,
        accountId: String,
        scriptName: String,
        workerCode: String,
        secretKey: String?
    ) {
        val metadataObj = JSONObject().apply {
            put("main_module", "worker.js")
            put("compatibility_date", "2024-09-01")
            put("compatibility_flags", JSONArray().apply { put("nodejs_compat") })

            val bindingsArray = JSONArray()
            if (!secretKey.isNullOrBlank()) {
                bindingsArray.put(JSONObject().apply {
                    put("type", "plain_text")
                    put("name", "SECRET")
                    put("text", secretKey)
                })
            }
            put("bindings", bindingsArray)
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "metadata",
                null,
                metadataObj.toString().toRequestBody("application/json".toMediaTypeOrNull())
            )
            .addFormDataPart(
                "worker.js",
                "worker.js",
                workerCode.toRequestBody("application/javascript+module".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$scriptName")
            .header("Authorization", "Bearer $token")
            .put(multipartBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body)
                throw IllegalStateException("خطا در آپلود اسکریپت ورکر: $errorMsg")
            }
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                val errorMsg = extractErrorMessage(body)
                throw IllegalStateException("آپلود ورکر ناموفق بود: $errorMsg")
            }
        }
    }

    private fun enableScriptSubdomain(token: String, accountId: String, scriptName: String) {
        val jsonPayload = JSONObject().apply {
            put("enabled", true)
        }

        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$scriptName/subdomain")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body)
                throw IllegalStateException("خطا در فعال‌سازی ساب‌دامین ورکر: $errorMsg")
            }
        }
    }

    private fun extractErrorMessage(responseJson: String): String {
        return try {
            val json = JSONObject(responseJson)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val firstErr = errors.getJSONObject(0)
                firstErr.optString("message", "خطای ناشناخته")
            } else {
                json.optString("message", "خطای ناشناخته از سمت سرور")
            }
        } catch (_: Exception) {
            if (responseJson.isNotBlank()) responseJson.take(120) else "خطای نامشخص"
        }
    }
}
