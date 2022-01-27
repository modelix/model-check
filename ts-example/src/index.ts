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

import {CheckingJobType, Client} from "ts-client";

let client = new Client("http://localhost:63320/modelcheck")

function getRoot(): HTMLDivElement {
    return document.getElementById("root") as HTMLDivElement
}

function allResults(jobId: string) {

}

function subscribeToJob(jobId: string) {
    client.subscribeToJob(jobId, event => {
        let root = getRoot();
        console.log(event)
        console.log(event.messages)

        let ul = document.createElement("ul");
        if(event.messages != null) {
            let entries = event.messages.map(message => {
                let li = document.createElement("li")
                li.textContent = `${message.severity}: ${message.text}`
                return li
            } )
            ul.append(...entries)
        }
        root.append(ul)
    })
}

function showResult(jobId: string) {
    client.getJobResult(jobId).then(value => {
        let ul = document.createElement("ul");
        let entries = value.messages.map(message => {
            let li = document.createElement("li")
            li.textContent = `${message.severity}: ${message.text}`
            return li
        } )
        ul.append(...entries)
        getRoot().replaceChildren(ul)
        document.location.hash = `result-${jobId}`
    })
}

function refreshJobs() {
    if (document.location.hash == "") {
        client.getAllJobs().then(value => {
            let root = getRoot();

            if(value.length == 0) {
                let element = document.createElement("p");
                element.innerText = "No Jobs"
                root.replaceChildren(element)
                return
            }
            let newChildren = value.map(job => {
                let li = document.createElement("li");
                li.innerText = `Job - ${job.id} with type ${job.type} - ${job.status.type}`
                let statusLink = document.createElement("a");
                statusLink.onclick = ev => {
                    ev.preventDefault()
                    ev.stopPropagation()
                    allResults(job.id)
                }
                statusLink.textContent = "All Results"
                statusLink.href = "#"
                li.append(statusLink)
                li.append(" ")
                let resultLink = document.createElement("a");
                resultLink.onclick = ev =>  {
                    ev.preventDefault()
                    ev.stopPropagation()
                    showResult(job.id)
                }
                resultLink.textContent = "Latest Result"
                resultLink.href = "#"
                li.append(resultLink)
                if(job.type === CheckingJobType.Continuous) {
                    li.append(" ")
                    let subscribeLink = document.createElement("a");
                    subscribeLink.textContent = "Subscribe"
                    subscribeLink.onclick = ev => {
                        ev.preventDefault()
                        ev.stopPropagation()
                        subscribeToJob(job.id)
                        root.replaceChildren("Subscribed to Job " + job.id)
                    }
                    subscribeLink.href = "#"
                    li.append(subscribeLink)
                }
                return li
            })

            root.replaceChildren(...newChildren)
        })
    }
}
setTimeout(refreshJobs, 5000)

document.addEventListener("DOMContentLoaded", function () {
    let form = document.getElementById("new-job") as HTMLFormElement;
    form.onsubmit = ev => {
        ev.stopPropagation()
        ev.preventDefault()
        let nodeRef = document.getElementById("nodeRef") as HTMLInputElement;
        let continuous = document.getElementById("true") as HTMLInputElement;
        client.createCheckingJob(nodeRef.value, continuous.checked).then(() => refreshJobs())
    }
})

