import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

// todo:
//   dns records not a set

suspend fun main() {
    val projectId = System.getenv("PROJECT_ID")
    if  (projectId == null) {
        println("Please set the PROJECT_ID environment variable")
        exitProcess(1)
    }

    val credentials = GoogleCredentials.getApplicationDefault()

    try {
        credentials.refreshIfExpired()
    } catch (e: IOException) {
        println("GCP Credentials Invalid. Either run `gcloud auth application-default login` or set the GOOGLE_APPLICATION_CREDENTIALS env var")
        exitProcess(1)
    }

    GCP(projectId, credentials).use { gcp ->
        val registrations = gcp.listDomains() //.take(1) // temp

        registrations.forEach { gcp.unlockDomain(it.domainName) }

        val registrationsAndCodes = registrations.map { registration ->
            val authCode = gcp.authCode(registration.domainName)
            Pair(registration, authCode)
        }

        registrationsAndCodes.forEach { (registration, authCode) ->
            val redirectConfigs = registration.dnsSettings?.googleDomainsDns?.domainForwards?.map { forward ->
                """
                    redirect {
                        to = "${forward.targetUri}"
                        aliases {
                            "${forward.subdomain}"
                        }
                    }
                """.trimIndent()
            } ?: emptyList()

            val dnsConfigs = registration.dnsSettings?.googleDomainsDns?.records?.map {
                """
                    new {
                        sub = "${it.name}"
                        type = "${it.type}"
                        values {
                            ${it.rrdata.joinToString("\n", "\"", "\"")}
                        }
                    }
                """.trimIndent()
            } ?: emptyList()

            println(
                """
                    ["${registration.domainName}"] {
                        authCode = "$authCode"
                        ${redirectConfigs.joinToString("\n")}
                        records {
                            ${dnsConfigs.joinToString("\n")}
                        }
                    }
                """.trimIndent()
            )
        }
    }

}

class GCP(private val projectId: String, private val credentials: GoogleCredentials) : AutoCloseable {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            )
        }
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(credentials.accessToken.tokenValue, credentials.refreshAccessToken().tokenValue)
                }
            }
        }
    }

    @Serializable
    data class GoogleDomainsDns(val records: List<Record> = emptyList(), val domainForwards: List<DomainForward> = emptyList())

    @Serializable
    data class DNSSettings(val googleDomainsDns: GoogleDomainsDns? = null, val googleDomainsRedirectsDataAvailable: Boolean = false)

    // todo: contacts
    @Serializable
    data class Registration(val domainName: String, val dnsSettings: DNSSettings? = null, val state: String)

    @Serializable
    data class Registrations(val registrations: List<Registration>)

    suspend fun listDomains(): List<Registration> = run {
        val url = "https://domains.googleapis.com/v1/projects/$projectId/locations/global/registrations"
        val resp = client.get(url)

        if (resp.status.value != 200) {
            throw Exception(resp.bodyAsText())
        }
        else {
            resp.body<Registrations>().registrations.filter { it.state == "ACTIVE" }.map { registration ->
                if (registration.dnsSettings != null) {
                    coroutineScope {
                        val dnsRecordsDeferred = async {
                            if (registration.dnsSettings.googleDomainsDns != null) googleDomainsDnsRecords(registration) else emptyList()
                        }
                        val forwardingConfigDeferred = async {
                            if (registration.dnsSettings.googleDomainsRedirectsDataAvailable) googleDomainsForwardingConfig(registration) else emptyList()
                        }
                        registration.copy(
                            dnsSettings = registration.dnsSettings.copy(
                                googleDomainsDns = registration.dnsSettings.googleDomainsDns?.copy(
                                    records = dnsRecordsDeferred.await(),
                                    domainForwards = forwardingConfigDeferred.await()
                                )
                            )
                        )
                    }
                }
                else {
                    registration
                }
            }
        }
    }

    @Serializable
    data class ManagementSettings(val transferLockState: String)

    @Serializable
    data class ConfigureManagementSettings(val managementSettings: ManagementSettings, val updateMask: String = "transferLockState")

    suspend fun unlockDomain(domainName: String): Unit = run {
        val url = "https://domains.googleapis.com/v1/projects/$projectId/locations/global/registrations/$domainName:configureManagementSettings"
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(ConfigureManagementSettings(ManagementSettings("UNLOCKED")))
        }
        if (resp.status.value != 200) {
            throw Exception(resp.bodyAsText())
        }
    }

    @Serializable
    data class AuthorizationCode(val code: String)

    suspend fun authCode(domainName: String): String = run {
        val url = "https://domains.googleapis.com/v1/projects/$projectId/locations/global/registrations/$domainName:retrieveAuthorizationCode"
        val resp = client.get(url)

        if (resp.status.value != 200) {
            throw Exception(resp.bodyAsText())
        }
        else {
            resp.body<AuthorizationCode>().code
        }
    }

    @Serializable
    data class Record(val name: String, val type: String, val ttl: Int, val rrdata: Set<String>)

    @Serializable
    data class Records(val rrset: List<Record>)

    suspend fun googleDomainsDnsRecords(registration: Registration): List<Record> = run {
        val url = "https://domains.googleapis.com/v1/projects/$projectId/locations/global/registrations/${registration.domainName}:retrieveGoogleDomainsDnsRecords"
        val resp = client.get(url)

        if (resp.status.value != 200) {
            throw Exception(resp.bodyAsText())
        }
        else {
            resp.body<Records>().rrset
        }
    }

    @Serializable
    data class DomainForward(val subdomain: String, val targetUri: String, val redirectType: String)

    @Serializable
    data class ForwardingConfig(val domainForwardings: List<DomainForward> = emptyList())

    suspend fun googleDomainsForwardingConfig(registration: Registration): List<DomainForward> = run {
        val url = "https://domains.googleapis.com/v1/projects/$projectId/locations/global/registrations/${registration.domainName}:retrieveGoogleDomainsForwardingConfig"
        val resp = client.get(url)

        if (resp.status.value != 200) {
            throw Exception(resp.bodyAsText())
        }
        else {
            resp.body<ForwardingConfig>().domainForwardings
        }
    }

    override fun close() {
        client.close()
    }

}
