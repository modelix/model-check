/*
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.modelix.modelcheck.api.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

sealed class Checkers {
    object All : Checkers()
    class Single(val id: String) : Checkers()
    class Multiple(val ids: List<String>) : Checkers()
}

sealed class GetJobResultStatus {
    object Node : GetJobResultStatus()
    class New(val result: ModelJobCheckResult) : GetJobResultStatus()
    class FromCache(val result: ModelJobCheckResult) : GetJobResultStatus()
}

@Suppress("unused", "MemberVisibilityCanBePrivate")
class ModelCheckClient {
    private val client: HttpClient
    private val backendAddress: String
    private val allAllAvailableCheckers = lazy { runBlocking { this@ModelCheckClient.getAvailableCheckers() } }
    private class SubscriptionHolder(var connecting: Boolean, val flow: MutableSharedFlow<ModelJobCheckResult>)
    private val subscriptions = ConcurrentHashMap<UUID, SubscriptionHolder>()
    private val resultCache = mutableMapOf<UUID, ModelJobCheckResult>()

    constructor(backendAddress: String) : this(backendAddress, CIO.create())
    constructor(backendAddress: String, engine: HttpClientEngine) {
        this.backendAddress = backendAddress
        client = HttpClient(engine) {
            install(ContentNegotiation) {
                json()
            }
            install(WebSockets)
        }
    }

    suspend fun getAvailableCheckers(): List<Checker> {
        val response: HttpResponse = client.get {
            accept(ContentType.Application.Json)
            url.takeFrom(backendAddress).pathComponents("checkers")
            expectSuccess = true
        }
        val checkersResponse = response.body<GetCheckersResponse>()
        return checkersResponse.checkers
    }

    suspend fun getJobs(): List<ModelCheckJob> {
        val response: HttpResponse = client.get {
            accept(ContentType.Application.Json)
            url.takeFrom(backendAddress).pathComponents("jobs")
            expectSuccess = true
        }
        val allJobsResponse = response.body<AllJobsResponse>()
        return allJobsResponse.jobs
    }

    suspend fun createModelCheckJob(
        nodeRef: NodeReference,
        checkers: Checkers = Checkers.All,
        continuous: Boolean = false
    ): UUID? {
        val parameters = Parameters.build {
            when (checkers) {
                Checkers.All -> appendAll("checkers", allAllAvailableCheckers.value.map { it.id })
                is Checkers.Single -> append("checkers", checkers.id)
                is Checkers.Multiple -> appendAll("checkers", checkers.ids)
            }
            append("nodeRef", nodeRef.primary)
            nodeRef.alternates.forEach {
                append("nodeRef", it)
            }
            append("continuous", continuous.toString())
        }
        val response: HttpResponse = client.submitForm(
            url = "",
            formParameters = parameters,
            encodeInQuery = false
        ) {
            accept(ContentType.Application.Json)
        }
        return if (response.status.isSuccess()) {
            val createJobResponse = response.body<CreateJobResponse>()
            createJobResponse.jobId
        } else {
            null
        }
    }

    suspend fun cancelCheckingJob(id: UUID) {
        @Suppress("UNUSED_VARIABLE")
        val ignored: HttpResponse = client.delete {
            accept(ContentType.Application.Json)
            url.takeFrom(backendAddress).pathComponents("jobs", id.toString())
            expectSuccess = true
        }
    }

    suspend fun getJobStatus(id: UUID): CheckingJobStatus {
        val response: HttpResponse = client.get {
            accept(ContentType.Application.Json)
            url.takeFrom(backendAddress).pathComponents("jobs", id.toString(), "status")
            expectSuccess = true
        }
        val allJobsResponse = response.body<JobStatusResponse>()
        return allJobsResponse.status
    }

    /**
     * Gets the latest result of the checking job. Might return null if the checking job has not produced a result
     * yet. Use [getJobStatus] to check the status of a job.
     * @param id id of a checking job
     * @return the latest result of the checking job
     */
    suspend fun getJobResult(id: UUID): GetJobResultStatus {
        val response: HttpResponse = client.get {
            accept(ContentType.Application.Json)

            if(resultCache.containsKey(id)) {
                header(HttpHeaders.IfNoneMatch, resultCache[id]?.id)
            }

            url.takeFrom(backendAddress).pathComponents("jobs", id.toString(), "result")
            expectSuccess = true
        }
        if (response.status == HttpStatusCode.NotFound) {
            return GetJobResultStatus.Node
        }
        if(response.status == HttpStatusCode.NotModified) {
            return GetJobResultStatus.FromCache(resultCache[id]!!)
        }
        val allJobsResponse = response.body<JobResultResponse>()
        resultCache[id] = allJobsResponse.result
        return GetJobResultStatus.New(allJobsResponse.result)
    }

    /**
     * Gets the all available results for a checking job. The results might be empty if the job hasn't produced any, yet.
     * Use [getJobStatus] to check the status of a job.
     */
    suspend fun getJobResults(id: UUID): List<ModelJobCheckResult> {
        val response: HttpResponse = client.get {
            accept(ContentType.Application.Json)
            url.takeFrom(backendAddress).pathComponents("jobs", id.toString(), "results")
            expectSuccess = true
        }
        if (response.status == HttpStatusCode.NotFound) {
            return emptyList()
        }
        val allJobsResponse = response.body<JobAllResultsResponse>()
        return allJobsResponse.results
    }

    /**
     * Allows subscription to a specific checking job. The flow will emit a value each time the checking job completes.
     * If the job has already completes at least once new subscribers will get the latest result on subscription.
     *
     * Multiple subscriptions to the same job will share the same websocket connection.
     *
     * This method is thread safe.
     */
    suspend fun subscribeToJob(id: UUID): Flow<ModelJobCheckResult> {
        val holder = subscriptions.getOrPut(id) {
            SubscriptionHolder(false,  MutableSharedFlow(1, 0, BufferOverflow.DROP_OLDEST))
        }
        synchronized(holder) {
            if(holder.connecting) {
                return holder.flow
            }
            holder.connecting = true
        }
        client.webSocket(request = {
            url.takeFrom(backendAddress).pathComponents("jobs", id.toString(), "result")
        }) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val modelJobCheckResult = Json.decodeFromString<ModelJobCheckResult>(frame.readText())
                        holder.flow.emit(modelJobCheckResult)

                    }
                    else -> {// TODO log}
                    }
                }
            }
        }
        // Buffered flow for every client to prevent back pressure to the producer (websocket client).
        // If a consumer consumes slower than we emit events the flow will drop the older events.
        // This is ok because we aren't an ordered stream of events but every event is the complete model
        // check result. If a consumer wants to make sure they do not drop events they can introduce their own
        // buffer.
        return holder.flow.buffer(onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
}