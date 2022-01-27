# modelix-model-check

Modelix model check allows accessing MPS, and in the future from other implementations, model check results from other 
processes or form other programming languages within the same process. 

Modelix model check hosts client and server implementations.

## Server 

The actual model check implementation is done is the `core`. The core is independent of the hosting environment and has
a generic interface that allows for different implementations behind the interface. Currently, the only supported source
for model check message is the MPS type system/checking rules. Other implementation are possibly added in the future. 

### MPS Plugin

The MPS plugin allows hosting the modelix model check server within an MPS instance using the integrated webserver of 
the IDE. This is useful for local testing and development scenarios where deploying containers would pose a large overhead. 
Another possible use case is editors that need access to model checking messages but aren't running in the JVM as MPS e.g. 
HTML/JavaScript that are embedded via JCEF inside of MPS.

### Modelix 

Work in progress.

## Client

Two clients are currently implemented a TypeScript client for JavaScript environment and a kotlin client. Both client 
implementations allow for "one off" checking of nodes and continuous checking of a node. When a node is checked continuously
client support streaming results via websockets so that they can react to new results being produced by the model checker.

### TypeScript

The TypeScript client is a simple fetch wrapper around the REST API of the model check server. It also allows for caching
results based on ETAG headers the server sends to prevent expensive rechecking of nodes that haven't changed. 

### Kotlin 

The kotlin client is a ktor client wrapper around the REST API of the model check server. It also allows for caching
results based on ETAG headers the server sends to prevent expensive rechecking of nodes that haven't changed. 
