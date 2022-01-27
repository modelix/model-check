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

// test/file.spec.ts
// Example of mocking using karma-typescript-mock

import {Client} from "../src/client";
import * as fetchMock from "fetch-mock";

describe('readFile()', () => {
    it("get stuff", () => {
        let client = new Client("http://localhost:8080/checker");
        client.getAllJobs()
    })
})