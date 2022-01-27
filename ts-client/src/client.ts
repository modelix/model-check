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

export enum Severity {
    Info = "Info", Warning = "Warning", Error = "Error"
}

export enum CheckingJobType {
    /**
     * A checking job that is executed once.
     */
    OneOff = "OneOff",

    /**
     * A checking job that is created and will be executed again when changes to the model require it to be re-executed.
     * Job of these type need explicit cancellation by the client that created them.
     */
    Continuous = "Continuous"
}

/**
 * The job was created and execution has not started yet.
 */
export interface Created {
    type: "created"
}

/**
 * The job is currently running and has not completed. Results of previous executions of the job might available.
 */
export interface Running {
    type: "running"
}

/**
 * The job was canceled by a client. This status is only set by explicit cancellation of a client. The manager might
 * cancel running jobs a model has changed while a checking job was running and restarts the job internally. These
 * internal cancellations aren't reflected with this state.
 */
export interface Canceled {
    type: "canceled"
}

/**
 * The checking job has complete. For job with type [CheckingJobType.OneOff] this is the final state.
 * Jobs with type [CheckingJobType.Continuous] will get executed again when changes require it and transition into
 * the state [Running] again.
 */
export interface NodeDeleted {
    type: "nodeDeleted"
}

/**
 * The node reference of the job could not get resolved. The manager assumes that in that case the node was deleted.
 * For checking jobs with type [CheckingJobType.OneOff] this can happen if the node was deleted from the model in the
 * time it takes for the checking job to start execution after the job was created.
 * For checking jobs with type [CheckingJobType.Continuous] this will happen if the node no longer exists. In this
 * case previous results of the job are still available if it ever completed.
 */
export interface Completed {
    type: "completed"
}


/**
 * An error occurred during the checking jobs execution. See the inner exception for more details.
 * Checking jobs with type [CheckingJobType.Continuous] will try to re-execute the next time a change triggers their
 * execution. For type [CheckingJobType.OneOff] this is a final state and job will not retry execution.
 */
export interface Error {
    type: "error"
    message: string
}

export type CheckingJobStatus = Created | Running | Canceled | NodeDeleted | Completed | Error

/**
 * The model check message is associated with a property. The [propertyName] field contains the name of the property.
 * Editors can use this information to highlight or show the error message on specific parts of the editor.
 */

export interface Property {
    type: "property"
    propertyName: string
}

/**
 * The model check message is associated with a reference or containment link. The [featureName] field contain the
 * name of the concept feature. No distinction between references and containment is available. The MPS meta model
 * does not allow for a reference and containment role to share the same name hence the name is enough to identify
 * the feature uniquely. Editors can use this information to highlight or show the error message on specific parts
 * of the editor.
 */
export interface ReferenceOrContainment {
    type: "referenceOrContainment"
    featureName: string
}

/**
 * The model check message is not associated with any feature of the node.
 */
export interface Node {
    type: "node"
}

export type CheckMessageTarget = Property | ReferenceOrContainment | Node

export interface ModelCheckMessageResponse {
    /**
     * Reference to the affected node. This is a string serialised MPS SNodeReference/SNodePointer. The client needs to
     * do the mapping to an node of their side manually.
     */
    affected: NodeRef;

    /**
     * The feature of the node associated with this message.
     */
    target: CheckMessageTarget;

    /**
     * Sources in the MPS language definition that produced this message. A message can have multiple sources. The list
     * contains string serialised MPS SNodeReference/SNodePointer instances.
     */
    sources: string[];
    severity: Severity;
    text: string;
}

/**
 * Reference to a node. The reference is represented as a string. The string must allow the client to choose the
 * appropriate parser for the node reference. e.g. Serialised node references from MPS must have the prefix `mps:`.
 * A client can probe the string and choose the parse at runtime.
 *
 * The [NodeReference.primary] field must always contain
 * a valid references to the node. The server and client can choose the primary type of reference freely. An MPS based
 * server can choose to use the MPS SNodeReference as their primary id. A client that directly connects to the modelix
 * model server might choose to use a PNodReference as their primary id.
 *
 * [NodeReference.alternates] can optionally contain alternate ways of identifying the same node. An MPS based server with
 * a model stored in the modelix model server can include references to the PNode stored in the server. Implementations
 * that consume the node reference are free to choose the way how they refer to the node. They might not use the primary
 * reference for performance reasons if an alternate reference is cheaper for them to resolve.
 * Implementations that create a [NodeReference] must make sure that logically all alternate reference point to the same
 * "thing" as the primary.
 */
export interface NodeRef {
    primary: string;
    alternates: string[];
}

/**
 *  A checker available in the model checking server. [Checker.name] might be empty or duplicated, its main purpose is
 *  debugging. [Checker.id] is guaranteed to be unique for each checker.
 */
export interface Checker {
    id: string;
    name: string;
}

export interface GetCheckersResponse {
    checkers: Checker[]
}

export interface ModelCheckResultResponse {
    id: string;
    completed: Date;
    messages: ModelCheckMessageResponse[];
}

export interface CreateJobResponse {
    jobId: string
}

export interface JobStatusResponse {
    jobId: string;
    status: CheckingJobStatus
}

export interface JobAllResultsResponse {
    jobId: string;
    results: ModelCheckResultResponse[];
}

export interface JobResultResponse {
    jobId: string;
    result: ModelCheckResultResponse;
}

export interface CheckingJob {
    id: string;
    target: NodeRef;
    type: CheckingJobType;
    status: CheckingJobStatus;
}

export interface AllJobsResponse {
    jobs: CheckingJob[]
}

// noinspection JSUnusedGlobalSymbols
export class Client {
    private readonly backend: string;
    private subscribers = new Map()
    private jobResultCache = new Map()

    constructor(backend: string) {
        this.backend = backend
    }

    private static async doFetch(url: string, method: string = "GET", additionalHeaders?: Record<string, string>, body?: URLSearchParams): Promise<Response> {
        const headers = new Headers({'Accept': 'application/json'})
        if (additionalHeaders != undefined) {
            for (const additionalHeadersKey in additionalHeaders) {
                headers.set(additionalHeadersKey, additionalHeaders[additionalHeadersKey])
            }
        }
        return fetch(url, {
            method: method,
            credentials: "include",
            body: body,
            headers: {}
        })
    }

    /**
     * Creates a new checking job within the server. The promise returns the id of the checking job which other methods
     * like getJobResult require to retrieve the results.
     *
     * Ids of the checkers are available via getAvailableCheckers.
     *
     * @param nodeRef reference to the node to check. The server needs to be able to deserialize the reference.
     * @param continuous if true the checking job will continuously produce new results on each model change.
     * @param checkers ids for checkers to run. Server implementation should default to all checkers if omitted.
     */
    public async createCheckingJob(nodeRef: string, continuous: boolean, checkers?: string[]): Promise<string> {
        let params = new URLSearchParams();
        params.append("nodeRef", nodeRef)
        params.append("continuous", continuous ? "true" : "false")
        for (const checker in checkers) {
            params.append("checker", checker)
        }
        const r = await Client.doFetch(this.backend.toString() + "/jobs", "POST", undefined, params)
        let data: CreateJobResponse = await r.json()
        if (r.ok) {
            return data.jobId
        } else {
            return Promise.reject()
        }
    }

    public async getAvailableCheckers(): Promise<Checker[]> {
        const r = await Client.doFetch(this.backend + "/checkers")
        const status: GetCheckersResponse = await r.json()
        if (r.ok) {
            return status.checkers
        } else {
            return Promise.reject()
        }
    }

    public async getAllJobs(): Promise<CheckingJob[]> {
        const r = await Client.doFetch(this.backend + "/jobs")
        const status: AllJobsResponse = await r.json()
        if (r.ok) {
            return status.jobs
        } else {
            return Promise.reject()
        }
    }

    public async getJobStatus(jobId: string): Promise<CheckingJobStatus> {
        const r = await Client.doFetch(this.backend + "/jobs/" + jobId + "/status")
        const status: JobStatusResponse = await r.json()
        if (r.ok) {
            return status.status
        } else {
            return Promise.reject()
        }
    }

    /**
     * Gets the latest result for a checking job. The boolean indicated if the value is from cache or a new value
     * from the server. If the boolean is true it is a new result returned by the server, if false it is from the
     * cache.
     * @param jobId
     */
    public async getJobResult(jobId: string): Promise<[ModelCheckResultResponse, boolean]> {
        const prevResult: ModelCheckResultResponse = this.jobResultCache.get(jobId)
        let etagHeaders = undefined
        if (prevResult != null) {
            etagHeaders = {"If-None-Match": prevResult.id}
        }

        const r = await Client.doFetch(this.backend + "/jobs/" + jobId + "/result", "GET", etagHeaders)

        if (r.status == 304) {
            return [prevResult, false]
        }

        if (r.ok) {
            const status: JobResultResponse = await r.json()
            this.jobResultCache.set(jobId, status.result)
            return [status.result, true]
        } else {
            return Promise.reject()
        }
    }

    public async getJobAllResults(jobId: string): Promise<ModelCheckResultResponse[]> {
        const r = await Client.doFetch(this.backend + "/jobs/" + jobId + "/results")
        const status: JobAllResultsResponse = await r.json()
        if (r.ok) {
            return status.results
        } else {
            return Promise.reject()
        }
    }

    public async cancelCheckingJob(jobId: string): Promise<string> {
        const r = await Client.doFetch(this.backend + "/jobs/" + jobId, "DELETE")
        if (r.ok) {
            return jobId
        } else {
            return Promise.reject()
        }
    }

    public subscribeToJob(jobId: string, onNewResult: (event: ModelCheckResultResponse) => void, onUnsubscribe?: () => void) {

        if (this.subscribers.has(jobId)) {
            let websocket = this.subscribers.get(jobId);
            websocket.addEventListener("onmessage", (e: MessageEvent) => {
                onNewResult(JSON.parse(e.data))
            })
            if (onUnsubscribe != undefined) {
                websocket.addEventListener("onerror", () => {
                    onUnsubscribe()
                })
                websocket.addEventListener("onclose", () => {
                    onUnsubscribe()
                })
            }

            return
        }

        let url = new URL(this.backend + "/jobs/" + jobId + "/results");
        url.protocol = url.protocol == "https://" ? "wss://" : "ws://"
        let webSocket = new WebSocket(url);
        webSocket.onmessage = (e) => {
            console.log(e)
            onNewResult(JSON.parse(e.data))
        }
        webSocket.onopen = (e) => {
            this.subscribers.set(jobId, webSocket)
        }
        webSocket.onerror = (e) => {
            console.log(e)
            this.subscribers.delete(jobId)
        }
        webSocket.onclose = ev => {
            this.subscribers.delete(jobId)
        }
    }
}