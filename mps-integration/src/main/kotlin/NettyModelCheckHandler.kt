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

package org.modelix.modelcheck.mpsintegration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.multipart.Attribute
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.handler.stream.ChunkedStream
import io.netty.util.CharsetUtil
import jetbrains.mps.smodel.SNodePointer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.apache.http.entity.ContentType
import org.jetbrains.io.*
import org.jetbrains.mps.openapi.module.SRepository
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.modelix.modelcheck.api.*
import org.modelix.modelcheck.manager.MPSModelCheckManager
import java.io.ByteArrayInputStream
import java.lang.IllegalArgumentException
import java.util.*


class NettyModelCheckHandler {
    companion object {
        private lateinit var modelCheckManager: ModelCheckManager
        private var initialized = false

        private const val NODEREF_PARAM_NAME = "nodeRef"
        private const val CHECKER_PARAM_NAME = "checker"
        private const val CONTINUOUS_PARAM_NAME = "continuous"
        private val jobRegex =
            Regex("""\S*/jobs/([0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12})/?""")
        private val jobResultRegex =
            Regex("""\S*/jobs/([0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12})/result/?""")
        private val jobResultsRegex =
            Regex("""\S*/jobs/([0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12})/results/?""")
        private val jobStatusRegex =
            Regex("""\S*/jobs/([0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12})/status/?""")


        fun checkInit() {
            require(initialized) { "Initialise NettyModelCheckHandler first!" }
        }

        @JvmStatic
        fun isInitialised(): Boolean {
            return initialized
        }

        @JvmStatic
        fun init(repo: SRepository) {
            this.modelCheckManager = MPSModelCheckManager(repo)
            initialized = true
        }

        @JvmStatic
        fun handleRequest(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {
            checkInit()
            val path = queryStringDecoder.path()
            val method = request.method()
            when {
                path.endsWith("/checkers") -> {
                    when (method) {
                        HttpMethod.GET -> {
                            responseJsonOrHtml(
                                GetCheckersResponse(modelCheckManager.getAvailableCheckers()),
                                null,
                                request,
                                channel
                            ) {
                                head { title = "Available Checkers" }
                                body {
                                    h2 { +"Available Checkers" }
                                    ul {
                                        modelCheckManager.getAvailableCheckers().forEach {
                                            li { +"${it.name} - ${it.id}" }
                                        }
                                    }
                                }
                            }
                        }
                        else -> unsupportedMethod(listOf(HttpMethod.GET), request, channel)
                    }
                }
                path.endsWith("/jobs") && method == HttpMethod.POST -> createNewJob(
                    request,
                    queryStringDecoder,
                    channel
                )
                // Order matters here because the result regex would match also resultS.
                path.contains(jobResultsRegex) -> {
                    when (method) {
                        HttpMethod.GET -> {
                            if ("WebSocket".equals(
                                    request.headers().getAsString(HttpHeaderNames.UPGRADE),
                                    ignoreCase = true
                                )
                            ) {
                                subscribeToResults(request, queryStringDecoder, channel)
                            } else {
                                getAllResults(request, queryStringDecoder, channel)
                            }
                        }
                        else -> unsupportedMethod(listOf(HttpMethod.GET), request, channel)
                    }
                }
                path.contains(jobResultRegex) -> {
                    when (method) {
                        HttpMethod.GET -> getLatestResult(request, queryStringDecoder, channel)
                        else -> unsupportedMethod(listOf(HttpMethod.GET), request, channel)
                    }
                }

                path.contains(jobStatusRegex) -> {
                    when (method) {
                        HttpMethod.GET -> getJobStatus(request, queryStringDecoder, channel)
                        else -> unsupportedMethod(listOf(HttpMethod.GET), request, channel)
                    }
                }
                path.contains(jobRegex) -> {
                    when (method) {
                        HttpMethod.DELETE -> deleteJob(request, queryStringDecoder, channel)
                        else -> unsupportedMethod(listOf(HttpMethod.DELETE), request, channel)
                    }
                }
                else -> {
                    val allJobs = AllJobsResponse(modelCheckManager.getAllJobs())
                    responseJsonOrHtml(allJobs, null, request, channel) {
                        htmlJobOverview(path, allJobs)
                    }
                }
            }
        }

        private fun HTML.htmlJobOverview(
            path: String,
            allJobs: AllJobsResponse
        ) {
            head { title = "Model checking Jobs" }
            body {
                h1 {
                    +"Model Checking Jobs"
                }
                if (allJobs.jobs.isEmpty()) {
                    p { +"No mode checking jobs in this instance." }
                } else {
                    ul {
                        allJobs.jobs.forEach {
                            li {
                                +"${it.id} - ${it.type} - ${it.status}"
                                a {
                                    href = "$path/jobs/${it.id}/result"
                                    +"Latest Result"
                                }
                                a {
                                    href = "$path/jobs/${it.id}/results"
                                    +"All Results"
                                }
                            }
                        }
                    }
                }
                form {
                    this.method = FormMethod.post
                    action = path + "/jobs"

                    h3 { +"Create new checking job:" }
                    label {
                        htmlFor = "nodeRef"
                        +"Target"
                    }
                    input {
                        name = NODEREF_PARAM_NAME
                        id = "nodeRef"
                        value = "mps:"
                    }
                    br
                    div {
                        label {
                            htmlFor = "true"
                            +"Continuous Checking Job"
                        }
                        radioInput {
                            id = "true"
                            name = CONTINUOUS_PARAM_NAME
                            value = "true"
                        }
                        label {
                            htmlFor = "true"
                            +"On off Checking Job"
                        }
                        radioInput {
                            id = "false"
                            name = CONTINUOUS_PARAM_NAME
                            value = "false"
                            checked = true
                        }
                    }
                    div {
                        modelCheckManager.getAvailableCheckers().forEach {
                            checkBoxInput {
                                name = "checker"
                                value = it.id
                                id = it.id
                                checked = true
                                +it.name
                            }
                            br
                        }
                    }
                    br
                    submitInput { }
                }
            }
        }


        private fun QueryStringDecoder.extractUUID(regex: Regex): UUID? {
            val uuidString = regex.matchEntire(this.path())?.groupValues?.get(1) ?: return null

            return try {
                UUID.fromString(uuidString)
            } catch (e: Exception) {
                return null
            }
        }

        private fun getJobStatus(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {

            val jobId = queryStringDecoder.extractUUID(jobStatusRegex) ?: return respondBadRequest(
                "invalid uuid",
                request,
                channel
            )

            try {
                val jobStatus = modelCheckManager.getJobStatus(jobId)
                responseJsonOrHtml(JobStatusResponse(jobId, jobStatus), null, request, channel) {
                    head { title = "Job Status - $jobId" }
                    body {
                        h2 { +"Job Status - $jobId" }
                        p {
                            +"Job Status is"
                            strong {
                                +"$jobStatus"
                            }
                        }
                    }
                }
            } catch (ia: IllegalArgumentException) {
                respond404(request, channel)
            }
        }

        private fun getAllResults(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {
            val jobId = queryStringDecoder.extractUUID(jobResultsRegex) ?: return respondBadRequest(
                "invalid uuid",
                request,
                channel
            )
            try {
                val jobStatus = modelCheckManager.getJobResults(jobId)
                responseJsonOrHtml(
                    JobAllResultsResponse(jobId, jobStatus.map { it.toResponse() }),
                    null,
                    request,
                    channel
                ) {
                    head { title = "Job Results - $jobId" }
                    body {
                        h2 { +"Job Results - $jobId" }
                        jobStatus.forEach {
                            div {
                                p {
                                    +"Id:"
                                    strong {
                                        it.id
                                    }
                                }
                                p {
                                    +"Completes at:"
                                    strong {
                                        +"${it.completed}"
                                    }
                                }
                                p { +"Messages:" }
                                ul {
                                    it.messages.forEach {
                                        li { +"${it.severity}: ${it.text} -> ${it.target}" }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (ia: IllegalArgumentException) {
                respond404(request, channel)
            }
        }

        private fun subscribeToResults(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {

            val jobId = queryStringDecoder.extractUUID(jobResultsRegex) ?: return respondBadRequest(
                "invalid uuid",
                request,
                channel
            )

            //TODO: this will not work behind TLS/reverse proxy
            val factory = WebSocketServerHandshakerFactory(
                "ws://" + request.headers().getAsString(HttpHeaderNames.HOST) + queryStringDecoder.path(),
                null,
                false,
                NettyUtil.MAX_CONTENT_LENGTH
            )

            val handshaker = factory.newHandshaker(request)
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel)
                return
            }

            if (!channel.isOpen) {
                return
            }

            val client = WebSocketClient(channel)

            handshaker.handshake(channel, request).addListener(ChannelFutureListener {
                if (it.isSuccess) {
                    GlobalScope.launch {
                        modelCheckManager.subscribeToJob(jobId).collect {
                            client.send(Json.encodeToString(it.toResponse()))
                        }
                    }
                }
            })
            return
        }

        private fun getLatestResult(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {
            val jobId = queryStringDecoder.extractUUID(jobResultRegex) ?: return respondBadRequest(
                "invalid uuid",
                request,
                channel
            )
            try {
                val jobStatus = modelCheckManager.getJobResult(jobId) ?: return respond404(request, channel)
                val etag = request.headers().get(HttpHeaderNames.ETAG)
                if(etag != null && etag == jobStatus.id) {
                    respond304(request, channel)
                }
                responseJsonOrHtml(JobResultResponse(jobId, jobStatus.toResponse()), jobStatus.id, request, channel) {
                    head { title = "Job Result - $jobId" }
                    body {
                        h2 { +"Job Result - $jobId" }
                        div {
                            p {
                                +"Id:"
                                strong {
                                    +jobStatus.id
                                }
                            }
                            p {
                                +"Completes at:"
                                strong {
                                    +"${jobStatus.completed}"
                                }
                            }
                            p { +"Messages:" }
                            ul {
                                jobStatus.messages.forEach {
                                    li { +"${it.severity}: ${it.text} -> ${it.target}" }
                                }
                            }
                        }
                    }
                }
            } catch (ia: IllegalArgumentException) {
                respond404(request, channel)
            }
        }


        private fun deleteJob(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {

            val path = if (queryStringDecoder.path().endsWith("/")) {
                queryStringDecoder.path().substringBeforeLast("/")
            } else {
                queryStringDecoder.path()
            }

            val uuidString = path.substringAfterLast("/")

            val jobId = try {
                UUID.fromString(uuidString)
            } catch (e: Exception) {
                return respondBadRequest("invalid uuid", request, channel)
            }

            try {
                modelCheckManager.cancelCheckingJob(jobId)
                respondOk(request, channel)
            } catch (ia: IllegalArgumentException) {
                respond404(request, channel)
            }
        }

        private fun createNewJob(request: HttpRequest, queryStringDecoder: QueryStringDecoder, channel: Channel) {

            val decoder = HttpPostRequestDecoder(request)
            val nodeRefData = decoder.getBodyHttpData(NODEREF_PARAM_NAME) as? Attribute

            val nodeRef = if (nodeRefData != null) {
                listOf(nodeRefData.value)
            } else {
                queryStringDecoder.parameters()[NODEREF_PARAM_NAME]
                    ?: return respondBadRequest(
                        "Missing $NODEREF_PARAM_NAME parameter",
                        request = request,
                        channel = channel
                    )
            }

            val continuousData = decoder.getBodyHttpData(CONTINUOUS_PARAM_NAME) as? Attribute

            val continuous = continuousData?.value?.toBoolean()
                ?: (queryStringDecoder.parameters()[CONTINUOUS_PARAM_NAME]?.firstOrNull()?.toBoolean() ?: false)

            val checkerData = decoder.getBodyHttpDatas(CHECKER_PARAM_NAME) as? List<Attribute>

            val checkers = checkerData?.map { it.value } ?: queryStringDecoder.parameters()[CHECKER_PARAM_NAME]

            val pointer = NodeReference(primary = nodeRef.first(), alternates = nodeRef.drop(1))

            val jobId = modelCheckManager.createModelCheckJob(
                pointer,
                checkers?.run { Checkers.Multiple(this) } ?: Checkers.All,
                continuous)
                ?: return respondBadRequest("could not create checking job", request, channel)

            responseJsonOrHtml(CreateJobResponse(jobId), request = request, channel = channel) {
                head { title = "Job created" }
                body {
                    p {
                        +"Job with id $jobId created!"
                    }
                }
            }
        }

        private inline fun <reified T>responseJsonOrHtml(
            data: T,
            etag: String? = null,
            request: HttpRequest,
            channel: Channel,
            noinline block: HTML.() -> Unit
        ) {
            val accept = request.headers().getAsString(HttpHeaderNames.ACCEPT)

            if (accept != null) {
                val acceptedValues = accept.split(",").reversed()
                val htmlIndex = acceptedValues.indexOf("text/html")
                val jsonIndex = acceptedValues.indexOf("application/json")

                if (htmlIndex > jsonIndex) {
                    respondHtml(request, channel, etag, block)
                } else {
                    respondJson(data, etag, request, channel)
                }
            } else {
                respondJson(data, etag, request, channel)
            }
        }

        private inline fun <reified T>respondJson(data: T, etag: String?, request: HttpRequest, channel: Channel) {
            val response: FullHttpResponse = response(
                ContentType.APPLICATION_JSON.mimeType,
                Unpooled.copiedBuffer(Json.encodeToString(data), CharsetUtil.UTF_8)
            )
            if (etag != null) {
                response.headers().set(HttpHeaderNames.ETAG, etag)
            }
            response.status = HttpResponseStatus.OK
            response.send(channel, request)
        }

        private fun respondOk(request: HttpRequest, channel: Channel) {
            val response = response(ContentType.TEXT_PLAIN.mimeType, Unpooled.copiedBuffer("", CharsetUtil.UTF_8))
            response.status = HttpResponseStatus.OK
            response.send(channel, request)
        }

        private fun respond404(request: HttpRequest, channel: Channel) {
            val response =
                response(ContentType.TEXT_PLAIN.mimeType, Unpooled.copiedBuffer("not found", CharsetUtil.UTF_8))
            response.status = HttpResponseStatus.NOT_FOUND
            response.send(channel, request)
        }

        private fun respond304(request: HttpRequest, channel: Channel) {
            val response =
                HttpResponseStatus.NOT_MODIFIED.response(request)
            response.send(channel, request)
        }

        private fun respondHtml(
            request: HttpRequest,
            channel: Channel,
            etag: String? = null,
            block: HTML.() -> Unit
        ) {
            val response = DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK
            )
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, ContentType.TEXT_HTML)
            response.addCommonHeaders()
            if (etag != null) {
                response.headers().set(HttpHeaderNames.ETAG, etag)
            }

            val content = createHTML().html(block = block).toByteArray()

            if (request.method() != HttpMethod.HEAD) {
                HttpUtil.setContentLength(response, content.size.toLong())
            }

            channel.write(response)

            if (request.method() != HttpMethod.HEAD) {
                val stream = ByteArrayInputStream(content)
                channel.write(ChunkedStream(stream))
                stream.close()
            }
            val keepAlive = response.addKeepAliveIfNeeded(request)

            val future = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE)
            }
        }

        private fun respondBadRequest(
            msg: String,
            request: HttpRequest,
            channel: Channel
        ) {
            val response = response(ContentType.TEXT_PLAIN.mimeType, Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8))
            response.status = HttpResponseStatus.BAD_REQUEST
            response.send(channel, request)
        }

        private fun unsupportedMethod(
            supportedMethods: List<HttpMethod>,
            request: HttpRequest,
            channel: Channel
        ) {
            val response = response(
                ContentType.TEXT_PLAIN.mimeType,
                Unpooled.copiedBuffer("unsupported method", CharsetUtil.UTF_8)
            )
            response.status = HttpResponseStatus.METHOD_NOT_ALLOWED
            response.headers().add(HttpHeaderNames.ALLOW, supportedMethods)
            response.send(channel, request)
        }
    }
}


